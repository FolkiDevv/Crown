package io.nekohasekai.sagernet.simple.data

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteFullException
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.PlatformNotifications
import java.io.File
import kotlin.system.exitProcess

/**
 * Thrown by [SagerDatabase.instance] when the database could not be opened in this process (not a corruption, or a
 * corruption no copy could be made of): the file stays as it is, nothing reads or writes a setting.
 */
class DatabaseUnavailableException(cause: Throwable) : RuntimeException("The settings database could not be opened", cause)

/**
 * What the app does while its database does not open (`stop-wiping-database-on-open-error`, touch point 21). The UI
 * process shows [DataProblemActivity]; the `:bg` process refuses to start a service and stops, so it never writes
 * a second, new file next to the one the UI cannot open. Logged with android.util.Log: Logs reads the database.
 */
object DataGate {

    private const val TAG = "DataGate"
    private const val NOTICE_TAG = "data"
    private const val NOTICE_ID = 1

    /** [DataProblemActivity] is up in this process: a crash for the database must not open it again (a loop). */
    @Volatile
    var screenShown = false

    fun databaseFile(): File = SagerNet.application.getDatabasePath(Key.DB_PROFILE)

    private fun markDir(): File = SagerNet.application.noBackupFilesDir

    fun kindOf(error: Throwable): DataFiles.Kind = DataFiles.kindOf(
        error,
        fileVersion = DataFiles.userVersion(databaseFile()),
        appVersion = SagerDatabase.VERSION,
        isCorrupt = { it is SQLiteDatabaseCorruptException },
        isFull = { it is SQLiteFullException },
    )

    private val isMainProcess get() = SagerNet.application.process == BuildConfig.APPLICATION_ID

    // ------------------------------------------------------------------------------------------------ corruption

    /**
     * The open helper of [SagerDatabase]: SQLite reports a damaged file through [SupportSQLiteOpenHelper.Callback.onCorruption],
     * whose default deletes it at once. Here the file and its write-ahead log are copied first; without a whole copy
     * the file is kept, the open fails and the error screen offers to save it.
     */
    class GuardedOpenHelperFactory(
        private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            delegate.create(
                SupportSQLiteOpenHelper.Configuration(
                    configuration.context,
                    configuration.name,
                    Guarded(configuration.callback),
                    configuration.useNoBackupDirectory,
                    configuration.allowDataLossOnRecovery,
                )
            )
    }

    private class Guarded(private val inner: SupportSQLiteOpenHelper.Callback) :
        SupportSQLiteOpenHelper.Callback(inner.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = inner.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = inner.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            inner.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            inner.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = inner.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            val file = db.path?.let(::File)
            if (file == null || !file.isFile) return inner.onCorruption(db)
            val copy = DataFiles.backup(file, System.currentTimeMillis())
            if (copy == null) {
                Log.e(TAG, "Corrupted database kept: no copy of it could be made")
                return
            }
            Log.i(TAG, "Corrupted database backed up as ${copy.name}")
            inner.onCorruption(db)
            if (!file.exists()) DataFiles.markRebuilt(markDir(), copy.name)
        }
    }

    /** The name of the copy of a damaged database rebuilt since the user last saw the notice; "" unknown, null none. */
    fun rebuiltCopy(): String? = DataFiles.rebuilt(markDir(), Key.DB_PROFILE)

    fun clearRebuilt() = DataFiles.clearRebuilt(markDir())

    /** The rebuilt notice came up in this process already (one left with Back comes again in the next process). */
    @Volatile
    private var rebuiltNoticeShown = false

    /**
     * Called by both interfaces whenever they are created, a restore after process death too: says once per process
     * that the data was rebuilt and offers the restore.
     */
    fun showRebuiltNotice(activity: Activity) {
        if (rebuiltNoticeShown || rebuiltCopy() == null) return
        rebuiltNoticeShown = true
        activity.startActivity(DataProblemActivity.rebuiltIntent(activity))
    }

    /** The rebuilt notice is up in this process (also when the system recreated it there after process death). */
    fun rebuiltNoticeUp() {
        rebuiltNoticeShown = true
    }

    /**
     * Called by SagerNet.onCreate in `:bg` when the database did not open: that process skipped its start-up, so it
     * must not stay for later work. A start of a service in it is refused first (refuseStart); then it ends.
     */
    fun endProcessSoon() {
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 5_000)
    }

    // ------------------------------------------------------------------------------------------------ crashes

    /**
     * Called first by CrashHandler. True when [throwable] comes from a database that did not open: the UI process
     * restarts into [DataProblemActivity] (whose process opens nothing), any other process stops without a report.
     */
    fun onUncaught(throwable: Throwable): Boolean {
        if (DataFiles.causes(throwable).none { it is DatabaseUnavailableException }) return false
        Log.e(TAG, "The database could not be opened", throwable)
        val app = SagerNet.application
        if (isMainProcess && !screenShown) {
            ProcessPhoenix.triggerRebirth(app, DataProblemActivity.errorIntent(app))
        } else {
            if (!isMainProcess) runCatching { postNotice(app) }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        return true
    }

    // ------------------------------------------------------------------------------------------------ services

    /**
     * Called first in onStartCommand of the services in `:bg`. True when the database does not open: the start is
     * refused with a notification that says why, and the process ends, so the next start opens the database anew.
     */
    fun refuseStart(service: Service): Boolean {
        if (SagerDatabase.isAvailable()) return false
        Log.e(TAG, "Service start refused: the database could not be opened", SagerDatabase.openFailure)
        try {
            // started with startForegroundService: it must go to the foreground before it may stop
            val notice = notice(service)
            if (Build.VERSION.SDK_INT >= 34) {
                service.startForeground(NOTICE_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                service.startForeground(NOTICE_ID, notice)
            }
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground", e)
            runCatching { postNotice(service) }
        }
        service.stopSelf()
        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
        return true
    }

    private fun notice(context: Context) =
        NotificationCompat.Builder(context, PlatformNotifications.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(R.string.simple_data_notice_title))
            .setContentText(context.getString(R.string.simple_data_notice_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.simple_data_notice_text)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0, DataProblemActivity.errorIntent(context),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun postNotice(context: Context) {
        try {
            SagerNet.updateNotificationChannels()
            NotificationManagerCompat.from(context).notify(NOTICE_TAG, NOTICE_ID, notice(context))
        } catch (_: SecurityException) {
        }
    }

    /** Restarts the UI process into the launcher: the app's start reads the settings again. */
    fun restart(context: Context) {
        ProcessPhoenix.triggerRebirth(
            context,
            Intent(context, io.nekohasekai.sagernet.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }
}
