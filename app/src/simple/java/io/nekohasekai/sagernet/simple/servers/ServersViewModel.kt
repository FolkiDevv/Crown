package io.nekohasekai.sagernet.simple.servers

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.Managed
import io.nekohasekai.sagernet.simple.modes.TrafficModeApplier
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.ui.test.RowPhase
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.ui.test.TestUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the card of one subscription shows (simple-ui spec "Сведения о подписке"). */
data class SubscriptionCard(
    val groupId: Long,
    val name: String,
    /** Bytes left, null when the provider reports no quota or an unlimited one. */
    val remaining: Long?,
    val total: Long,
    val traffic: SubscriptionHealth.Traffic,
    /** Epoch seconds, 0 when not reported. */
    val expire: Long,
    val expiry: SubscriptionHealth.Expiry,
    /** Epoch seconds of the last successful update, 0 = never. */
    val lastUpdate: Long,
    val refreshing: Boolean,
)

/** The server the running auto-selector picked, for «Авто» or the chosen country. */
data class RunningPick(val selectorId: Long, val name: String, val country: String?)

data class ServersUiState(
    /** null until the first read. */
    val sections: ServerSections? = null,
    val cards: Map<Long, SubscriptionCard> = emptyMap(),
    val selected: Long = 0L,
    val managed: Managed = Managed(0L, 0L, 0L, 0L),
    val choice: CountryChoice? = null,
    val pick: RunningPick? = null,
    /** The pull-to-refresh indicator: a manual update of the subscriptions runs. */
    val refreshing: Boolean = false,
    /** A choice is being applied. */
    val busy: Boolean = false,
) {
    val autoSelected: Boolean get() = selected > 0L && (selected == managed.auto || selected == managed.autoWhitelist)
    val countrySelected: Boolean get() = selected > 0L && selected == managed.country && choice != null
}

/** A snackbar text: [text] with [arg] as its only argument, if any. */
data class ServersMessage(@StringRes val text: Int, val arg: String? = null)

/**
 * The «Серверы» section (simple-ui spec "Структура списка серверов", "Выбор сервера в списке", "Определение страны
 * сервера", "Сведения о подписке"): the list read from the database, the choice of «Авто», a country or a server
 * (applied to a running connection at once), the manual update of subscriptions, and the background checks run when
 * the section opens — a URL test of every listed server, then an exit IP check of the servers whose name names no
 * country. Both run as ordinary test sessions of `:bg` (TestSessionClient), which keeps their results in the profiles.
 */
class ServersViewModel(application: Application) : AndroidViewModel(application), ProfileManager.Listener {

    private val mutableState = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<ServersMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<ServersMessage> = mutableMessages.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<ServersEvent>(extraBufferCapacity = 4)

    /** For the activity: the other sections follow a choice or an update. */
    val events: SharedFlow<ServersEvent> = mutableEvents.asSharedFlow()

    private var loadJob: Job? = null
    private val choosing = Mutex()

    /** Subscription ids being updated (SubscriptionClient's queue). */
    private var updating: Set<Long> = emptySet()

    /** The subscriptions this section asked to update, with the time of the request (elapsed realtime). */
    private val requested = HashMap<Long, Long>()

    /** Those of [requested] that pull to refresh asked for. */
    private val pulled = HashSet<Long>()
    private var pullUntil = 0L

    /** The background test session this section started, and the state before it (to tell its states apart). */
    private var ownTest: Int = NONE
    private var stateBeforeTest: TestUiState? = null
    private var lastUrlTest = 0L

    /** The servers the URL tests since [lastUrlTest] covered. */
    private val testedIds = HashSet<Long>()

    /**
     * The servers whose country this section has tried to find once since it was created (a URL test, then an exit
     * IP check if they answered), whatever the outcome; later rounds check them again without «Страна определяется…».
     */
    private val countryChecked = HashSet<Long>()

    /** Servers of the current round shown as «Страна определяется…» until the round is through. */
    private val roundPending = HashSet<Long>()

    /** Servers whose exit IP was checked at least once (they come after the others in the next batch). */
    private val ipAttempted = HashSet<Long>()

    /** Servers added while a round ran, tested right after it ([testAgain]). */
    private val queued = HashSet<Long>()
    private var testAgain = false

