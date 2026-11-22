package io.nekohasekai.sagernet.simple.modes

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The two app lists the simple UI keeps, both edited in the classic AppManagerActivity. */
enum class AppList {
    /** The apps of «Только выбранные приложения». */
    SELECTED,

    /** «Приложения без VPN». */
    BYPASS,
}

data class ModesUiState(
    val mode: TrafficMode = TrafficMode.AUTO,
    /** null until read. */
    val card: ModeCard? = null,
    val selectedApps: Int = 0,
    val bypassApps: Int = 0,
    /** «Только выбранные приложения» lost its last app and «Авто» took over. */
    val fallback: Boolean = false,
    /** A mode is being applied. */
    val busy: Boolean = false,
    /** Counts finished applications, so the home screen reads the server again after one (it may have changed). */
    val applied: Int = 0,
)

/**
 * The traffic modes for the settings section and the home screen's mode card. On its first start the simple UI puts
 * the stored (or default) mode into effect; afterwards a mode is applied when the user picks it or edits one of its
 * app lists, so routing chosen in the classic interface stays until then (the card then shows it as custom routing).
 */
class ModesViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application),
    GroupRepo.Listener {

    private val mutableState = MutableStateFlow(ModesUiState())
    val state: StateFlow<ModesUiState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    /** One-off snackbar texts (string resources) for the settings section. */
    val messages: SharedFlow<Int> = mutableMessages.asSharedFlow()

    /** The app list being edited in AppManagerActivity; kept across a process death while it is open. */
    private var editing: AppList?
        get() = saved.get<String>(KEY_EDITING)?.let { name -> AppList.entries.firstOrNull { it.name == name } }
        set(value) = saved.set(KEY_EDITING, value?.name)

    /** The edit was opened by picking «Только выбранные приложения» with no app chosen yet. */
    private var choosingAppsMode: Boolean
        get() = saved.get<Boolean>(KEY_CHOOSING) ?: false
        set(value) = saved.set(KEY_CHOOSING, value)

    private val work = Mutex()

    /** An application is running; the screen disables the choices meanwhile. */
    @Volatile
    private var busy = false

    @Volatile
    private var applied = 0

    init {
        GroupRepo.addListener(this)
        // An app list edit in flight (this is a new process after Android stopped the old one meanwhile) brings its
        // list back through appListClosed; applying before that would overwrite what was edited.
        val resuming = editing != null
        viewModelScope.launch {
            applying {
                // The first start, or an include list without apps left behind: apply now. Otherwise only bring the
                // managed entities up to date (groups added meanwhile, a newer configuration).
                val applyNow = !resuming && withContext(Dispatchers.IO) {
                    !TrafficModes.isModeStored() || TrafficModeApplier.hasEmptyIncludeList()
                }
                if (applyNow) {
                    TrafficModeApplier.apply(getApplication())
                } else {
                    TrafficModeApplier.ensure(getApplication())
                }
            }
        }
    }

    /**
     * Reads the state again (the screen came back, the service state changed); creates the managed entities that
     * could not be created before (the auto-selectors wait for the first server).
     */
    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val ids = TrafficModes.managedIds()
                    if (TrafficModes.isModeStored() && listOf(ids.smartRoute, ids.whitelistRoute, ids.auto, ids.autoWhitelist).any { it <= 0L }) {
                        work.withLock { TrafficModeApplier.ensure(getApplication()) }
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            publish()
        }
    }

    /**
     * The user picked [mode]. «Только выбранные приложения» without a chosen app opens the app list first
     * ([openAppList]); the mode is switched on only once an app is chosen there.
     */
    fun select(mode: TrafficMode, openAppList: (AppList) -> Unit) {
        if (mutableState.value.busy) return
        viewModelScope.launch {
            if (mode == TrafficMode.APPS && withContext(Dispatchers.IO) { TrafficModes.selectedApps().isEmpty() }) {
                choosingAppsMode = true
                edit(AppList.SELECTED, openAppList)
                return@launch
            }
            applying {
                TrafficModeApplier.select(getApplication(), mode)
            }
        }
    }

    /**
     * Opens [list] in AppManagerActivity: the per-app keys are set to it first, as that screen edits them. The keys in
     * effect before are kept and put back when it closes, so the application then compares against the scope the
     * service really runs with (otherwise an added bypass app looks like no change and the TUN keeps the old list).
     */
    fun edit(list: AppList, openAppList: (AppList) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val apps = if (list == AppList.SELECTED) TrafficModes.selectedApps() else TrafficModes.bypassApps()
                saved[KEY_PRE_PROXY_APPS] = DataStore.proxyApps
                saved[KEY_PRE_BYPASS] = DataStore.bypass
                saved[KEY_PRE_INDIVIDUAL] = DataStore.individual
                saved[KEY_PRE_STARTS] = TrafficModeApplier.serviceStarts()
                DataStore.proxyApps = true
                DataStore.bypass = list == AppList.BYPASS
                DataStore.individual = apps.joinToString("\n")
            }
            editing = list
            openAppList(list)
        }
    }

    /** AppManagerActivity closed: its list becomes the simple list, and the mode is applied again. */
    fun appListClosed() {
        val list = editing ?: return
        editing = null
        val choosing = choosingAppsMode
        choosingAppsMode = false
        viewModelScope.launch {
            val own = getApplication<Application>().packageName
            // A core started meanwhile (a reconnect, a switch from the notification) built its TUN from the staged keys.
            var stagedScopeMayRun = false
            val apps = withContext(Dispatchers.IO) {
                val edited = TrafficModePlan.parseList(DataStore.individual) - own
                val startsBefore = saved.get<Long>(KEY_PRE_STARTS)
                stagedScopeMayRun = TrafficModePlan.stagedScopeMayRun(
                    startsAtEdit = startsBefore,
                    startsNow = TrafficModeApplier.serviceStarts(),
                    startInProgress = DataStore.serviceState == BaseService.State.Connecting,
                )
                saved.remove<Long>(KEY_PRE_STARTS)
                restorePreEditScope()
                edited
            }
            applying {
                withContext(Dispatchers.IO) {
                    if (list == AppList.SELECTED) TrafficModes.setSelectedApps(apps) else TrafficModes.setBypassApps(apps)
                }
                when {
                    choosing && apps.isNotEmpty() -> TrafficModeApplier.select(getApplication(), TrafficMode.APPS, stagedScopeMayRun)
                    choosing -> {
                        // Nothing chosen: the mode stays as it was, and the per-app keys go back to it.
                        mutableMessages.tryEmit(R.string.simple_mode_apps_none_chosen)
                        TrafficModeApplier.apply(getApplication(), stagedScopeMayRun)
                    }

                    else -> TrafficModeApplier.apply(getApplication(), stagedScopeMayRun)
                }
            }
        }
    }

    /** The per-app keys [edit] found, back in DataStore; nothing when they were not recorded. */
    private fun restorePreEditScope() {
        val proxyApps = saved.get<Boolean>(KEY_PRE_PROXY_APPS) ?: return
        DataStore.proxyApps = proxyApps
        DataStore.bypass = saved.get<Boolean>(KEY_PRE_BYPASS) ?: true
        DataStore.individual = saved.get<String>(KEY_PRE_INDIVIDUAL).orEmpty()
        saved.remove<Boolean>(KEY_PRE_PROXY_APPS)
        saved.remove<Boolean>(KEY_PRE_BYPASS)
        saved.remove<String>(KEY_PRE_INDIVIDUAL)
    }

    fun dismissFallback() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { TrafficModes.setFallbackNotice(false) }
            publish()
        }
    }

    /**
     * The ready settings of the first subscription (simple-ui "Готовые настройки после импорта подписки"): the
     * managed «Авто» selector becomes the server and «Авто» the traffic mode. True when both were set.
     */
    suspend fun setUpAuto(): Boolean {
        var done = false
        applying {
            withContext(Dispatchers.IO) {
                val app = getApplication<android.app.Application>()
                val auto = TrafficModes.ensureManaged(app).auto
                if (auto <= 0L) return@withContext null
                val before = DataStore.selectedProxy
                DataStore.selectedProxy = auto
                val result = TrafficModeApplier.select(app, TrafficMode.AUTO)
                // The applier compares with the server already switched above; a running service follows here.
                if (before != auto && result.change == ServiceChange.NONE && DataStore.serviceState.canStop) {
                    SagerNet.reloadService()
                }
                done = true
                result
            }
        }
        return done
    }

    override fun onCleared() {
        GroupRepo.removeListener(this)
        super.onCleared()
    }

    // The managed auto-selectors follow the groups (design.md D5).
    override suspend fun groupAdd(group: ProxyGroup) = syncGroups()
    override suspend fun groupRemoved(groupId: Long) = syncGroups()
    override suspend fun groupUpdated(group: ProxyGroup) = Unit
    override suspend fun groupUpdated(groupId: Long) = Unit

    private fun syncGroups() {
        viewModelScope.launch {
            work.withLock {
                withContext(Dispatchers.IO) {
                    try {
                        TrafficModeApplier.ensure(getApplication())
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }
    }

    /** Runs [block] (an application) with the busy flag, reports a fallback to «Авто», and publishes the result. */
    private suspend fun applying(block: suspend () -> Any?) {
        work.withLock {
            busy = true
            mutableState.value = mutableState.value.copy(busy = true)
            try {
                val result = block()
                if ((result as? TrafficModeApplier.Result)?.fellBack == true) {
                    mutableMessages.tryEmit(R.string.simple_mode_fallback_auto)
                }
            } catch (e: Exception) {
                Logs.w("simple modes: applying failed", e)
                message(R.string.simple_mode_apply_failed)
            } finally {
                busy = false
                applied++
                publish()
            }
        }
    }

    private fun message(@StringRes text: Int) {
        mutableMessages.tryEmit(text)
    }

    private suspend fun publish() {
        val next = withContext(Dispatchers.IO) {
            try {
                val mode = TrafficModes.mode()
                val scope = PerAppScope(DataStore.proxyApps, DataStore.bypass, TrafficModePlan.parseList(DataStore.individual))
                ModesUiState(
                    mode = mode,
                    card = TrafficModePlan.card(mode, DataStore.currentRouteId, TrafficModes.managedIds(), scope),
                    selectedApps = TrafficModes.selectedApps().size,
                    bypassApps = TrafficModes.bypassApps().size,
                    fallback = TrafficModes.fallbackNotice(),
                    busy = false,
                )
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
        } ?: return
        mutableState.value = next.copy(busy = busy, applied = applied)
    }

    private companion object {
        const val KEY_EDITING = "modes_editing"
        const val KEY_CHOOSING = "modes_choosing_apps"
        const val KEY_PRE_PROXY_APPS = "modes_pre_proxy_apps"
        const val KEY_PRE_BYPASS = "modes_pre_bypass"
        const val KEY_PRE_INDIVIDUAL = "modes_pre_individual"
        const val KEY_PRE_STARTS = "modes_pre_service_starts"
    }
}
