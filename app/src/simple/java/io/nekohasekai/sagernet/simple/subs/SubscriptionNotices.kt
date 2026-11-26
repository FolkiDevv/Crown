package io.nekohasekai.sagernet.simple.subs

import io.nekohasekai.sagernet.simple.servers.SubscriptionHealth
import java.time.ZoneId

/**
 * What the last fetch of a subscription said beyond its servers (SimpleHooks, DataStore `simple_sub_mark_<gid>`):
 * [LIMIT] the panel's device limit headers, [EMPTY] an answer without a single server but with an announcement.
 * Both stay until a fetch brings servers again.
 */
enum class FetchMark(val key: String) {
    NONE(""),
    EMPTY("empty"),
    LIMIT("limit");

    companion object {
        fun of(key: String?): FetchMark = entries.firstOrNull { it.key == key && it != NONE } ?: NONE
    }
}

/** One subscription as its messages need it. */
data class SubscriptionFacts(
    val groupId: Long,
    val name: String,
    /** Epoch seconds, 0 = not reported. */
    val expire: Long,
    val hasQuota: Boolean,
    val total: Long,
    val used: Long,
    val announce: String,
    val webUrl: String,
    val supportUrl: String,
    /** The servers that are no [Placeholders]. */
    val usable: Int,
    /** The names of the placeholders, in the subscription's order. */
    val placeholders: List<String>,
    val mark: FetchMark,
)

/** How a message stands out: red, amber, or the provider's announcement. */
enum class NoticeLevel { BLOCKED, WARNING, NEWS }

enum class NoticeReason {
    /** The subscription's end has passed. */
    EXPIRED,

    /** No traffic is left. */
    EXHAUSTED,

    /** The panel refused for its device limit. */
    DEVICE_LIMIT,

    /** The panel sent an announcement instead of servers. */
    REFUSED,

    /** Nothing but placeholders came. */
    PLACEHOLDERS,

    /** The end is at most [SubscriptionHealth.EXPIRY_WARNING_DAYS] days away. */
    EXPIRING,

    /** Little traffic is left ([SubscriptionHealth.Traffic.LOW]). */
    LOW_TRAFFIC,

    /** The provider's announcement. */
    ANNOUNCE,
}

/**
 * One message of a subscription. [level] BLOCKED: the VPN cannot work through this subscription; WARNING: it still
 * works, but the user has something to do. [message] is what the provider said in its own words (the placeholders'
 * names, or the announcement), "" when nothing.
 */
data class Notice(
    val facts: SubscriptionFacts,
    val reason: NoticeReason,
    val level: NoticeLevel,
    val message: String = "",
    /** [EXPIRING]: calendar days to the end, 0 = today. */
    val daysLeft: Int = 0,
) {
    val groupId: Long get() = facts.groupId

    /** The state «Позже» hides: the next threshold (the last day, the day itself) is another one. */
    val token: String
        get() = when (reason) {
            NoticeReason.EXPIRING -> "expiring:" + when {
                daysLeft <= 0 -> 0
                daysLeft == 1 -> 1
                else -> SubscriptionHealth.EXPIRY_WARNING_DAYS
            }

            NoticeReason.REFUSED, NoticeReason.PLACEHOLDERS, NoticeReason.ANNOUNCE ->
                reason.name.lowercase() + ":" + SubscriptionNotices.hash(message)

            else -> reason.name.lowercase()
        }
}

/** The messages of the subscriptions (simple-ui "Сообщения подписки"); JVM unit tests cover it. */
object SubscriptionNotices {

    /**
     * The messages of one subscription: at most one red or amber, the most important first, then the announcement
     * (unless the amber or red one quotes it already).
     */
    fun of(facts: SubscriptionFacts, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): List<Notice> {
        val expiry = SubscriptionHealth.expiry(facts.expire, nowSeconds)
        val traffic = SubscriptionHealth.traffic(facts.hasQuota, facts.total, facts.used)
        val announce = facts.announce.trim()
        val placeholders = facts.placeholders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        // What the provider said about it: its placeholders; for the device limit also its announcement, which then
        // most likely explains the refusal (an end or used traffic is no news an announcement would be about).
        val said = placeholders.ifEmpty { announce }
        val noServers = facts.usable == 0
        val main = when {
            expiry == SubscriptionHealth.Expiry.EXPIRED ->
                Notice(facts, NoticeReason.EXPIRED, NoticeLevel.BLOCKED, placeholders)

            traffic == SubscriptionHealth.Traffic.EXHAUSTED ->
                Notice(facts, NoticeReason.EXHAUSTED, NoticeLevel.BLOCKED, placeholders)

            facts.mark == FetchMark.LIMIT ->
                Notice(facts, NoticeReason.DEVICE_LIMIT, if (noServers) NoticeLevel.BLOCKED else NoticeLevel.WARNING, said)

            noServers && placeholders.isNotEmpty() ->
                Notice(facts, NoticeReason.PLACEHOLDERS, NoticeLevel.BLOCKED, placeholders)

            facts.mark == FetchMark.EMPTY && announce.isNotEmpty() ->
                Notice(facts, NoticeReason.REFUSED, if (noServers) NoticeLevel.BLOCKED else NoticeLevel.WARNING, announce)

            expiry == SubscriptionHealth.Expiry.SOON -> Notice(
                facts, NoticeReason.EXPIRING, NoticeLevel.WARNING,
                daysLeft = SubscriptionHealth.daysLeft(facts.expire, nowSeconds, zone),
            )

            traffic == SubscriptionHealth.Traffic.LOW -> Notice(facts, NoticeReason.LOW_TRAFFIC, NoticeLevel.WARNING)
            else -> null
        }
        val news = Notice(facts, NoticeReason.ANNOUNCE, NoticeLevel.NEWS, announce)
            .takeIf { announce.isNotEmpty() && main?.message != announce }
        return listOfNotNull(main, news)
    }

