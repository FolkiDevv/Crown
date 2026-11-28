package io.nekohasekai.sagernet.simple.servers

import io.nekohasekai.sagernet.database.ProxyEntity

/**
 * What the last background round of «Серверы» covered (simple-ui "Фоновая проверка серверов"), kept in the UI
 * process's SharedPreferences `simple_ui` so that a recreated activity or a new process keeps the interval:
 * [at] — when the round started (wall clock, ms); [known] — every listed server at that time (and those tested since);
 * [left] — servers picked for a round that were not tested yet (a round stopped when the section was hidden).
 */
data class RoundRecord(val at: Long, val known: Set<Long>, val left: Set<Long>) {

    /** A new round is due: none yet, the interval is over, or the clock went back past [at]. */
    fun due(now: Long, interval: Long): Boolean = at <= 0L || now < at || now - at >= interval

    /** One string, so that one write stores the three parts together. */
    fun encode(): String = "$at|${known.joinToString(",")}|${left.joinToString(",")}"

    companion object {
        val NONE = RoundRecord(0L, emptySet(), emptySet())

        /** [NONE] for anything unreadable. */
        fun decode(text: String?): RoundRecord {
            val parts = text?.split('|')?.takeIf { it.size == 3 } ?: return NONE
            val at = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: return NONE
            return RoundRecord(at, ids(parts[1]), ids(parts[2]))
        }

        private fun ids(text: String): Set<Long> =
            text.split(',').mapNotNullTo(HashSet()) { part -> part.toLongOrNull()?.takeIf { it > 0L } }
    }
}

/**
 * Which servers a background round tests (simple-ui "Фоновая проверка серверов"): what the list needs to show, not
 * every server. Pure, for JVM unit tests.
 */
object RoundPlan {

    /** At most this many servers per round, whatever the list holds. */
    const val LIMIT = 150

    /** Servers per country of a subscription: the best by the last results, then the never or longest untested. */
    const val PER_COUNTRY = 3
    private const val BEST = 2

    /**
     * Servers whose country neither the name nor an exit IP check gave, per round: the exit IP check that follows
     * asks a lookup service for each (ServersViewModel's IP_TEST_LIMIT is the same).
     */
    const val NO_COUNTRY = 30

    /**
     * A full round over [sections], at most [limit] servers in this order: the first pick of every country, the own
     * servers, those without a known country (never or longest untested first), the servers for whitelists, then the
     * other picks of each country. A server is listed once.
     */
    fun round(sections: ServerSections, limit: Int = LIMIT): List<Long> {
        val rows = sections.subscriptions.flatMap { it.countries }
        val picks = rows.filter { it.country is ServerCountry.Known }.map { pick(it.servers) }
        val noCountry = rows.filter { it.country !is ServerCountry.Known }
            .flatMap { it.servers }
            .sortedBy { it.testedAt }
            .take(NO_COUNTRY)
        val order = picks.mapNotNull { it.firstOrNull() } +
            sections.mine +
            noCountry +
            sections.whitelist +
            (1 until PER_COUNTRY).flatMap { k -> picks.mapNotNull { it.getOrNull(k) } }
        return order.asSequence().map { it.id }.distinct().take(limit).toList()
    }

    /**
     * Within the interval of [record]: first [wanted] (rows the user looks at) and what [record] left untested, both
     * only while not tested since the round started ([since], seconds, as `latency_at`), then a round over the servers
     * added since ([round] of those not in [RoundRecord.known]); at most [limit] in all.
     */
    fun followUp(
        sections: ServerSections,
        record: RoundRecord,
        since: Long,
        wanted: Collection<Long>,
        limit: Int = LIMIT,
    ): List<Long> {
        val byId = sections.allServers.associateBy { it.id }
        fun open(id: Long) = byId[id]?.let { it.testedAt < since } == true
        val first = (wanted.asSequence() + record.left.asSequence()).filter(::open)
        val added = round(sections.only { it.id !in record.known }, limit)
        return (first + added.asSequence()).distinct().take(limit).toList()
    }

    /** Up to [PER_COUNTRY] of one country: the [BEST] working fastest, then the never or longest untested. */
    private fun pick(servers: List<ServerEntry>): List<ServerEntry> {
        val best = servers.filter { it.working }.sortedBy { if (it.latency > 0) it.latency else Int.MAX_VALUE }.take(BEST)
        val rest = (servers - best.toSet()).sortedBy { it.testedAt }
        return best + rest.take(PER_COUNTRY - best.size)
    }

    private val ServerEntry.working: Boolean
        get() = latency > 0 || latency == ProxyEntity.LATENCY_CONNECT_ONLY

    /** [this] with only the servers [keep] keeps; rows left empty are dropped. */
    private fun ServerSections.only(keep: (ServerEntry) -> Boolean): ServerSections = ServerSections(
        subscriptions = subscriptions.map { s ->
            s.copy(countries = s.countries.mapNotNull { row ->
                row.servers.filter(keep).takeIf { it.isNotEmpty() }?.let { row.copy(servers = it) }
            })
        },
        mine = mine.filter(keep),
        whitelist = whitelist.filter(keep),
    )
}