    /**
     * A country round runs: from the start of its URL test until its exit IP check (if any) is through. A test asked
     * for meanwhile waits for the round ([queued]) instead of starting one the ending round would then overwrite.
     */
    private var roundBusy = false

    /** A [reload] asked to test afterwards. */
    private var testAfterLoad = false
    private var detecting: Set<Long> = emptySet()

    /** Live results of the running URL test, until the database has them. */
    private val liveLatency = HashMap<Long, Int>()

    private var autoStatus = AutoSelectorStatus.IDLE
    /** The running pick as the list shows it: (profile id, (country, name)). */
    private var pickInfo: Pair<Long, Pair<String?, String>>? = null

    /** The pick lookup under way; a newer one cancels it, so an older result never overwrites a fresher one. */
    private var pickJob: Job? = null

    init {
        ProfileManager.addListener(this)
        viewModelScope.launch {
            SubscriptionClient.states.collect { states -> onSubscriptionStates(states.keys) }
        }
        viewModelScope.launch {
            SubscriptionClient.reports.collect { report ->
                if (report.error && requested.remove(report.gid) != null) {
                    publishCards()
                    mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_update_failed, report.text.trim()))
                }
            }
        }
        viewModelScope.launch {
            TestSessionClient.state.collect(::onTestState)
        }
        viewModelScope.launch {
            AutoSelectorClient.status.collect { status ->
                autoStatus = status
                publishPick()
            }
        }
        reload()
    }

    // ------------------------------------------------------------------------------------------------ screen events

    /** The section became visible: the list is read again and the background checks run (at most every 2 min). */
    fun opened() {
        reload(thenTest = true)
    }

    /** Reads the list again (another section or the classic interface may have changed it). */
    fun reload(thenTest: Boolean = false) {
        // A newer read replaces an older one, but not its request to test afterwards.
        if (thenTest) testAfterLoad = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    load()
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            } ?: return@launch
            // The live results of a running test go over the stored ones here, on the main thread that owns them.
            val live = loaded.copy(sections = loaded.sections?.let(::withLiveLatency))
            mutableState.update { live.copy(pick = it.pick, refreshing = it.refreshing, busy = it.busy) }
            publishCards()
            // Names and countries may have changed (an update in :bg): the running pick is looked up again.
            pickInfo = null
            publishPick()
            if (testAfterLoad) {
                testAfterLoad = false
                startUrlTest()
            }
        }
    }

    fun chooseAuto() = choose {
        val auto = mutableState.value.managed.auto.takeIf { it > 0L }
            ?: withContext(Dispatchers.IO) { TrafficModeApplier.ensure(getApplication()).ids.auto }
        if (auto <= 0L) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
            return@choose
        }
        TrafficModeApplier.selectServer(auto)
    }

    fun chooseCountry(row: CountryRow) {
        val code = row.code ?: return
        choose {
            val choice = CountryChoice(row.groupId, code)
            val members = withContext(Dispatchers.IO) {
                ServerCatalog.countryMembers(choice, SimpleConfigRepo.current().wlKeywords)
            }
            if (members.isEmpty() ||
                TrafficModeApplier.selectCountry(getApplication(), choice, members) <= 0L
            ) {
                mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
            }
        }
    }

    fun chooseServer(server: ServerEntry) = choose { TrafficModeApplier.selectServer(server.id) }

    /** Updates one subscription now (the card's button). */
    fun update(groupId: Long) = update(groupId, pulled = false)

    /** [pulled]: asked for by pull to refresh, whose indicator then follows this update. */
    private fun update(groupId: Long, pulled: Boolean) {
        // An update that is queued or running already is not asked for again (repeated pulls); a pull still shows
        // its indicator until that update is through.
        if (groupId in updating || groupId in requested) {
            if (pulled) {
                requested.putIfAbsent(groupId, SystemClock.elapsedRealtime())
                this.pulled += groupId
            }
            return
        }
        requested[groupId] = SystemClock.elapsedRealtime()
        if (pulled) this.pulled += groupId
        SubscriptionClient.refreshGroup(groupId, false)
        publishCards()
        // Until the queue reports it, the card shows the update for a moment; then it follows the queue.
        viewModelScope.launch {
            delay(PULL_GRACE_MS)
            publishCards()
        }
    }

    /** Pull to refresh: updates every subscription of the list. */
    fun updateAll() {
        val ids = mutableState.value.cards.keys
        if (ids.isEmpty()) {
            reload(thenTest = true)
            return
        }
        // The indicator shows at once and stays until the queue reports the updates (or briefly, if it never does).
        pullUntil = SystemClock.elapsedRealtime() + PULL_GRACE_MS
        mutableState.update { it.copy(refreshing = true) }
        ids.forEach { update(it, pulled = true) }
        publishCards()
        // Every deadline gets its publication: the indicator goes away then unless a pulled update still runs.
        viewModelScope.launch {
            delay(PULL_GRACE_MS)
            publishCards()
        }
    }

    override fun onCleared() {
        ProfileManager.removeListener(this)
        super.onCleared()
    }

    // ------------------------------------------------------------------------------------------------ choice

    private fun choose(block: suspend () -> Unit) {
        if (choosing.isLocked) return
        viewModelScope.launch {
            choosing.withLock {
                mutableState.update { it.copy(busy = true) }
                try {
                    block()
                } catch (e: Exception) {
                    Logs.w("simple servers: choice failed", e)
                    mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
                } finally {
                    mutableState.update { it.copy(busy = false) }
                    reload()
                    mutableEvents.tryEmit(ServersEvent.Chosen)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    private fun load(): ServersUiState {
        val context = getApplication<Application>()
        val keywords = SimpleConfigRepo.current().wlKeywords
        val snapshot = ServerCatalog.load(keywords, detecting, context.getString(R.string.simple_server_unnamed))
        val servers = snapshot.servers
        val locale = context.resources.configuration.locales[0]
        val sections = ServerList.build(snapshot.entries, servers) { CountryLabels.name(it, locale) }
        val now = System.currentTimeMillis() / 1000
        val cards = snapshot.groups.filter { it.isSubscription }.associate { group ->
            val info = group.subInfo
            val valid = info.valid
            group.id to SubscriptionCard(
                groupId = group.id,
                name = group.displayName(),
                remaining = if (valid && info.hasQuota && info.total > 0L) info.remaining else null,
                total = info.total,
                traffic = SubscriptionHealth.traffic(valid && info.hasQuota, info.total, info.used),
                expire = if (valid) info.expire else 0L,
                expiry = SubscriptionHealth.expiry(if (valid) info.expire else 0L, now),
                lastUpdate = group.subLastUpdate,
                refreshing = group.id in updating,
            )
        }
        return ServersUiState(
            sections = sections,
            cards = cards,
            selected = DataStore.selectedProxy,
            managed = TrafficModes.managedIds(),
            choice = TrafficModes.countryChoice(),
        )
    }

    // ProfileManager calls these for changes made in this process (the classic editors, the add flow).
    override suspend fun onAdd(profile: ProxyEntity) = reload()
    override suspend fun onUpdated(data: List<TrafficData>) = Unit
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit
    override suspend fun onRemoved(groupId: Long, profileId: Long) = reload()

    // ------------------------------------------------------------------------------------------------ subscriptions

    private fun onSubscriptionStates(ids: Set<Long>) {
        val finished = updating - ids
        updating = ids
        if (ids.any { it in pulled }) pullUntil = 0L
        requested.keys.removeAll(finished)
        pulled.removeAll(finished)
        publishCards()
        if (finished.isEmpty()) return
        // The update ran in :bg: the selectors follow the new servers, the list and the delays are read again.
        mutableEvents.tryEmit(ServersEvent.Updated)
        lastUrlTest = 0L
        reload(thenTest = true)
    }

    private fun publishCards() {
        val now = SystemClock.elapsedRealtime()
        val pulling = now < pullUntil
        fun justRequested(id: Long) = requested[id]?.let { now - it < PULL_GRACE_MS } == true
        // A request that never reached the queue (it failed at once) is forgotten after the grace period.
        requested.entries.removeAll { (id, at) -> id !in updating && now - at >= PULL_GRACE_MS }
        pulled.retainAll(requested.keys)
        mutableState.update { state ->
            state.copy(
                cards = state.cards.mapValues { (id, card) -> card.copy(refreshing = id in updating || justRequested(id)) },
                // Only pull to refresh shows the indicator at the top; a card's own button shows its spinner.
                refreshing = pulling || state.cards.keys.any { it in updating && it in pulled },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ background tests

    private fun startUrlTest() {
        val now = SystemClock.elapsedRealtime()
        val all = mutableState.value.sections?.allServers?.map { it.id }.orEmpty()
        // Within the interval only servers added since the last round are tested (a server just added by hand).
        val throttled = lastUrlTest > 0L && now - lastUrlTest < TEST_INTERVAL_MS
        val ids = if (throttled) all.filter { it !in testedIds } else all
        if (ids.isEmpty()) return
        if (ownTest != NONE || roundBusy) {
            // A round runs: the servers it does not cover (one just added) are tested right after it, and those whose
            // country is still open are «being detected» meanwhile.
            val waiting = mutableState.value.sections?.allServers
                ?.filter { it.id in ids && it.id !in testedIds && it.country !is ServerCountry.Known && it.id !in countryChecked }
                ?.map { it.id }.orEmpty()
            testAgain = true
            if (waiting.isNotEmpty() && !detecting.containsAll(waiting)) {
                queued += waiting
                detecting = detecting + waiting
                reload()
            }
            return
        }
        val before = TestSessionClient.state.value
        // Another session (one the user started in the classic interface) runs: this round is skipped.
        if (!TestSessionClient.startProfiles(TestSpec.KIND_URL, ids)) return
        if (!throttled) {
            lastUrlTest = now
            testedIds.clear()
        }
        testedIds += ids
        stateBeforeTest = before
        ownTest = TestSpec.KIND_URL
        roundBusy = true
        liveLatency.clear()
        // A server whose country neither the name nor an earlier exit IP check gave is «being detected» until its
        // checks are through, rather than «unknown» at first.
        val pending = mutableState.value.sections?.allServers
            ?.filter { it.id in ids && it.country !is ServerCountry.Known && it.id !in countryChecked }
            ?.map { it.id }.orEmpty()
        if (pending.isNotEmpty()) {
            roundPending += pending
            detecting = detecting + pending
            reload()
        }
    }

    /** The exit IP check of the servers whose country the name does not give, which answered the URL test. */
    private fun startIpTest() {
        val candidates = mutableState.value.sections?.allServers
            ?.filter { it.country !is ServerCountry.Known && (it.latency > 0 || it.latency == ProxyEntity.LATENCY_CONNECT_ONLY) }
            ?.map { it.id }
            .orEmpty()
        // At most IP_TEST_LIMIT per round, those never checked first, so a long list is covered over the rounds.
        val ids = candidates.sortedBy { it in ipAttempted }.take(IP_TEST_LIMIT)
        // Servers left for a later round are not through with their first check yet.
        roundPending.removeAll((candidates - ids.toSet()).toSet())
        val before = TestSessionClient.state.value
        if (ids.isEmpty() || !TestSessionClient.startProfiles(TestSpec.KIND_IP, ids)) {
            // Nothing answered (or another session runs): this round is through, what was detected is unknown.
            finishRound()
            return
        }
        stateBeforeTest = before
        ownTest = TestSpec.KIND_IP
        ipAttempted += ids
        // The servers that did not answer are through; only those checked for the first time are «being detected».
        countryChecked += roundPending - ids.toSet()
        detecting = ids.filterTo(HashSet()) { it !in countryChecked } + queued
        reload()
    }

    /** The country round is over: its servers count as checked and nothing is «being detected» any more. */
    private fun finishRound() {
        roundBusy = false
        countryChecked += roundPending
        roundPending.clear()
        // Servers queued for the next round stay «being detected».
        val next = queued.toSet()
        if (detecting != next) {
            detecting = next
            reload()
        }
        startQueued()
    }

    /** Tests what was added while the last round ran (see [startUrlTest]). */
    private fun startQueued() {
        if (!testAgain) return
        testAgain = false
        viewModelScope.launch {
            loadJob?.join()
            queued.clear()
            startUrlTest()
            // Nothing to test after all (or another session runs): nothing is «being detected» any more.
            if (ownTest == NONE && detecting.isNotEmpty()) {
                detecting = emptySet()
                reload()
            }
        }
    }

    private fun onTestState(test: TestUiState) {
        if (ownTest == NONE || test === stateBeforeTest) return
        if (test.kind != ownTest) {
            // The session ended and another one (not this section's) runs now.
            if (test.running) {
                ownTest = NONE
                roundBusy = false
                roundPending.clear()
                queued.clear()
                if (detecting.isNotEmpty()) {
                    detecting = emptySet()
                    reload()
                }
            }
            return
        }
        if (ownTest == TestSpec.KIND_URL) {
            var changed = false
            for ((id, row) in test.rows) {
                if (row.phase != RowPhase.DONE) continue
                val latency = when {
                    row.ok -> row.latency.takeIf { it != 0 } ?: ProxyEntity.LATENCY_CONNECT_ONLY
                    row.failed -> -1
                    else -> continue
                }
                if (liveLatency.put(id, latency) != latency) changed = true
            }
            if (changed) publishLatency()
        }
        if (test.running) return
        val finished = ownTest
        ownTest = NONE
        stateBeforeTest = null
        // The panel of the classic interface would otherwise show this section's checks.
        TestSessionClient.dismiss()
        viewModelScope.launch {
            // :bg has stored the results; read them, then check the exit IPs the names leave open.
            if (finished == TestSpec.KIND_IP) {
                countryChecked += detecting - queued
                countryChecked += roundPending
                roundPending.clear()
                detecting = queued.toSet()
                roundBusy = false
            }
            loadJob?.join()
            reload()
            loadJob?.join()
            liveLatency.clear()
            if (finished == TestSpec.KIND_URL) startIpTest() else startQueued()
        }
    }

    private fun publishLatency() {
        mutableState.update { state -> state.copy(sections = state.sections?.let(::withLiveLatency)) }
    }

    /** [sections] with the live URL test results over the stored delays; main thread only (it owns [liveLatency]). */
    private fun withLiveLatency(sections: ServerSections): ServerSections {
        if (liveLatency.isEmpty()) return sections
        fun ServerEntry.live() = liveLatency[id]?.let { copy(latency = it) } ?: this
        return sections.copy(
            subscriptions = sections.subscriptions.map { s ->
                s.copy(countries = s.countries.map { row -> row.copy(servers = row.servers.map { it.live() }) })
            },
            mine = sections.mine.map { it.live() },
            whitelist = sections.whitelist.map { it.live() },
        )
    }

    // ------------------------------------------------------------------------------------------------ running pick

    private fun publishPick() {
        val status = autoStatus
        val selected = mutableState.value.selected
        if (!status.active || status.selectorId != selected || status.selectedId <= 0L) {
            mutableState.update { it.copy(pick = null) }
            return
        }
        val known = pickInfo?.takeIf { it.first == status.selectedId }?.second
        if (known != null) {
            // The core names an unnamed member by its address: the list's name for it instead.
            val name = known.second.ifEmpty { getApplication<Application>().getString(R.string.simple_server_unnamed) }
            mutableState.update { it.copy(pick = RunningPick(status.selectorId, name, known.first)) }
            return
        }
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    ProfileManager.getProfile(status.selectedId)?.let {
                        CountryFromName.resolve(it.outbound.name, it.testCountry).code to
                            ServerCatalog.nameOf(it, getApplication<Application>().getString(R.string.simple_server_unnamed))
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            // A lookup for a pick that is no longer running is dropped.
            if (autoStatus.selectedId != status.selectedId) return@launch
            pickInfo = status.selectedId to (info ?: (null to ""))
            publishPick()
        }
    }

    private companion object {
        const val NONE = -1
        const val TEST_INTERVAL_MS = 2 * 60_000L
        const val PULL_GRACE_MS = 3_000L

        /** Every exit IP check asks a lookup service; a list without country names is checked in part. */
        const val IP_TEST_LIMIT = 30
    }
}

enum class ServersEvent {
    /** The user chose «Авто», a country or a server. */
    Chosen,

    /** A subscription update finished in `:bg`. */
    Updated,
}
