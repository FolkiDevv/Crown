package io.nekohasekai.sagernet.simple.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.simple.add.SimpleImport
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
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
     * Settings › Subscriptions does). Switching on uses a day — the interval the simple UI gives a subscription, as
     * the first import does (design.md D3) — unless the user set an interval of their own in the classic settings:
     * a magnitude below the scheduler's minimum or the untouched classic default (30 min) becomes a day.
     */
    fun autoUpdate(subAutoUpdate: Int, on: Boolean): Int {
        if (on && autoUpdateOn(subAutoUpdate)) return subAutoUpdate
        val kept = abs(subAutoUpdate).takeIf { it >= SubscriptionScheduler.MIN_INTERVAL_MINUTES }
        val minutes = when {
            kept == null -> SimpleImport.DAILY_MINUTES
            on && kept == CLASSIC_DEFAULT_MINUTES -> SimpleImport.DAILY_MINUTES
            else -> kept
        }
        return if (on) minutes else -minutes
    }

    /** The magnitude of the classic default `sub_auto_update` (-30: off, 30 minutes). */
    private const val CLASSIC_DEFAULT_MINUTES = 30

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
    /** The once-only hint after an upgrade on the way back to the classic interface. */
    val classicHint: Boolean = false,
)

/**
 * The settings section's own switches. They write the same keys as the classic settings, so both interfaces show the
 * same values; [refresh] reads them again each time the simple interface comes to the front.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SimpleUi.store(application)
    private val mutableState = MutableStateFlow(
        SettingsUiState(
            advanced = SimpleUi.isAdvanced(store),
            classicHint = SimpleUi.showsClassicHint(store, SimpleUi.isUpgrade(application)),
        )
    )
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val autostart = DataStore.rememberEnable
            val autoUpdate = SettingsValues.autoUpdateOn(DataStore.subAutoUpdate)
            val night = NightMode.of(DataStore.nightTheme)
            mutableState.update { it.copy(autostart = autostart, autoUpdate = autoUpdate, nightMode = night) }
        }
    }

    fun setAutostart(on: Boolean) {
        mutableState.update { it.copy(autostart = on) }
        viewModelScope.launch(Dispatchers.IO) {
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
        viewModelScope.launch(Dispatchers.IO) {
            DataStore.subAutoUpdate = SettingsValues.autoUpdate(DataStore.subAutoUpdate, on)
            SubscriptionScheduler.schedule()
        }
    }

    /** Written first, then applied: AppCompat recreates the activity, and the new one reads the stored mode. */
    fun setNightMode(mode: NightMode) {
        if (mode == mutableState.value.nightMode) return
        mutableState.update { it.copy(nightMode = mode) }
        viewModelScope.launch(Dispatchers.IO) {
            DataStore.nightTheme = mode.value
            withContext(Dispatchers.Main) {
                Theme.currentNightMode = mode.value
                Theme.applyNightTheme()
            }
        }
    }

    fun setAdvanced(on: Boolean) {
        SimpleUi.setAdvanced(store, on)
        mutableState.update { it.copy(advanced = on) }
    }

    fun closeClassicHint() {
        SimpleUi.closeClassicHint(store)
        mutableState.update { it.copy(classicHint = false) }
    }
}
