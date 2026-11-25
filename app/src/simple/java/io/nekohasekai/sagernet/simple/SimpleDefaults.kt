package io.nekohasekai.sagernet.simple

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Defaults the simple interface sets for subscription settings it shares with the classic one (design.md D3), once
 * per install, before the first subscription is downloaded. Both are off in the classic defaults:
 * - «Send HWID» (`sub_send_hwid`): many subscription panels serve only a placeholder server ("App not supported") to
 *   clients that do not send the device headers, which a user of the simple UI cannot diagnose;
 * - «Respect the server's update interval» (`sub_respect_server_interval`): a subscription updates as often as its
 *   provider asks (`profile-update-interval`), the daily interval of the simple UI is the fallback.
 *
 * Each is turned on when it was never stored (a fresh install has nothing stored; the install kind of SimpleUi cannot
 * decide it, since builds with the simple UI record "fresh" on their own first run and keep it across upgrades);
 * a value stored before (on or off) is the user's and stays.
 */
object SimpleDefaults {

    /** The defaults were applied on this install (UI process only, design.md D3). */
    const val KEY_APPLIED = "defaults_applied"

    /**
     * The value a setting that the simple UI wants on gets, or null to leave it: [stored] —
     * the stored value, null when the setting was never written.
     */
    fun onByDefault(stored: Boolean?): Boolean? = if (stored == null) true else null

    /** Applies the defaults once; blocking (DataStore is Room), call it off the main thread. */
    @Synchronized
    fun apply(context: Context) {
        val store = SimpleUi.store(context)
        if (store.getBoolean(KEY_APPLIED, false)) return
        try {
            val settings = DataStore.configurationStore
            for (key in listOf(SettingsRegistry.SUB_SEND_HWID.key, SettingsRegistry.SUB_RESPECT_SERVER_INTERVAL.key)) {
                val stored = settings.getBoolean(key)
                onByDefault(stored)?.let {
                    settings.putBoolean(key, it)
                    Logs.i("simple defaults: $key = $it (never stored)")
                }
            }
            store.putBoolean(KEY_APPLIED, true)
        } catch (e: Exception) {
            Logs.w("simple defaults", e)
        }
    }
}
