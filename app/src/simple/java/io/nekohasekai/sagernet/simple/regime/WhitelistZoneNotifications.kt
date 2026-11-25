package io.nekohasekai.sagernet.simple.regime

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * The notification of traffic-modes «Подсказка вне зоны белых списков»: its own channel with sound, two actions, posted
 * (and repeated) by NetworkRegimeWatcher in `:bg`, taken away from either process.
 */
@SuppressLint("MissingPermission")
object WhitelistZoneNotifications {

    const val CHANNEL = "simple-whitelist-zone"
    private const val ID = 0x57_5a

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.simple_wl_zone_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = context.getString(R.string.simple_wl_zone_channel_description) }
            )
        }
    }

    /** Posts the notification again, with its sound: [use] is what of the configuration is for whitelists. */
    fun show(context: Context, use: WhitelistZone.Use) {
        ensureChannel(context)
        val text = context.getString(
            when (use) {
                WhitelistZone.Use.MODE -> R.string.simple_wl_zone_text_mode
                WhitelistZone.Use.SERVER -> R.string.simple_wl_zone_text_server
            }
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let { PendingIntent.getActivity(context, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(context.getString(R.string.simple_wl_zone_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.simple_wl_zone_switch), action(context, WhitelistZoneReceiver.ACTION_LEAVE))
            .addAction(0, context.getString(R.string.simple_wl_zone_keep), action(context, WhitelistZoneReceiver.ACTION_KEEP))
        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build())
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(ID)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun action(context: Context, action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, WhitelistZoneReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The notification's two actions, in the UI process (where the simple UI applies modes and servers). */
class WhitelistZoneReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_LEAVE && action != ACTION_KEEP) return
        val app = context.applicationContext
        val pending = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // A server for whitelists without an «Авто» selector to take over stays, and so does the notification:
                // a tap on it opens the app, where the server list is (Android lets no receiver open it from here).
                if (action == ACTION_KEEP) WhitelistZoneState.keep(app) else WhitelistZoneState.leave(app)
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_LEAVE = "io.nekohasekai.sagernet.simple.WHITELIST_ZONE_LEAVE"
        const val ACTION_KEEP = "io.nekohasekai.sagernet.simple.WHITELIST_ZONE_KEEP"
    }
}
