package io.nekohasekai.sagernet.simple.home

import android.app.Application
import android.os.RemoteException
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.ConnectionClock
import io.nekohasekai.sagernet.simple.SimpleHooks
import io.nekohasekai.sagernet.simple.modes.Managed
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.simple.modes.WhitelistKeywords
import io.nekohasekai.sagernet.simple.regime.RegimeStore
import io.nekohasekai.sagernet.simple.servers.CountryFromName
import io.nekohasekai.sagernet.simple.servers.ServerCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The server card: what runs (or would run) when the button is pressed. */
data class ServerCard(
    /** The profile's name; for an auto-selector its own name. */
    val name: String,
    val auto: Boolean,
    /** The server the running auto-selector picked, "" while none is picked or for a plain profile. */
    val autoSelected: String = "",
    /** The latest measured delay in ms, null when unknown. */
    val latencyMs: Int? = null,
    /**
     * For a managed auto-selector of the traffic modes, its name in the current language (the stored name is in the
     * language it was created in); null otherwise.
     */
    @StringRes val localizedName: Int? = null,
    /** The country (ISO code) of the plain server, or of the server the auto-selector picked; null when unknown. */
    val country: String? = null,
    /** The country chosen in «Серверы» when the managed country selector runs; null otherwise. */
    val countryChoice: String? = null,
    /** The plain server, or the server the auto-selector picked, is a server for whitelists (WhitelistKeywords). */
    val whitelistServer: Boolean = false,
    /** How many servers of the running auto-selector work in this network; null before its first round. */
    val working: Working? = null,
) {
    data class Working(val alive: Int, val total: Int)
}

data class HomeUiState(
    val phase: ConnectionPhase = ConnectionPhase.OFF,
    /** `SystemClock.elapsedRealtime()` of the connection start, 0 when not connected. */
    val connectedSince: Long = 0L,
    /** Bytes per second through the proxy. */
    val txRate: Long = 0L,
    val rxRate: Long = 0L,
    /** null until the profiles are read. */
    val hasServers: Boolean? = null,
    val server: ServerCard? = null,
    val problem: HomeProblem? = null,
)

