package io.nekohasekai.sagernet.simple

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.settings.ClassicVisit
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications
import java.util.UUID

/**
 * Which interface opens: always the simple one, except for an intent that asks for the classic [MainActivity]
 * ([EXTRA_OPEN_CLASSIC] and the classic-only intents). The switch to the classic interface is not remembered: it is
 * meant only for a user support guides there, so the next launch from the launcher, a notification or a tile opens
 * the simple interface again. A choice older versions stored (`simple_ui_selected`) is ignored.
 *
 * Its own SharedPreferences ([PREFS]), not DataStore, hold the advanced mode: only the UI process reads it, and it
 * stays clear of upstream keys and the Room schema (design.md D3). [MainActivity] stays the entry point of every
 * launcher, notification, tile and shortcut and hands over to [SimpleActivity] with [redirect].
 *
 * The decisions are plain functions so that JVM unit tests cover them without Android.
 */
object SimpleUi {

    const val PREFS = "simple_ui"

    /** «Расширенный режим» of the simple settings: links to the classic screens. */
    const val KEY_ADVANCED = "advanced_mode"

    /** An intent extra that keeps [MainActivity] open (the classic interface and screens of the advanced mode). */
    const val EXTRA_OPEN_CLASSIC = "io.nekohasekai.sagernet.simple.OPEN_CLASSIC"

    /**
     * Intents only the classic interface handles: the switch from the advanced mode and the Wi-Fi permission flow.
     * The tile's long press opens the simple interface, like a launch from the launcher. Links, files, shares and the
     * QR scanner's texts ([MainActivity.EXTRA_IMPORT_TEXTS]) go to the simple UI's add flow since batch 4; a backup or
     * a routing link it recognises comes back here carrying [EXTRA_OPEN_CLASSIC] and [markOwn].
     */
    private val CLASSIC_EXTRAS = setOf(
        EXTRA_OPEN_CLASSIC,
        PlatformNotifications.EXTRA_WIFI_PERMISSION,
    )

    /**
     * A routing profile link the simple UI hands to the classic one ([markOwn]): only the route import takes it, with
     * its own question, never the import of servers (the link may have come from another app).
     */
    const val EXTRA_ROUTE_LINK = "io.nekohasekai.sagernet.simple.ROUTE_LINK"

    /**
     * A file another app sent without the right to read it ([redirect]): the simple UI says it cannot be read. The
     * value is the file's name.
     */
    const val EXTRA_UNREADABLE_FILE = "io.nekohasekai.sagernet.simple.UNREADABLE_FILE"

    /** Extras that carry something to import; with the intent's data they make it an import. */
    private val IMPORT_EXTRAS = setOf(
        Intent.EXTRA_TEXT,
        Intent.EXTRA_STREAM,
        MainActivity.EXTRA_IMPORT_TEXTS,
        EXTRA_ROUTE_LINK,
    )

    /**
     * MainActivity is exported: another app can send it any action and extras. Intents the app sends to itself
     * carry this process's secret ([markOwn]); another app cannot read it, and a new process makes a new one.
     */
    private const val EXTRA_OWN = "io.nekohasekai.sagernet.simple.OWN"
    private val ownSecret = UUID.randomUUID().toString()

    /** Marks [intent] as sent by the app itself (the QR scanner of a shortcut, the simple UI's hand-overs). */
    fun markOwn(intent: Intent): Intent = intent.putExtra(EXTRA_OWN, ownSecret)

    /** Whether [intent] was sent by the app itself in this process ([markOwn]). */
    fun isOwn(intent: Intent?): Boolean = try {
        intent?.getStringExtra(EXTRA_OWN) == ownSecret
    } catch (_: RuntimeException) {
        // A crafted extras bundle that does not unparcel.
        false
    }

    /** The key-value storage of the advanced mode; [SharedPreferences] on the device, a map in tests. */
    interface Store {
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    fun store(context: Context): Store =
        PrefsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun isAdvanced(store: Store): Boolean = store.getBoolean(KEY_ADVANCED, false)

