package io.nekohasekai.sagernet.simple.subs

import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase

/**
 * The subscription state the simple UI keeps beside the database (design.md D3: DataStore keys with the `simple_`
 * prefix, which both processes read): the mark of the last fetch, the user's «Позже» and ✕, and the notifications
 * already sent. Blocking: call it off the main thread.
 */
object SubscriptionState {

    private const val MARK = "simple_sub_mark_"
    private const val LATER = "simple_sub_later_"
    private const val HIDDEN = "simple_sub_hidden_"
    private const val ALERT = "simple_sub_alert_"

    private val store get() = DataStore.configurationStore

    fun mark(gid: Long): FetchMark = FetchMark.of(store.getString(MARK + gid))

    fun setMark(gid: Long, mark: FetchMark) = store.putString(MARK + gid, mark.key.ifEmpty { null })

    fun later(gid: Long): String? = store.getString(LATER + gid)

    fun setLater(gid: Long, token: String) = store.putString(LATER + gid, token)

    fun clearLater(gid: Long) {
        if (store.contains(LATER + gid)) store.remove(LATER + gid)
    }

    fun hidden(gid: Long): String? = store.getString(HIDDEN + gid)

    fun setHidden(gid: Long, token: String) = store.putString(HIDDEN + gid, token)

    fun alerts(gid: Long): Map<SubscriptionNotices.AlertKind, String> =
        SubscriptionNotices.AlertKind.entries.mapNotNull { kind -> store.getString(alertKey(gid, kind))?.let { kind to it } }.toMap()

    fun setAlerts(gid: Long, sent: Map<SubscriptionNotices.AlertKind, String>) {
        for (kind in SubscriptionNotices.AlertKind.entries) store.putString(alertKey(gid, kind), sent[kind])
    }

    /** A deleted subscription leaves nothing behind. */
    fun forget(gid: Long) {
        for (key in listOf(MARK, LATER, HIDDEN)) store.remove(key + gid)
        for (kind in SubscriptionNotices.AlertKind.entries) store.remove(alertKey(gid, kind))
    }

    private fun alertKey(gid: Long, kind: SubscriptionNotices.AlertKind) = ALERT + gid + "_" + kind.name.lowercase()

    /** The subscriptions (not archived) in the user's group order, with what their messages need. */
    fun facts(): List<SubscriptionFacts> = GroupRepo.all().filter { it.isSubscription && !it.archive }.map(::factsOf)

    fun factsOf(group: ProxyGroup): SubscriptionFacts {
        val members = SagerDatabase.proxyDao.getByGroup(group.id).filter { it.type != AutoSelectorProfiles.TYPE }
        val (placeholders, usable) = members.partition(::isPlaceholder)
        return factsOf(group, usable.size, placeholders.map { it.outbound.name.trim() })
    }

    /** [usable]: its servers that are no placeholders; [placeholders]: the placeholders' names. */
    fun factsOf(group: ProxyGroup, usable: Int, placeholders: List<String>): SubscriptionFacts {
        val info = group.subInfo
        return SubscriptionFacts(
            groupId = group.id,
            name = group.displayName(),
            expire = if (info.valid) info.expire else 0L,
            hasQuota = info.valid && info.hasQuota,
            total = info.total,
            used = info.used,
            announce = if (info.valid) info.announce.trim() else "",
            webUrl = if (info.valid) info.webUrl.trim() else "",
            supportUrl = if (info.valid) info.supportUrl.trim() else "",
            usable = usable,
            placeholders = placeholders,
            mark = mark(group.id),
        )
    }

    fun isPlaceholder(profile: ProxyEntity): Boolean {
        val outbound = profile.outbound
        if (outbound.invalid || !outbound.hasServerEndpoint()) return false
        return Placeholders.isPlaceholder(outbound.server, outbound.serverPort, outbound.serverPortOptional())
    }
}
