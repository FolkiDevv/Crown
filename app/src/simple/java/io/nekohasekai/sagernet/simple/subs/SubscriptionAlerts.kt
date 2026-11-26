package io.nekohasekai.sagernet.simple.subs

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleActivity
import io.nekohasekai.sagernet.simple.SimpleUi

/**
 * The notifications of simple-ui "Уведомления о подписке", posted by `:bg` after each update of a subscription: the
 * channel «Подписка» (with sound) for its end, its traffic and the device limit, the channel «Сообщения провайдера»
 * (silent) for a new announcement. Each event once; while the app is on screen its home card says it instead.
 */
@SuppressLint("MissingPermission")
object SubscriptionAlerts {

    const val CHANNEL_SUBSCRIPTION = "simple-subscription"
    const val CHANNEL_NEWS = "simple-provider-news"

    /** Opens the app on the announcement of the subscription with this group id. */
    const val EXTRA_ANNOUNCE = "io.nekohasekai.sagernet.simple.OPEN_ANNOUNCE"

    private const val ID_BASE = 0x53_42_0000
    private val KINDS = SubscriptionNotices.AlertKind.entries.size

    /** The request code of the provider action, apart from the content intent's (extras do not tell them apart). */
    private const val ACTION_REQUEST = 0x0800_0000

    /** A notification's provider button: the page to open ([EXTRA_PROVIDER_URL]) for the subscription with this id. */
    const val EXTRA_PROVIDER_GROUP = "io.nekohasekai.sagernet.simple.PROVIDER_GROUP"
    const val EXTRA_PROVIDER_URL = "io.nekohasekai.sagernet.simple.PROVIDER_URL"

    fun check(context: Context, gid: Long) {
        val group = GroupRepo.get(gid)?.takeIf { it.isSubscription && !it.archive } ?: return
        val notices = SubscriptionNotices.alertable(SubscriptionState.factsOf(group), System.currentTimeMillis() / 1000)
        val sent = SubscriptionState.alerts(gid)
        val (due, now) = SubscriptionNotices.due(notices, sent)
        // On screen, the home card shows it already; it counts as told. Otherwise an event counts as told once its
        // notification is posted: one that failed is tried again at the next update.
        val failed = if (due.isEmpty() || appVisible(context)) emptyList() else due.filterNot { post(context, it) }
        val told = now.toMutableMap()
        for (kind in failed.mapNotNull { SubscriptionNotices.alert(it)?.first }) {
            val before = sent[kind]
            if (before == null) told.remove(kind) else told[kind] = before
        }
        if (told != sent) SubscriptionState.setAlerts(gid, told)
    }

    private fun appVisible(context: Context): Boolean = try {
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any {
            it.processName == context.packageName &&
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SUBSCRIPTION,
                context.getString(R.string.simple_sub_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.simple_sub_channel_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEWS,
                context.getString(R.string.simple_news_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.simple_news_channel_description) }
        )
    }

    /** Whether the notification was posted. */
    private fun post(context: Context, notice: Notice): Boolean {
        ensureChannels(context)
        val facts = notice.facts
        val news = notice.reason == NoticeReason.ANNOUNCE
        val texts = NoticeTexts.of(context, notice)
        val text = listOf(texts.body, texts.quote).filter { it.isNotEmpty() }.joinToString("\n")
        // One notification per subscription and kind: the end and the traffic due together both stay in the shade.
        val kind = SubscriptionNotices.alert(notice)?.first ?: return false
        val id = ID_BASE + (facts.groupId.toInt() and 0x3FFF) * KINDS + kind.ordinal
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.apply { if (news) putExtra(EXTRA_ANNOUNCE, facts.groupId) }
        val open = launch?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = NotificationCompat.Builder(context, if (news) CHANNEL_NEWS else CHANNEL_SUBSCRIPTION)
            .setSmallIcon(if (news) R.drawable.simple_ic_campaign else R.drawable.ic_notification_warning)
            .setContentTitle(texts.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (news) NotificationCompat.CATEGORY_RECOMMENDATION else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (news) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
            // Below Android 8 there is no channel to give the sound: the subscription's own come with it, news stays silent.
            .setDefaults(if (news) 0 else NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (news) {
            // «Подробнее» shows the announcement in the new interface; the classic one has no such sheet, and the
            // notification's own text holds the whole announcement there.
            if (SimpleUi.isSimpleSelected(SimpleUi.store(context))) {
                open?.let { builder.addAction(0, context.getString(R.string.simple_notice_more), it) }
            }
        } else {
            val url = facts.webUrl.ifEmpty { facts.supportUrl }
            if (url.isNotEmpty()) {
                // Through the new interface, which then checks the subscription again when the user comes back (as the
                // home card's button does); the classic one has no such check, so the page opens straight away there.
                val view = if (SimpleUi.isSimpleSelected(SimpleUi.store(context))) {
                    Intent(context, SimpleActivity::class.java)
                        .putExtra(EXTRA_PROVIDER_GROUP, facts.groupId)
                        .putExtra(EXTRA_PROVIDER_URL, url)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } else {
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                // Renewing or topping up for the end and the traffic; the account frees a device of the limit.
                val label = when {
                    facts.webUrl.isEmpty() -> context.getString(R.string.simple_notice_support)
                    notice.reason == NoticeReason.DEVICE_LIMIT -> context.getString(R.string.simple_notice_account)
                    else -> texts.renewLabel
                }
                builder.addAction(
                    0, label,
                    PendingIntent.getActivity(context, id xor ACTION_REQUEST, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                )
            }
        }
        return try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
            true
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }
}