/**
 * The home screen over the same service connection the classic UI uses ([SagerConnection], design.md D4): the
 * service state, the speed, the connection time (kept by [SimpleHooks] in `:bg`), the current server and the problem
 * banner. While the screen is visible ([setForeground]) the running connection is URL-tested now and then, which is
 * how a server that stopped answering is noticed.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application), SagerConnection.Callback,
    ProfileManager.Listener {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND, true)

    private val mutableState = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-off snackbar texts (string resources) for the screen. */
    val messages: SharedFlow<Int> = mutableMessages.asSharedFlow()

    private var serviceState = BaseService.State.Idle
    private var userStopping = false
    private var lost: HomeProblem.ConnectionLost? = null
    private var urlTestFailures = 0

    /** The user hid «Сервер не отвечает»; it stays hidden until a check succeeds or the connection changes. */
    private var serverProblemHidden = false
    private var autoStatus = AutoSelectorStatus.IDLE
    private var foreground = false
    private var checkJob: Job? = null
    private var profilesJob: Job? = null

    /** The profile [connect] selects when the selected one is gone; 0 = keep the selection. */
    private var fallbackProfile = 0L

    /** The delay the last check of the running connection measured (through the auto-selector's pick, if any). */
    private var liveLatency: Int? = null

    /**
     * «Работают в этой сети: N из M» of the running auto-selector, from its member table: only members that answered
     * since the session first saw the current physical network count (HomeStatus.working); null until known.
     */
    private var workingCount: WorkingRead? = null
    private var workingJob: Job? = null

    /** A count read for one selector: it is shown only while that selector runs. */
    private data class WorkingRead(val selectorId: Long, val alive: Int, val total: Int)

    /** The selector changed while a member table was read: one more read follows that one. */
    private var workingAgain = false

    /** Reads the running selector's member table again, a moment after its status changed (rounds end, picks change). */
    private fun refreshWorkingLater() {
        if (workingJob?.isActive == true) {
            workingAgain = true
            return
        }
        workingAgain = false
        workingJob = viewModelScope.launch {
            delay(WORKING_DELAY_MS)
            val selectorId = selectedProfile?.takeIf { it.type == AutoSelectorProfiles.TYPE }?.id
            val status = autoStatus
            val service = connection.service
            val read = if (
                selectorId == null || service == null || serviceState != BaseService.State.Connected ||
                !status.active || status.selectorId != selectorId || status.suspended
            ) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        val full = AutoSelectorStatus.parse(service.autoSelectorStatus(true))
                        // The table must be the one of the selector this read is for.
                        if (full.selectorId != selectorId) return@withContext null
                        HomeStatus.working(
                            members = full.members.map { HomeStatus.MemberHealth(it.isUsable, it.lastOkMs, it.lastProbeMs) },
                            since = RegimeStore.currentNetworkSince(),
                            lastRoundMs = full.lastRoundMs,
                            suspended = full.suspended,
                        )?.let { (alive, total) -> WorkingRead(selectorId, alive, total) }
                    } catch (e: Exception) {
                        Logs.d("simple home: no member table: ${e.message}")
                        null
                    }
                }
            }
            // The selector may have changed while the table was read: a count of another one is dropped and read again.
            val stillRunning = autoStatus.selectorId == selectorId && autoStatus.active
            val count = read?.takeIf { stillRunning }
            if (count != workingCount) {
                workingCount = count
                publish()
            }
            // Read again only for another selector that runs now (not after the service stopped: nothing to read).
            if (autoStatus.active && autoStatus.selectorId != selectorId) workingAgain = true
        }.also { job ->
            job.invokeOnCompletion { cause ->
                if (cause == null && workingAgain) viewModelScope.launch { refreshWorkingLater() }
            }
        }
    }

    init {
        lost = DataStore.serviceError.takeIf { it.isNotBlank() }?.let { HomeProblem.ConnectionLost(it) }
        connection.connect(application, this)
        ProfileManager.addListener(this)
        viewModelScope.launch {
            AutoSelectorClient.status.collect { status ->
                val reselected = status.selectedId != autoStatus.selectedId
                autoStatus = status
                if (reselected) {
                    liveLatency = null
                    if (foreground && serviceState == BaseService.State.Connected) restartChecks(SOON_MS)
                    lookUpPickCountry(status.selectedId)
                }
                refreshWorkingLater()
                publish()
            }
        }
        refreshProfiles()
    }

    // ------------------------------------------------------------------------------------------------ screen events

    /** The screen became visible or hidden: the speed is sent only to a foreground connection. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        connection.updateConnectionId(
            if (visible) SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND
            else SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND
        )
        if (visible) {
            refreshProfiles()
            if (serviceState == BaseService.State.Connected) restartChecks(SOON_MS)
        } else {
            checkJob?.cancel()
            mutableState.update { it.copy(txRate = 0L, rxRate = 0L) }
        }
    }

    /**
     * The main button: stops in the connecting, protected and reconnecting phases, otherwise starts. [requestStart]
     * asks for the VPN consent and starts the service (the activity owns that request).
     */
    fun toggle(requestStart: () -> Unit) {
        val phase = mutableState.value.phase
        when {
            HomeStatus.buttonStops(phase) -> {
                userStopping = true
                publish()
                SagerNet.stopService()
            }

            phase == ConnectionPhase.DISCONNECTING -> Unit
            else -> viewModelScope.launch {
                if (!prepareSelection()) {
                    refreshProfiles()
                    return@launch
                }
                requestStart()
            }
        }
    }

    fun message(@StringRes text: Int) {
        mutableMessages.tryEmit(text)
    }

    /** Hides the banner; a loss is forgotten like a swiped error of the classic UI. */
    fun dismissProblem() {
        if (lost != null) {
            lost = null
            viewModelScope.launch(Dispatchers.IO) { clearServiceError() }
        }
        // Stays hidden while the server keeps failing; the next successful check or connection shows it again.
        if (mutableState.value.problem == HomeProblem.ServerNotResponding) serverProblemHidden = true
        publish()
    }

    override fun onCleared() {
        ProfileManager.removeListener(this)
        connection.disconnect(getApplication())
        super.onCleared()
    }

    // ------------------------------------------------------------------------------------------------ service

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        onState(state, msg)
    }

    override fun onServiceConnected(service: ISagerNetService) {
        val state = try {
            BaseService.State.entries[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        // A start left over from a service process that died without stopping (force stop, crash, reboot).
        if (!state.started && state != BaseService.State.Stopping) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { DataStore.configurationStore.remove(SimpleHooks.KEY_CONNECTED_SINCE) }
            }
        }
        onState(state, null, restartPending = false)
    }

    /**
     * The service process went away; the framework calls this before (or instead of) [onBinderDied], so both note
     * the loss from the state before either. A service Android restarts reports Connecting, which clears it again.
     */
    override fun onServiceDisconnected() {
        noteServiceDeath()
        onState(BaseService.State.Idle, null)
    }

    override fun onBinderDied() {
        noteServiceDeath()
        onState(BaseService.State.Idle, null)
        connection.disconnect(getApplication())
        connection.connect(getApplication(), this)
    }

    private fun noteServiceDeath() {
        if (HomeStatus.isLossOnServiceDeath(serviceState, userStopping)) lost = HomeProblem.ConnectionLost(null)
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        mutableState.update { it.copy(txRate = stats.txRateProxy, rxRate = stats.rxRateProxy) }
    }

    override fun cbSelectorUpdate(id: Long) = refreshProfiles()

    private fun onState(state: BaseService.State, msg: String?, restartPending: Boolean? = null) {
        val previous = mutableState.value.phase
        val since = readSince()
        val pending = restartPending ?: (since > 0L)
        val phase = HomeStatus.phase(state, userStopping, pending, previous)
        when (state) {
            BaseService.State.Connecting -> lost = null
            BaseService.State.Stopped -> {
                if (HomeStatus.isLoss(msg, userStopping)) lost = HomeProblem.ConnectionLost(msg)
            }

            else -> Unit
        }
        if (state == BaseService.State.Connected || !state.started && state != BaseService.State.Stopping) {
            userStopping = false
        }
        val wasConnected = serviceState == BaseService.State.Connected
        serviceState = state
        val connected = state == BaseService.State.Connected
        if (connected && !wasConnected) {
            urlTestFailures = 0
            serverProblemHidden = false
            if (foreground) restartChecks(FIRST_CHECK_MS)
        } else if (!connected && wasConnected) {
            checkJob?.cancel()
            urlTestFailures = 0
            serverProblemHidden = false
            liveLatency = null
            workingCount = null
        }
        mutableState.update {
            it.copy(
                phase = phase,
                connectedSince = when {
                    connected -> since.takeIf { s -> s > 0L } ?: it.connectedSince.takeIf { s -> s > 0L }
                    ?: SystemClock.elapsedRealtime()

                    phase == ConnectionPhase.RECONNECTING -> it.connectedSince
                    else -> 0L
                },
                txRate = if (connected) it.txRate else 0L,
                rxRate = if (connected) it.rxRate else 0L,
            )
        }
        publish()
        if (state == BaseService.State.Stopped) refreshProfiles()
    }

    /** The connection start the service keeps, 0 when none (or not plausible). */
    private fun readSince(): Long {
        val since = try {
            DataStore.configurationStore.getLong(SimpleHooks.KEY_CONNECTED_SINCE) ?: 0L
        } catch (e: Exception) {
            Logs.w(e)
            0L
        }
        return if (ConnectionClock.isValid(since, SystemClock.elapsedRealtime())) since else 0L
    }

    // ------------------------------------------------------------------------------------------------ server checks

    private fun restartChecks(firstDelay: Long) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            delay(firstDelay)
            while (isActive && serviceState == BaseService.State.Connected) {
                val service = connection.service
                val result = if (service == null) null else withContext(Dispatchers.IO) {
                    try {
                        service.urlTest()
                    } catch (e: Exception) {
                        Logs.d("simple home: url test failed: ${e.message}")
                        FAILED
                    }
                }
                if (!isActive || serviceState != BaseService.State.Connected) break
                when {
                    result == null -> Unit
                    result > 0 -> {
                        urlTestFailures = 0
                        serverProblemHidden = false
                        liveLatency = result
                    }

                    // -2: failed through an OpenVPN/OpenConnect exit whose tunnel is up; says nothing either way
                    result == VPN_EXIT_UNKNOWN -> Unit
                    else -> {
                        urlTestFailures++
                        liveLatency = null
                    }
                }
                refreshWorkingLater()
                publish()
                delay(if (urlTestFailures > 0) RETRY_MS else INTERVAL_MS)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ profiles

    // ProfileManager calls these on any thread: the refresh itself runs on the main one.
    override suspend fun onAdd(profile: ProxyEntity) = refreshProfilesLater()
    override suspend fun onUpdated(data: List<TrafficData>) = Unit
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        if (profile.id == DataStore.selectedProxy || profile.id == fallbackProfile) refreshProfilesLater()
        // The auto-selector's running member was renamed or re-tested: its name and country are read again.
        if (profile.id == pick?.id) viewModelScope.launch { lookUpPickCountry(profile.id, force = true) }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) = refreshProfilesLater()

    private fun refreshProfilesLater() {
        viewModelScope.launch { refreshProfiles() }
    }

    private var selectedProfile: ProxyEntity? = null

    /** The managed auto-selectors of the traffic modes, read with the profiles. */
    private var managed: Managed? = null

    /** The country of the selected plain server, and the country chosen in «Серверы» (read with the profiles). */
    private var selectedCountry: String? = null
    private var choiceCountry: String? = null

    /** The auto-selector's pick as the list shows it: its id, country (ISO code or null), name, whitelist category. */
    private class Pick(val id: Long, val country: String?, val name: String, val whitelist: Boolean = false)

    /** The selected plain server is a server for whitelists. */
    private var selectedWhitelist = false

    private var pick: Pick? = null

    /** Counts the pick lookups started; only the newest one may publish (a forced one may overtake an older one). */
    private var pickLookups = 0L

    /** The selected plain server's name as the list shows it (never its address). */
    private var selectedName: String = ""

    /** What one [refreshProfiles] read. */
    private class ProfilesSnapshot(
        val profile: ProxyEntity?,
        val fallback: Long,
        val hasServers: Boolean,
        val managed: Managed,
        val country: String?,
        val name: String,
        val choiceCountry: String?,
        /** The shown plain server is a server for whitelists. */
        val whitelist: Boolean,
    )

    /** Looks the running pick [id] up in the catalog; [force] reads it again (it may have been renamed or re-tested). */
    private fun lookUpPickCountry(id: Long, force: Boolean = false) {
        if (id <= 0L || !force && pick?.id == id) return
        val lookup = ++pickLookups
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                try {
                    ProfileManager.getProfile(id)?.let {
                        Pick(
                            id,
                            CountryFromName.resolve(it.outbound.name, it.testCountry).code,
                            ServerCatalog.nameOf(it, unnamed()),
                            isWhitelistServer(it),
                        )
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            // A lookup for a pick that is no longer running is dropped (a newer one is under way or done).
            if (lookup != pickLookups || autoStatus.selectedId != id) return@launch
            pick = found ?: Pick(id, null, "")
            publish()
        }
    }

    /** Reads the selected server again (a traffic mode may have switched the managed auto-selector). */
    fun refreshServer() = refreshProfiles()

    private fun refreshProfiles() {
        profilesJob?.cancel()
        profilesJob = viewModelScope.launch {
            // Everything is read into one snapshot and assigned here on the main thread: a refresh cancelled while
            // reading cannot leave its (older) metadata behind for the profile a newer refresh publishes.
            val snapshot = withContext(Dispatchers.IO) {
                try {
                    val managedIds = TrafficModes.managedIds()
                    val selected = ProfileManager.getProfile(DataStore.selectedProxy)
                    val first = if (selected == null) firstProfile() else null
                    val shown = selected ?: first
                    // The managed auto-selectors of the traffic modes are no server of their own.
                    val hasServers = TrafficModes.hasServers() ||
                        selected != null && !TrafficModes.isManagedSelector(selected.id)
                    ProfilesSnapshot(
                        profile = shown,
                        fallback = first?.id ?: 0L,
                        hasServers = hasServers,
                        managed = managedIds,
                        country = shown?.let { CountryFromName.resolve(it.outbound.name, it.testCountry).code },
                        name = shown?.let { ServerCatalog.nameOf(it, unnamed()) }.orEmpty(),
                        choiceCountry = TrafficModes.countryChoice()?.code
                            ?.takeIf { selected != null && selected.id == managedIds.country },
                        whitelist = shown != null && shown.type != AutoSelectorProfiles.TYPE && isWhitelistServer(shown),
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            if (snapshot == null) {
                selectedProfile = null
                fallbackProfile = 0L
                mutableState.update { it.copy(hasServers = it.hasServers ?: false) }
                publish()
                return@launch
            }
            selectedProfile = snapshot.profile
            fallbackProfile = snapshot.fallback
            managed = snapshot.managed
            selectedCountry = snapshot.country
            selectedName = snapshot.name
            choiceCountry = snapshot.choiceCountry
            selectedWhitelist = snapshot.whitelist
            mutableState.update { it.copy(hasServers = snapshot.hasServers) }
            publish()
            // The profiles may have changed in :bg (a subscription update renamed the running member).
            if (autoStatus.active && autoStatus.selectedId > 0L) lookUpPickCountry(autoStatus.selectedId, force = true)
        }
    }

    /** The first profile in list order, what a start uses when nothing valid is selected. */
    private fun firstProfile(): ProxyEntity? = SagerDatabase.groupDao.allGroups().firstNotNullOfOrNull { group ->
        ProfileManager.members(group.id).firstOrNull { !TrafficModes.isManagedSelector(it.id) }
    }

    /** Selects [fallbackProfile] when the selected profile is gone; false when there is nothing to start. */
    private suspend fun prepareSelection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (ProfileManager.getProfile(DataStore.selectedProxy) != null) return@withContext true
            val first = firstProfile() ?: return@withContext false
            DataStore.selectedProxy = first.id
            true
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }

    // ------------------------------------------------------------------------------------------------ output

    private fun publish() {
        val connected = serviceState == BaseService.State.Connected
        val profile = selectedProfile
        val auto = profile?.type == AutoSelectorProfiles.TYPE
        val running = autoStatus.takeIf { auto && it.active && it.selectorId == profile?.id }
        val currentPick = pick?.takeIf { running != null && it.id == running.selectedId }
        val server = profile?.let {
            ServerCard(
                name = selectedName.ifEmpty { CountryFromName.tidy(it.displayName()) },
                auto = auto,
                // The core reports an unnamed member by its address: the name as the list shows it, once looked up.
                // The catalog's name only: the core reports an unnamed member by its address. Until the lookup is
                // through the card says that Auto is choosing; a member the catalog does not know is «unnamed».
                autoSelected = currentPick?.name?.ifEmpty { unnamed() }.orEmpty(),
                latencyMs = if (connected) liveLatency else it.latency.takeIf { ms -> ms > 0 && !auto },
                localizedName = when (it.id) {
                    managed?.auto -> R.string.simple_selector_auto_name
                    managed?.autoWhitelist -> R.string.simple_selector_auto_whitelist_name
                    else -> null
                },
                country = if (auto) {
                    currentPick?.country
                } else {
                    selectedCountry
                },
                countryChoice = choiceCountry,
                whitelistServer = if (auto) currentPick?.whitelist == true else selectedWhitelist,
                working = workingCount?.takeIf { running != null && !running.suspended && it.selectorId == running.selectorId }
                    ?.let { ServerCard.Working(it.alive, it.total) },
            )
        }
        val exhausted = running != null && running.exhaustedSinceMs > 0L
        mutableState.update {
            it.copy(
                server = server,
                problem = HomeStatus.problem(lost, connected, urlTestFailures, exhausted, serverProblemHidden),
            )
        }
    }

    private fun clearServiceError() {
        try {
            DataStore.serviceError = ""
            DataStore.serviceErrorDns = false
            DataStore.serviceErrorGeo = false
            DataStore.serviceErrorRuleSets = false
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun unnamed(): String = getApplication<Application>().getString(R.string.simple_server_unnamed)

    /** By the name the list sorts it with («Для белых списков», ServerCatalog); blocking. */
    private fun isWhitelistServer(profile: ProxyEntity): Boolean =
        WhitelistKeywords.isWhitelistServer(profile.outbound.name.trim(), SimpleConfigRepo.current().wlKeywords)

    private companion object {
        const val FIRST_CHECK_MS = 2_000L
        const val SOON_MS = 1_000L
        const val INTERVAL_MS = 30_000L
        const val RETRY_MS = 10_000L
        const val WORKING_DELAY_MS = 500L
        const val FAILED = -1
        const val VPN_EXIT_UNKNOWN = -2
    }
}
