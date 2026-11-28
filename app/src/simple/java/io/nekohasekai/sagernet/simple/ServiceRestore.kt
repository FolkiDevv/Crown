package io.nekohasekai.sagernet.simple

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications
import kotlinx.coroutines.delay

/**
 * The connection comes back after its service process died (design.md D2, touch point 12): the service is sticky while
 * the user wants it running, and a restart by the system (a null intent) starts the selected profile again, at most
 * [RestoreRules.MAX_RESTORES] times in [RestoreRules.WINDOW_MS]. When it cannot come back the user gets a notification.
 *
 * The mark lives in the shared settings ([KEY_WANTED], device-local): only `:bg` writes it — set by a start the user or
 * an autostart asked for, removed by any stop (the button, notification, tile, widget, shortcuts, a failed start, a
 * revoked VPN). The UI and the package-replaced receiver only read it.
 *
 * Android does not always restart a sticky service: with no other live (non-cached) process of the app it may never do.
 * So while the connection is wanted the service holds a binding to [RestoreWatchdog] in its own small process; the
 * death of `:bg` unbinds it at once, and the watchdog starts the service again ([EXTRA_RESTORE], counted like a restart
 * by the system) or reports the loss when Android refuses the start.
 */
object ServiceRestore {

    /** A start of the service that is a restore ([RestoreWatchdog]): handled like the system's restart (null intent). */
    const val EXTRA_RESTORE = "io.nekohasekai.sagernet.simple.RESTORE"

    /** The service's own start (BaseService.startRunner after a restart in place): it keeps the count of restores. */
    const val EXTRA_INTERNAL = "io.nekohasekai.sagernet.simple.INTERNAL"

    /** [RestoreRules.marker] of the boot in which the connection was started; absent = the user does not want it. */
    const val KEY_WANTED = "simple_service_wanted"

    /** `SystemClock.elapsedRealtime()` of the recent restores, comma separated ([RestoreRules.encode]). */
    const val KEY_RESTORES = "simple_service_restores"

    /** Restores in a row that did not reach Connected: the window alone misses starts further apart than it. */
    const val KEY_UNCONNECTED = "simple_service_unconnected"

    private const val TAG = "crown_restore"
    private const val ID = 1
    private const val REQUEST_OPEN = 0x5E57
    private const val REQUEST_CONNECT = 0x5E58

    /** The periodic check ([RestoreCheckJob]); far from the ids WorkManager hands out from 0. */
    private const val JOB_ID = 0x5E570001
    private const val CHECK_PERIOD_MS = 15 * 60_000L

    /** How long a new `:bg` waits for a restart by the system before it reports the loss. */
    private const val LOST_CHECK_MS = 15_000L

    /** This process is starting a profile for a restart by the system (and not since connected or stopped). */
    @Volatile
    private var restoring = false

    /** Why the last restore of this process gave up, for the Stopped state the UI shows; taken once. */
    @Volatile
    private var reason: String? = null

    /** The watchdog's binding of this process (main thread only). */
    private var watching: ServiceConnection? = null

    private val store get() = DataStore.configurationStore

    /** A start by the system after the process died (null intent) or by the watchdog. */
    fun isRestart(intent: Intent?): Boolean = intent == null || intent.getBooleanExtra(EXTRA_RESTORE, false)

    /** Why the restore just refused gave up (null when it was not wanted): the message of the Stopped that follows. */
    fun takeReason(): String? = reason.also { reason = null }

