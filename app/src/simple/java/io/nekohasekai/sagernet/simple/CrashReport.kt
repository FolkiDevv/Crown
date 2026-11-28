package io.nekohasekai.sagernet.simple

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ui.BlankActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications
import java.io.File

/**
 * The report of a crash without an activity started by the crashed process (design.md D2, touch point 14): Android
 * blocks such starts from the background, and a crashed process must end (CrashHandler passes the crash to Android).
 *
 * The crashing process only writes a marker file ([record]: short, no database, never throws). The report comes later:
 * - a crash of a service process (`:bg`, the restore watchdog `:watch`) — a notification "send the log", posted by the
 *   next `:bg` (the restored service) or the next opening of either interface; a notification of the lost connection
 *   takes its action over ([offerWith]);
 * - a crash of the app's process — a dialog at the next opening of either interface; a crash repeated within
 *   [CrashRules.REPEAT_MS] gets the notification instead, so a report that crashes again cannot loop.
 *
 * The marker is moved aside ([SEEN]) once reported, so only one process reports it and a repeat is still seen.
 */
object CrashReport {

    private const val MARKER = "crash_report"
    private const val SEEN = "crash_report.seen"

    private const val TAG = "crown_crash"
    private const val ID = 1
    private const val REQUEST_SEND = 0x5E59

    @Volatile
    private var marker: File? = null

    @Volatile
    private var seen: File? = null

    @Volatile
    private var process = ""

    /** Application.onCreate, before the handler is set: the files are resolved now, not in the crashing process. */
    fun install(filesDir: File, process: String) {
        marker = File(filesDir, MARKER)
        seen = File(filesDir, SEEN)
        this.process = process
    }

