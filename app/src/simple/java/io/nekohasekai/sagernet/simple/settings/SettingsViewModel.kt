package io.nekohasekai.sagernet.simple.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.simple.add.SimpleImport
import io.nekohasekai.sagernet.simple.control.ExternalControl
import io.nekohasekai.sagernet.update.UpdateScheduler
import io.nekohasekai.sagernet.utils.AlwaysOnVpn
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** The classic night mode values (`nightTheme`, Theme.getNightMode) the simple theme choice writes. */
enum class NightMode(val value: Int) {
    SYSTEM(0),
    LIGHT(2),
    DARK(1),

    /** Dark while the battery saver is on; only the classic interface sets it, the simple one shows it when set. */
    BATTERY(3),
    ;

    companion object {
        fun of(value: Int): NightMode = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

/**
 * The plain rules of the simple settings over the classic keys, apart for JVM tests.
 */
object SettingsValues {

    /** Automatic subscription updates are on: the scheduler ignores intervals below its minimum. */
    fun autoUpdateOn(subAutoUpdate: Int): Boolean = subAutoUpdate >= SubscriptionScheduler.MIN_INTERVAL_MINUTES

    /**
     * `sub_auto_update` after the switch: sign-encoded minutes, the magnitude kept while off (as the classic
     * Settings › Subscriptions does), so an interval the user set — 30 minutes too — comes back when switched on.
     * [stored] is null when the setting was never written (the classic default, -30, is then only implied): switching
     * on uses a day, the interval the simple UI gives a subscription, as the first import does (design.md D3). A
     * stored magnitude below the scheduler's minimum also becomes a day.
     */
    fun autoUpdate(stored: Int?, on: Boolean): Int {
        if (stored != null && on && autoUpdateOn(stored)) return stored
        val minutes = stored?.let { abs(it) }?.takeIf { it >= SubscriptionScheduler.MIN_INTERVAL_MINUTES }
            ?: SimpleImport.DAILY_MINUTES
        return if (on) minutes else -minutes
    }

    /** The theme choices: the three plain ones, and the classic battery-saver mode only while it is in effect. */
    fun nightModes(current: NightMode): List<NightMode> =
        listOf(NightMode.SYSTEM, NightMode.LIGHT, NightMode.DARK) + listOfNotNull(current.takeIf { it == NightMode.BATTERY })
}

data class SettingsUiState(
    /** «Подключаться при включении телефона»: the classic auto connect (`remember_enable`). */
    val autostart: Boolean = false,
    /** «Автообновление подписок»: the classic global switch (`sub_auto_update`). */
    val autoUpdate: Boolean = false,
    val nightMode: NightMode = NightMode.SYSTEM,
    val advanced: Boolean = false,
    /** Advanced mode, in-app updater builds: the classic daily update check (`updateCheckAuto`). */
    val updateCheck: Boolean = false,
    /** Advanced mode, in-app updater builds: the classic «Allow updating to beta versions» (`allow_beta_update`). */
    val betaUpdates: Boolean = false,
    /** «Постоянный VPN»: Android's always-on VPN for Crown, with or without blocking connections without VPN. */
    val alwaysOn: AlwaysOnVpn.State = AlwaysOnVpn.State.UNKNOWN,
    /** Advanced mode: «Разрешить управление VPN другим приложениям» ([ExternalControl], off by default). */
    val externalControl: Boolean = false,
)

/**
 * The settings section's own switches. They write the same keys as the classic settings, so both interfaces show the
 * same values; [refresh] reads them again each time the simple interface comes to the front.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SimpleUi.store(application)
    private val mutableState = MutableStateFlow(
        SettingsUiState(advanced = SimpleUi.isAdvanced(store))
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    /**
     * [block] in the process-wide queue, then the stored values published once nothing else waits in it: a read
     * ([refresh]) never overtakes a write asked before it (also one left by the screen a classic visit closed), and
     * what the switches show at the end is what is stored, not a value read before the last write.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun write(block: suspend () -> Unit) {
        writes.trySend {
            block()
            if (writes.isEmpty) publishStored()
        }
    }

    private fun publishStored() {
        val autostart = DataStore.rememberEnable
        val autoUpdate = SettingsValues.autoUpdateOn(DataStore.subAutoUpdate)
        val night = NightMode.of(DataStore.nightTheme)
        val updateCheck = DataStore.updateCheckAuto
        val betaUpdates = DataStore.allowBetaUpdate
        val alwaysOn = AlwaysOnVpn.state()
        val externalControl = ExternalControl.isAllowed()
        mutableState.update {
            it.copy(
                autostart = autostart, autoUpdate = autoUpdate, nightMode = night,
                updateCheck = updateCheck, betaUpdates = betaUpdates, alwaysOn = alwaysOn,
                externalControl = externalControl,
            )
        }
    }

    private companion object {
        /**
         * The settings reads and writes, run one at a time in the order they were asked for: a quick on-off of a
         * switch must end with "off" stored, never with an earlier write landing last. Process-wide, so a write
         * asked just before the screen closes (opening a classic screen closes it) still lands, and the next
         * screen's read comes after it.
         */
        val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED).also { channel ->
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                for (write in channel) {
                    try {
                        write()
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        }
    }

    /** Reads the stored switches again (they may have changed in the classic interface), after any queued write. */
    fun refresh() = write {}

    fun setAutostart(on: Boolean) {
        mutableState.update { it.copy(autostart = on) }
        write {
            DataStore.rememberEnable = on
            // The boot receiver follows the switch at once, as in the classic Settings › General.
            try {
                BootReceiver.enabled = on
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    fun setAutoUpdate(on: Boolean) {
        mutableState.update { it.copy(autoUpdate = on) }
        write {
            val stored = DataStore.configurationStore.getInt(SettingsRegistry.SUB_AUTO_UPDATE.key)
            DataStore.subAutoUpdate = SettingsValues.autoUpdate(stored, on)
            SubscriptionScheduler.schedule()
        }
    }

    /** Written first, then applied: AppCompat recreates the activity, and the new one reads the stored mode. */
    fun setNightMode(mode: NightMode) {
        if (mode == mutableState.value.nightMode) return
        mutableState.update { it.copy(nightMode = mode) }
        write {
            DataStore.nightTheme = mode.value
            withContext(Dispatchers.Main) {
                Theme.applyNightTheme(mode.value)
            }
        }
    }

    /** The daily check is (re)scheduled or cancelled at once, as in the classic Settings › General. */
    fun setUpdateCheck(on: Boolean) {
        mutableState.update { it.copy(updateCheck = on) }
        write {
            DataStore.updateCheckAuto = on
            UpdateScheduler.schedule(enabled = on)
        }
    }

    fun setBetaUpdates(on: Boolean) {
        mutableState.update { it.copy(betaUpdates = on) }
        write { DataStore.allowBetaUpdate = on }
    }

    /** Read by the receiver in `:bg` on every broadcast, so it acts from the next one on. */
    fun setExternalControl(on: Boolean) {
        mutableState.update { it.copy(externalControl = on) }
        write { ExternalControl.setAllowed(on) }
    }

    fun setAdvanced(on: Boolean) {
        SimpleUi.setAdvanced(store, on)
        mutableState.update { it.copy(advanced = on) }
    }
}
