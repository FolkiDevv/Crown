package io.nekohasekai.sagernet.simple

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import io.nekohasekai.sagernet.simple.settings.ClassicVisit
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications

/**
 * Which interface opens: the simple one (default, new and upgraded installs alike) or the classic [MainActivity].
 *
 * The choice lives in its own SharedPreferences ([PREFS]), not in DataStore: only the UI process reads it, and it
 * stays clear of upstream keys and the Room schema (design.md D3). [MainActivity] stays the entry point of every
 * launcher, notification, tile and shortcut and hands over to [SimpleActivity] with [redirect].
 *
 * The decisions are plain functions over [Store] so that JVM unit tests cover them without Android.
 */
object SimpleUi {

    const val PREFS = "simple_ui"
    const val KEY_SIMPLE = "simple_ui_selected"
    const val KEY_INSTALL_KIND = "install_kind"
    const val INSTALL_FRESH = "fresh"
    const val INSTALL_UPGRADE = "upgrade"

    /** The one hint after an upgrade, on the way back to the classic interface, was closed (simple-ui spec). */
    const val KEY_CLASSIC_HINT_CLOSED = "classic_hint_closed"

    /** «Расширенный режим» of the simple settings: links to the classic screens. */
    const val KEY_ADVANCED = "advanced_mode"

    /** An intent extra that keeps [MainActivity] open whatever is chosen (screens of the advanced mode). */
    const val EXTRA_OPEN_CLASSIC = "io.nekohasekai.sagernet.simple.OPEN_CLASSIC"

    /**
     * Intents only the classic interface handles: the tile's long press and the Wi-Fi permission flow. Links, files,
     * shares and the QR scanner's texts ([MainActivity.EXTRA_IMPORT_TEXTS]) go to the simple UI's add flow since
     * batch 4; a backup it recognises comes back here as a VIEW carrying [EXTRA_OPEN_CLASSIC].
     */
    private val CLASSIC_ACTIONS = setOf(
        TileService.ACTION_QS_TILE_PREFERENCES,
    )
    private val CLASSIC_EXTRAS = setOf(
        EXTRA_OPEN_CLASSIC,
        PlatformNotifications.EXTRA_WIFI_PERMISSION,
    )

    /** The key-value storage of the choice; [SharedPreferences] on the device, a map in tests. */
    interface Store {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
        fun getString(key: String): String?
        fun putString(key: String, value: String)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    }

    fun store(context: Context): Store =
        PrefsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** The simple interface unless the user picked the classic one. */
    fun isSimpleSelected(store: Store): Boolean = store.getBoolean(KEY_SIMPLE, true)

    fun select(store: Store, simple: Boolean) = store.putBoolean(KEY_SIMPLE, simple)

    /**
     * Whether this install came as an upgrade of a version without the simple UI. Decided on the first run of this
     * version and kept, since every later update moves lastUpdateTime past firstInstallTime. A fresh install has
     * both times equal (also when Android restored sager_net.db from a cloud backup).
     */
    fun isUpgrade(store: Store, firstInstallTime: Long, lastUpdateTime: Long): Boolean {
        store.getString(KEY_INSTALL_KIND)?.let { return it == INSTALL_UPGRADE }
        val upgrade = lastUpdateTime > firstInstallTime
        store.putString(KEY_INSTALL_KIND, if (upgrade) INSTALL_UPGRADE else INSTALL_FRESH)
        return upgrade
    }

    /**
     * The hint on the way back to the classic interface shows after an upgrade from a version without the simple UI
     * ([isUpgrade]), never on a fresh install, until it is closed once.
     */
    fun showsClassicHint(store: Store, upgrade: Boolean): Boolean =
        upgrade && !store.getBoolean(KEY_CLASSIC_HINT_CLOSED, false)

    fun closeClassicHint(store: Store) = store.putBoolean(KEY_CLASSIC_HINT_CLOSED, true)

    fun isAdvanced(store: Store): Boolean = store.getBoolean(KEY_ADVANCED, false)

    fun setAdvanced(store: Store, on: Boolean) = store.putBoolean(KEY_ADVANCED, on)

    /** Whether an intent must stay in the classic interface (see [CLASSIC_ACTIONS], [CLASSIC_EXTRAS]). */
    fun opensClassic(action: String?, extraKeys: Set<String>): Boolean =
        action in CLASSIC_ACTIONS || extraKeys.any { it in CLASSIC_EXTRAS }

    fun shouldRedirect(store: Store, action: String?, extraKeys: Set<String>): Boolean =
        isSimpleSelected(store) && !opensClassic(action, extraKeys)

    /**
     * Called by [MainActivity.onCreate] before its content is set, and by [MainActivity.onNewIntent] for a reused
     * singleTask instance (one kept open by a classic-only intent): opens [SimpleActivity] instead when the simple
     * interface is chosen and finishes [activity]. Returns true when it did, and the caller returns right away.
     */
    fun redirect(activity: Activity, intent: Intent? = activity.intent): Boolean {
        val store = store(activity)
        recordInstallKind(activity, store)
        val extras = intent?.extras?.keySet().orEmpty()
        if (!shouldRedirect(store, intent?.action, extras)) {
            // A classic screen opened from the simple settings: show it, and back returns to the simple interface.
            ClassicVisit.attach(activity, intent)
            return false
        }
        activity.startActivity(simpleIntent(activity, intent))
        activity.finish()
        noTransition(activity)
        return true
    }

    /** Switches the interface, remembers the choice and closes the current one. */
    fun switchTo(activity: Activity, simple: Boolean) {
        select(store(activity), simple)
        val target = if (simple) {
            simpleIntent(activity, null)
        } else {
            Intent(activity, MainActivity::class.java).putExtra(EXTRA_OPEN_CLASSIC, true)
        }
        activity.startActivity(target)
        activity.finish()
        noTransition(activity)
    }

    fun isUpgrade(context: Context): Boolean = recordInstallKind(context, store(context))

    private fun recordInstallKind(context: Context, store: Store): Boolean {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        }
        return isUpgrade(store, info.firstInstallTime, info.lastUpdateTime)
    }

    private fun simpleIntent(context: Context, source: Intent?): Intent {
        val intent = Intent(context, SimpleActivity::class.java)
        if (source != null) {
            intent.action = source.action
            intent.setDataAndType(source.data, source.type)
            source.extras?.let { intent.putExtras(it) }
            // A shared or opened file: MainActivity's read grant goes on with the Uri (SEND keeps it in clipData).
            intent.clipData = source.clipData
            intent.addFlags(source.flags and URI_GRANTS)
        }
        return intent
    }

    private const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    @Suppress("DEPRECATION")
    private fun noTransition(activity: Activity) = activity.overridePendingTransition(0, 0)
}