    /** In the crashing process, synchronously: a few hundred bytes to a file; never throws. */
    fun record(throwable: Throwable) {
        try {
            val file = marker ?: return
            val previous = CrashRules.decode(readSmall(file)) ?: CrashRules.decode(readSmall(seen))
            val next = CrashRules.next(previous, process, System.currentTimeMillis(), throwable.javaClass.name)
            val temp = File(file.path + ".tmp")
            temp.writeText(CrashRules.encode(next))
            if (!temp.renameTo(file)) {
                file.writeText(CrashRules.encode(next))
                temp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * A new `:bg` (Application.onCreate, before the service starts): a crash of the service process is reported now, so a
     * notification of the lost connection can take the report over.
     */
    fun onBackgroundProcessStart(context: Context) = guarded {
        val pending = pending() ?: return@guarded
        if (!CrashRules.isApp(pending.process)) claim()?.let { notify(context, it) }
    }

    /**
     * Either interface opens (every onCreate, so a recreated activity asks again): true when it should ask whether to
     * send the log ([answered] ends the question). Any other crash not reported yet goes to a notification.
     */
    fun onInterfaceOpen(context: Context): Boolean = try {
        val pending = pending()
        when {
            pending == null -> false
            CrashRules.prompts(pending) -> true
            else -> {
                claim()?.let { notify(context, it) }
                false
            }
        }
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    /** The interface's question is answered: [send] shares the log (BlankActivity), otherwise the report is dropped. */
    fun answered(context: Context, send: Boolean) = guarded {
        claim()
        if (send) context.startActivity(sendLogIntent(context))
    }

    /**
     * A notification of the lost connection (ServiceRestore) while the crash report is shown, or while a crash of a service
     * process waits for its report (the watchdog reports a loss before any new `:bg` runs): one notification with both
     * actions instead of two.
     */
    fun offerWith(context: Context, builder: NotificationCompat.Builder) = guarded {
        val shown = context.getSystemService<NotificationManager>()?.activeNotifications
            ?.any { it.tag == TAG && it.id == ID } == true
        // A newer crash than the one shown is taken too, or it would post a second report later.
        val claimed = pending()?.takeIf { !CrashRules.isApp(it.process) }?.takeIf { claim() != null }
        if (!shown && claimed == null) return@guarded
        if (shown) NotificationManagerCompat.from(context).cancel(TAG, ID)
        claimed?.let { Logs.w("crash: ${it.process} stopped with ${it.exception}") }
        builder.addAction(0, context.getString(R.string.simple_crash_send), sendLogPending(context))
    }

    private fun notify(context: Context, crash: CrashMarker) {
        Logs.w("crash: ${crash.process} stopped with ${crash.exception}")
        @StringRes val title = if (CrashRules.isBackground(crash.process)) {
            R.string.simple_crash_vpn_title
        } else {
            R.string.simple_crash_app_title
        }
        val text = context.getString(R.string.simple_crash_notification_text)
        // The app's process creates the channels off the main thread: they must exist before the post.
        SagerNet.updateNotificationChannels()
        val notification = NotificationCompat.Builder(context, PlatformNotifications.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(sendLogPending(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, ID, notification)
        } catch (e: SecurityException) {
            Logs.w(e)
        }
    }

    /** BlankActivity shares a redacted log export; opened from a tap, so it is allowed from the background. */
    private fun sendLogIntent(context: Context) = Intent(context, BlankActivity::class.java)
        .putExtra(BlankActivity.EXTRA_SEND_LOG, true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun sendLogPending(context: Context) = PendingIntent.getActivity(
        context, REQUEST_SEND, sendLogIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pending(): CrashMarker? = CrashRules.decode(readSmall(marker))

    /** Takes the pending crash: the first process to move the marker aside reports it. */
    private fun claim(): CrashMarker? {
        val file = marker ?: return null
        val aside = seen ?: return null
        val crash = pending() ?: return null
        return if (file.renameTo(aside)) crash else null
    }

    private fun readSmall(file: File?): String? =
        file?.takeIf { it.isFile && it.length() <= CrashRules.MAX_BYTES }?.readText()

    /** Nothing here may break the start of a process. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}

/** A crash: the process (`<package>[:name]`), the wall time, the exception's class and how often it repeated. */
data class CrashMarker(val process: String, val time: Long, val exception: String, val repeats: Int)

/** The pure rules of [CrashReport], covered by JVM unit tests. */
object CrashRules {

    /** A crash of the app's process this soon after the last one is a repeat: no dialog for it. */
    const val REPEAT_MS = 60_000L

    /** A marker is a few lines; anything bigger is not one. */
    const val MAX_BYTES = 4096L

    private const val VERSION = "1"

    fun isBackground(process: String): Boolean = process.endsWith(":bg")

    /** The app's own process (no `:name`), the one the interfaces run in; the others are service processes. */
    fun isApp(process: String): Boolean = ':' !in process

    /** The marker of a crash of [process] at [now], counting the repeats of a crash of the app's process. */
    fun next(previous: CrashMarker?, process: String, now: Long, exception: String): CrashMarker {
        val repeat = previous != null && isApp(process) && isApp(previous.process) &&
            now >= previous.time && now - previous.time < REPEAT_MS
        return CrashMarker(process, now, exception, if (repeat) (previous?.repeats ?: 0) + 1 else 0)
    }

    /** The interface asks about a crash of its own process; a repeat or a service process's crash gets a notification. */
    fun prompts(crash: CrashMarker): Boolean = isApp(crash.process) && crash.repeats == 0

    fun encode(crash: CrashMarker): String = listOf(
        VERSION, crash.process, crash.time.toString(), crash.exception, crash.repeats.toString(),
    ).joinToString("\n") { it.replace('\n', ' ') }

    fun decode(raw: String?): CrashMarker? {
        val lines = raw?.split('\n') ?: return null
        if (lines.size < 5 || lines[0] != VERSION || lines[1].isBlank()) return null
        val time = lines[2].toLongOrNull() ?: return null
        val repeats = lines[4].trim().toIntOrNull()?.coerceAtLeast(0) ?: return null
        return CrashMarker(lines[1], time, lines[3], repeats)
    }
}