    /**
     * The one message the home screen shows (simple-ui "Одно место для сообщений"): a red one first, then an amber one
     * the user did not put off ([later]: group id → the token «Позже» hid), then an announcement not closed ([hidden]:
     * group id → the token ✕ hid). [notices] in the user's order of the subscriptions.
     */
    fun pick(notices: List<Notice>, later: Map<Long, String>, hidden: Map<Long, String>): Notice? =
        notices.firstOrNull { it.level == NoticeLevel.BLOCKED }
            ?: notices.firstOrNull { it.level == NoticeLevel.WARNING && later[it.groupId] != it.token }
            ?: notices.firstOrNull { it.level == NoticeLevel.NEWS && hidden[it.groupId] != it.token }

    /**
     * Every condition a notification tells of, whichever message the card shows: little traffic stays told while the
     * end comes near, so it is not sent again once the end is extended.
     */
    fun alertable(facts: SubscriptionFacts, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): List<Notice> {
        val placeholders = facts.placeholders.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
        val announce = facts.announce.trim()
        val expiry = when (SubscriptionHealth.expiry(facts.expire, nowSeconds)) {
            SubscriptionHealth.Expiry.EXPIRED -> Notice(facts, NoticeReason.EXPIRED, NoticeLevel.BLOCKED, placeholders)
            SubscriptionHealth.Expiry.SOON -> Notice(
                facts, NoticeReason.EXPIRING, NoticeLevel.WARNING,
                daysLeft = SubscriptionHealth.daysLeft(facts.expire, nowSeconds, zone),
            )

            else -> null
        }
        val traffic = when (SubscriptionHealth.traffic(facts.hasQuota, facts.total, facts.used)) {
            SubscriptionHealth.Traffic.EXHAUSTED -> Notice(facts, NoticeReason.EXHAUSTED, NoticeLevel.BLOCKED, placeholders)
            SubscriptionHealth.Traffic.LOW -> Notice(facts, NoticeReason.LOW_TRAFFIC, NoticeLevel.WARNING)
            else -> null
        }
        val limit = Notice(
            facts, NoticeReason.DEVICE_LIMIT,
            if (facts.usable == 0) NoticeLevel.BLOCKED else NoticeLevel.WARNING,
            placeholders.ifEmpty { announce },
        ).takeIf { facts.mark == FetchMark.LIMIT }
        val news = Notice(facts, NoticeReason.ANNOUNCE, NoticeLevel.NEWS, announce).takeIf { announce.isNotEmpty() }
        return listOfNotNull(expiry, traffic, limit, news)
    }

    /** The kinds of notification (simple-ui "Уведомления о подписке") and the state each one was sent for. */
    enum class AlertKind(val news: Boolean) { EXPIRY(false), TRAFFIC(false), LIMIT(false), NEWS(true) }

    /**
     * The notification state of [notice], null when it sends none: the end three days ahead and on the last day, the
     * end itself, little or no traffic, the device limit, a new announcement.
     */
    fun alert(notice: Notice): Pair<AlertKind, String>? = when (notice.reason) {
        NoticeReason.EXPIRING -> AlertKind.EXPIRY to if (notice.daysLeft <= 1) "last" else "soon"
        NoticeReason.EXPIRED -> AlertKind.EXPIRY to "expired"
        NoticeReason.LOW_TRAFFIC -> AlertKind.TRAFFIC to "low"
        NoticeReason.EXHAUSTED -> AlertKind.TRAFFIC to "exhausted"
        NoticeReason.DEVICE_LIMIT -> AlertKind.LIMIT to "limit"
        NoticeReason.ANNOUNCE -> AlertKind.NEWS to notice.token
        NoticeReason.REFUSED, NoticeReason.PLACEHOLDERS -> null
    }

    /**
     * Each event once: [sent] the state last sent per kind. Returns the notices to send now and the new [sent] (a kind
     * whose condition is over is forgotten, so it is sent again when it comes back).
     */
    fun due(notices: List<Notice>, sent: Map<AlertKind, String>): Pair<List<Notice>, Map<AlertKind, String>> {
        val current = LinkedHashMap<AlertKind, Pair<String, Notice>>()
        for (notice in notices) {
            val (kind, state) = alert(notice) ?: continue
            current.putIfAbsent(kind, state to notice)
        }
        val send = current.filter { (kind, value) -> sent[kind] != value.first }.map { it.value.second }
        return send to current.mapValues { it.value.first }
    }

    /** A short stable hash of a text (the announcement ✕ and «Позже» compare it). */
    fun hash(text: String): String {
        var h = 1125899906842597L
        for (c in text.trim()) h = 31 * h + c.code
        return java.lang.Long.toHexString(h)
    }
}
