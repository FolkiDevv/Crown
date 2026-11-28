package io.nekohasekai.sagernet.utils

import android.content.Context
import android.os.Process
import android.util.Log
import androidx.annotation.VisibleForTesting
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.CrashReport
import kotlin.system.exitProcess

/**
 * Logs an uncaught exception, leaves a marker for the report and passes the crash to Android's handler, which records
 * it and ends the process. Crown (touch point 14): the report no longer starts an activity from the crashed process
 * (Android blocks it in the background, and the process lived on with a dead main thread); see [CrashReport].
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    /** Set by BlankActivity while it shares the log: a crash there leaves no marker, or the report would loop. */
    @Volatile
    var reporting = false

    /** Android's handler, saved before this one replaces it. */
    @Volatile
    private var previous: Thread.UncaughtExceptionHandler? = null

    /** Ends the process when Android's handler is missing or returned. */
    @VisibleForTesting
    internal var exit: () -> Unit = {
        Process.killProcess(Process.myPid())
        exitProcess(10)
    }

    /** Application.onCreate, in every process. */
    fun install(context: Context, process: String) {
        CrashReport.install(context.filesDir, process)
        chainTo(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    @VisibleForTesting
    internal fun chainTo(handler: Thread.UncaughtExceptionHandler?) {
        if (handler !== this) previous = handler
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log

        try {
            Log.e(thread.toString(), throwable.stackTraceToString())
        } catch (e: Throwable) {
            // An Error here (out of memory) must not skip the hand-off below: the process would live on.
        }

        // First: the log below may wait on the settings database.
        if (!reporting) CrashReport.record(throwable)

        try {
            Logs.e(thread.toString())
            Logs.e(throwable.stackTraceToString())
        } catch (e: Throwable) {
            // An Error here (out of memory) must not skip the hand-off below: the process would live on.
        }

        try {
            previous?.uncaughtException(thread, throwable)
        } catch (e: Throwable) {
        } finally {
            exit()
        }
    }
}