    fun setAdvanced(store: Store, on: Boolean) = store.putBoolean(KEY_ADVANCED, on)

    /**
     * Whether an intent must stay in the classic interface (see [CLASSIC_EXTRAS]). One that carries
     * something to import ([hasData], [IMPORT_EXTRAS]) stays only when the app sent it itself ([own]): another app
     * must not pick the interface that imports its data, the classic one asks less.
     */
    fun opensClassic(extraKeys: Set<String>, hasData: Boolean = false, own: Boolean = false): Boolean =
        extraKeys.any { it in CLASSIC_EXTRAS } && (own || !hasData && extraKeys.none { it in IMPORT_EXTRAS })

    /** Every intent but a classic one goes to the simple interface, whatever was opened before. */
    fun shouldRedirect(extraKeys: Set<String>, hasData: Boolean = false, own: Boolean = false): Boolean =
        !opensClassic(extraKeys, hasData, own)

    /**
     * Called by [MainActivity.onCreate] before its content is set, and by [MainActivity.onNewIntent] for a reused
     * singleTask instance (one kept open by a classic intent): opens [SimpleActivity] instead unless [intent] asks
     * for the classic interface, and finishes [activity]. Returns true when it did, and the caller returns right away.
     * A [restored] activity passed this check when it was created (its intent is not imported again).
     */
    fun redirect(activity: Activity, intent: Intent? = activity.intent, restored: Boolean = false): Boolean {
        val extras = intent?.extras?.keySet().orEmpty()
        if (!shouldRedirect(extras, intent?.data != null, restored || isOwn(intent))) {
            // A classic screen opened from the simple settings: show it, and back returns to the simple interface.
            ClassicVisit.attach(activity, intent)
            return false
        }
        try {
            activity.startActivity(simpleIntent(activity, intent))
        } catch (e: SecurityException) {
            // Another app sent a file without the right to read it: the hand-over cannot pass the read grant on. Nothing
            // is imported, and the simple UI says the file cannot be read, as the classic import does.
            Logs.w(e)
            activity.startActivity(
                markOwn(Intent(activity, SimpleActivity::class.java).putExtra(EXTRA_UNREADABLE_FILE, fileName(intent)))
            )
        }
        activity.finish()
        noTransition(activity)
        return true
    }

    /** Switches the interface and closes the current one. Nothing is stored: the next launch opens the simple one. */
    fun switchTo(activity: Activity, simple: Boolean) {
        val target = if (simple) {
            simpleIntent(activity, null)
        } else {
            Intent(activity, MainActivity::class.java).putExtra(EXTRA_OPEN_CLASSIC, true)
        }
        activity.startActivity(target)
        activity.finish()
        noTransition(activity)
    }

    private fun simpleIntent(context: Context, source: Intent?): Intent {
        val intent = Intent(context, SimpleActivity::class.java)
        // Reopened from Recents, the task's first intent comes again: it only opens the interface (nothing imported twice).
        if (source != null && source.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0) {
            intent.action = source.action
            intent.setDataAndType(source.data, source.type)
            source.extras?.let { intent.putExtras(it) }
            // A shared or opened file: MainActivity's read grant goes on with the Uri (SEND keeps it in clipData).
            intent.clipData = source.clipData
            intent.addFlags(source.flags and URI_GRANTS)
        }
        return intent
    }

    /** The name of the file an intent names (its data or stream), without reading it. */
    @Suppress("DEPRECATION")
    private fun fileName(intent: Intent?): String {
        val uri = intent?.data ?: runCatching { intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }.getOrNull()
        return uri?.let { it.lastPathSegment ?: it.toString() }.orEmpty()
    }

    private const val URI_GRANTS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    @Suppress("DEPRECATION")
    private fun noTransition(activity: Activity) = activity.overridePendingTransition(0, 0)
}