    /** Whether the user wants the connection running now (read by both processes). */
    fun isWanted(): Boolean = try {
        RestoreRules.isWanted(store.getLong(KEY_WANTED) ?: 0L, bootMarker())
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    /** onStartCommand's result: sticky while the connection is wanted. */
    fun startResult(): Int = if (isWanted()) Service.START_STICKY else Service.START_NOT_STICKY

    /** A start the user or an autostart asked for ([intent]), unless a stop is still in progress. */
    fun onStartRequested(context: Context, intent: Intent?) = guarded {
        restoring = false
        reason = null
        // A start by the user (or an autostart) begins a new count of restores; the service's own start keeps it.
        if (intent?.getBooleanExtra(EXTRA_INTERNAL, false) != true || !isWanted()) {
            store.remove(KEY_RESTORES)
            store.remove(KEY_UNCONNECTED)
        }
        store.putLong(KEY_WANTED, bootMarker())
        cancel(context)
        watch(context)
        scheduleCheck(context)
    }

    /** Any stop but an in-place restart: nothing restores the connection after it (the mark goes before the watch). */
    fun onStopRequested() = guarded {
        restoring = false
        reason = null // the message of this stop is taken already
        if (store.contains(KEY_WANTED)) store.remove(KEY_WANTED)
        unwatch()
        cancelCheck()
    }

    /**
     * The system restarted the sticky service (null intent) after its process died: true when the selected profile
     * starts again. Otherwise the caller stops the service; the user is told why when the connection was wanted.
     */
    fun onSystemRestart(service: Service): Boolean = try {
        when {
            !isWanted() -> {
                Logs.i("restore: the service restarted, the connection is not wanted")
                false
            }

            else -> {
                val now = SystemClock.elapsedRealtime()
                val unconnected = store.getLong(KEY_UNCONNECTED) ?: 0L
                val admitted = RestoreRules.admit(RestoreRules.decode(store.getString(KEY_RESTORES)), now, unconnected)
                if (admitted == null) {
                    Logs.w("restore: ${RestoreRules.MAX_RESTORES} restores within the window, giving up")
                    giveUp(service, R.string.simple_restore_gave_up_title, R.string.simple_restore_gave_up_text)
                    false
                } else {
                    // Stored before anything else runs: a start that kills the process again must count.
                    store.putString(KEY_RESTORES, RestoreRules.encode(admitted))
                    store.putLong(KEY_UNCONNECTED, unconnected + 1)
                    if (enterForeground(service)) {
                        Logs.i("restore: starting the selected profile again (restore ${admitted.size})")
                        restoring = true
                        // Before the core starts: a start that kills the process again is seen by the watchdog too.
                        watch(service)
                        scheduleCheck(service)
                        true
                    } else {
                        giveUp(service, R.string.simple_restore_lost_title, R.string.simple_restore_lost_text)
                        false
                    }
                }
            }
        }
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    /** A restart by the system found no VPN consent (revoked, or another VPN app took it): no activity may start. */
    fun onConsentMissing(context: Context) = guarded {
        if (!isWanted()) return@guarded
        Logs.w("restore: no VPN consent")
        giveUp(context, R.string.simple_restore_consent_title, R.string.simple_restore_consent_text)
    }

    /**
     * A restore that does not start: the service leaves the foreground, so neither the placeholder nor the notification
     * the dead process left reads as connected. A start by [startForegroundService] must go foreground first.
     */
    fun leaveForeground(service: Service) {
        enterForeground(service)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") service.stopForeground(true)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /**
     * RestoreWatchdog.onUnbind (process `:watch`): the service that held the binding is gone. While the connection is
     * wanted that is a death of `:bg` (every stop removes the mark first), so the service starts again now, without
     * waiting for the system. VPN apps may start a foreground service from the background (the VPN consent exempts
     * them); when Android refuses, the user is told and the service record of the dead process is stopped.
     */
    fun onWatchedServiceGone(context: Context) = guarded {
        if (isWanted()) restoreNow(context, "the service process is gone")
    }

    /**
     * [RestoreCheckJob] (in `:bg`, every 15 min while the connection is wanted): the fallback when the watchdog died with
     * `:bg` (the whole app killed at once) and Android restarts neither. A `:bg` started for the job runs no service.
     */
    fun onCheck(context: Context) = guarded {
        if (!isWanted()) {
            cancelCheck()
            return@guarded
        }
        val state = DataStore.serviceState
        if (state.started || state == BaseService.State.Stopping) return@guarded
        restoreNow(context, "the periodic check found no service")
    }

    /** Starts the service as a restore ([EXTRA_RESTORE]) now, or reports the loss when Android refuses. */
    private fun restoreNow(context: Context, why: String) {
        val service = SagerConnection.serviceClass
        note("restore: $why, starting the service again")
        try {
            ContextCompat.startForegroundService(context, Intent(context, service).putExtra(EXTRA_RESTORE, true))
        } catch (e: Exception) {
            note("restore: start refused: $e")
            try {
                context.stopService(Intent(context, service))
            } catch (e: Exception) {
                Logs.w(e)
            }
            giveUp(context, R.string.simple_restore_lost_title, R.string.simple_restore_lost_text)
        }
    }

    /** BaseService.failRunner: a restore that failed to start reports [message] in a notification too. */
    fun onStartFailed(context: Context, message: String) = guarded {
        if (!restoring) return@guarded
        restoring = false
        post(context, R.string.simple_restore_failed_title, message)
    }

    /** SimpleHooks.onServiceStarted: the connection is up, a notification of its loss is over. */
    fun onConnected(context: Context) = guarded {
        restoring = false
        if (store.contains(KEY_UNCONNECTED)) store.remove(KEY_UNCONNECTED)
        cancel(context)
    }

    /**
     * A new `:bg` process: a wanted connection is not running here. The system restarts a sticky service right away,
     * so the loss is reported only when no start came meanwhile (force stop, a restart refused or still far off); the
     * report ends the restore ([giveUp]), so a later `:bg` does not report it again.
     */
    fun onBackgroundProcessStart(context: Context) {
        // On the main thread, like every start and stop: a start that lands at the deadline is seen, not undone.
        runOnMainDispatcher {
            delay(LOST_CHECK_MS)
            guarded {
                val state = DataStore.serviceState
                if (isWanted() && !state.started && state != BaseService.State.Stopping) {
                    Logs.w("restore: the connection is wanted but no service runs")
                    giveUp(context, R.string.simple_restore_lost_title, R.string.simple_restore_lost_text)
                }
            }
        }
    }

    /**
     * Android 12+ may refuse a foreground start in the background (proxy mode has no VPN exemption): tried before the
     * core starts, with the service's own notification id, which ServiceNotification replaces once it shows.
     */
    private fun enterForeground(service: Service): Boolean {
        val channel = if (service is VpnService) "service-vpn" else "service-proxy"
        val notification = NotificationCompat.Builder(service, channel)
            .setSmallIcon(R.drawable.ic_throne_tile)
            .setContentTitle(service.getString(R.string.app_name))
            .setContentText(service.getString(R.string.connecting))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(
                    ServiceNotification.notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                )
            } else {
                service.startForeground(ServiceNotification.notificationId, notification)
            }
            true
        } catch (e: Exception) {
            Logs.w("restore: foreground start refused: $e")
            false
        }
    }

    /**
     * The connection is not coming back: the mark goes, so neither a later `:bg` nor an update reports or restores it
     * again, and the reason stays as the failed start's (both interfaces show it until dismissed or the next start).
     */
    private fun giveUp(context: Context, @StringRes title: Int, @StringRes text: Int) {
        val message = context.getString(text)
        reason = message
        store.remove(KEY_WANTED)
        cancelCheck()
        DataStore.serviceError = message
        DataStore.serviceErrorDns = false
        DataStore.serviceErrorGeo = false
        DataStore.serviceErrorRuleSets = false
        post(context, title, message)
    }

    private fun post(context: Context, @StringRes title: Int, text: String) {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { PendingIntent.getActivity(context, REQUEST_OPEN, it, flags) }
        // The consent screen when the VPN needs it, then the start: from a tap, so the start is in the foreground.
        val connect = PendingIntent.getActivity(
            context, REQUEST_CONNECT,
            Intent(context, VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags,
        )
        val builder = NotificationCompat.Builder(context, PlatformNotifications.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.simple_restore_connect), connect)
            .setAutoCancel(true)
        // The loss came from a crash still offered for a report: one notification instead of two.
        CrashReport.offerWith(context, builder)
        val notification = builder.build()
        try {
            NotificationManagerCompat.from(context).notify(TAG, ID, notification)
        } catch (e: SecurityException) {
            Logs.w(e)
        }
    }

    /**
     * Binds [RestoreWatchdog] (its process lives while the binding does) from the application of this process: one
     * binding whatever service instance asks, never dropped by a service destroyed without a stop, and never let go to
     * be bound again (that unbind would look like a death to the watchdog).
     */
    private fun watch(context: Context) {
        if (watching != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {}
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val app = context.applicationContext
        try {
            if (app.bindService(Intent(app, RestoreWatchdog::class.java), connection, Context.BIND_AUTO_CREATE)) {
                watching = connection
            } else {
                Logs.w("restore: the watchdog could not be bound")
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /** Arms [RestoreCheckJob] unless it is armed already (its period then keeps running). */
    private fun scheduleCheck(context: Context) {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RestoreCheckJob::class.java))
                .setPeriodic(CHECK_PERIOD_MS)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) Logs.w("restore: the check was not scheduled")
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun cancelCheck() {
        try {
            app.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /** Whether [text] is a reason [giveUp] stored: the home banner shows it instead of its plain text. */
    fun isReason(context: Context, text: String?): Boolean = text != null && text in listOf(
        R.string.simple_restore_lost_text, R.string.simple_restore_gave_up_text, R.string.simple_restore_consent_text,
    ).map { context.getString(it) }

    /** Also to logcat: `:watch` has no core log of its own. */
    private fun note(message: String) {
        Logs.w(message)
        Log.w("ServiceRestore", message)
    }

    private fun unwatch() {
        val connection = watching ?: return
        watching = null
        try {
            app.unbindService(connection)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(TAG, ID)
    }

    private fun bootMarker(): Long = RestoreRules.marker(
        try {
            Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            null
        }
    )

    /** The service must never fail because of the restore. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}

/** The pure rules of [ServiceRestore], covered by JVM unit tests. */
object RestoreRules {

    const val MAX_RESTORES = 3
    const val WINDOW_MS = 10 * 60_000L

    /** A [marker] when the boot count is unknown: valid in any boot. */
    const val ANY_BOOT = -1L

    /** What [ServiceRestore.KEY_WANTED] stores for the boot [bootCount] (never 0, which means "not wanted"). */
    fun marker(bootCount: Int?): Long = if (bootCount != null && bootCount >= 0) bootCount + 1L else ANY_BOOT

    /** A mark from an earlier boot is stale: the connection was not lost, the device restarted. */
    fun isWanted(stored: Long, current: Long): Boolean =
        stored != 0L && (stored == ANY_BOOT || current == ANY_BOOT || stored == current)

    /**
     * The restores of the window with this one ([now]) added, or null when [MAX_RESTORES] were made in it already or
     * [unconnected] restores in a row never reached Connected (starts further apart than the window, as the periodic
     * check makes them). A time later than [now] is from an earlier boot and does not count.
     */
    fun admit(history: List<Long>, now: Long, unconnected: Long = 0L): List<Long>? {
        val recent = history.filter { it <= now && now - it < WINDOW_MS }
        return if (recent.size >= MAX_RESTORES || unconnected >= MAX_RESTORES) null else recent + now
    }

    fun decode(raw: String?): List<Long> =
        raw?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    fun encode(times: List<Long>): String = times.joinToString(",")
}

/**
 * The watchdog of [ServiceRestore], alone in the process `:watch` (it loads nothing else): the VPN service binds it
 * while the connection is wanted, which also keeps this process alive as long as `:bg` is. When `:bg` dies, Android
 * unbinds it at once, whatever the state of the UI process.
 */
class RestoreWatchdog : Service() {

    private val binder = Binder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onUnbind(intent: Intent): Boolean {
        ServiceRestore.onWatchedServiceGone(applicationContext)
        return false
    }
}

/**
 * The periodic check of [ServiceRestore] (`:bg`, every 15 min while the connection is wanted): it survives the death of
 * every process of the app, which the watchdog does not.
 */
class RestoreCheckJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        ServiceRestore.onCheck(applicationContext)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
