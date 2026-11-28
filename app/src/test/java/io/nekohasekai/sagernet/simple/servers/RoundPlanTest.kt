package io.nekohasekai.sagernet.simple.servers

import io.nekohasekai.sagernet.database.ProxyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** limit-automatic-server-tests: which servers a background round of «Серверы» tests. */
class RoundPlanTest {

    private var nextId = 1L

    private fun server(
        group: Long,
        country: String?,
        latency: Int = 0,
        testedAt: Long = 0L,
        whitelist: Boolean = false,
    ) = ServerEntry(
        id = nextId++,
        groupId = group,
        name = "${country ?: "Server"} $nextId",
        latency = latency,
        country = if (country != null) ServerCountry.Known(country, true) else ServerCountry.Unknown,
        whitelist = whitelist,
        testedAt = testedAt,
    )

    private fun sections(servers: List<ServerEntry>, own: Set<Long> = emptySet()): ServerSections {
        val groups = servers.map { it.groupId }.distinct().map { GroupEntry(it, "g$it", it !in own) }
        return ServerList.build(groups, servers) { it }
    }

    @Test
    fun `a country gets its best servers and an untested one, not all of them`() {
        val fast = server(1, "DE", latency = 40, testedAt = 100)
        val faster = server(1, "DE", latency = 20, testedAt = 100)
        val slow = server(1, "DE", latency = 300, testedAt = 100)
        val dead = server(1, "DE", latency = -1, testedAt = 50)
        val untested = server(1, "DE")
        val ids = RoundPlan.round(sections(listOf(fast, faster, slow, dead, untested)))
        assertEquals(RoundPlan.PER_COUNTRY, ids.size)
        // The fastest first: it stands for the country in the list.
        assertEquals(faster.id, ids[0])
        assertEquals(setOf(faster.id, fast.id, untested.id), ids.toSet())
    }

    @Test
    fun `every country is covered before a country's second pick`() {
        val countries = listOf("DE", "NL", "FI", "SE")
        val servers = countries.flatMap { code -> List(10) { server(1, code, latency = 50 + it, testedAt = 100) } }
        val ids = RoundPlan.round(sections(servers), limit = countries.size + 1)
        val first = ids.take(countries.size).map { id -> servers.first { it.id == id }.country.code }
        assertEquals(countries.toSet(), first.toSet())
    }

    @Test
    fun `servers without a country are capped, never or longest untested first`() {
        val stale = List(RoundPlan.NO_COUNTRY) { server(1, null, latency = 80, testedAt = 1_000L + it) }
        val never = List(5) { server(1, null) }
        val ids = RoundPlan.round(sections(stale + never))
        assertEquals(RoundPlan.NO_COUNTRY, ids.size)
        assertTrue(ids.containsAll(never.map { it.id }))
        // The most recently tested are left for a later round.
        assertTrue(stale.takeLast(5).none { it.id in ids })
    }

    @Test
    fun `own servers and servers for whitelists are all tested`() {
        val mine = List(4) { server(9, "DE") }
        val whitelist = List(3) { server(1, "RU", whitelist = true) }
        val sub = List(20) { server(1, "NL", latency = 60, testedAt = 10) }
        val ids = RoundPlan.round(sections(mine + whitelist + sub, own = setOf(9L)))
        assertTrue(ids.containsAll(mine.map { it.id }))
        assertTrue(ids.containsAll(whitelist.map { it.id }))
        assertEquals(mine.size + whitelist.size + RoundPlan.PER_COUNTRY, ids.size)
    }

    @Test
    fun `a round of thousands of servers stays within the limit`() {
        val codes = (0 until 80).map { "C${'A' + it / 26}${'A' + it % 26}".takeLast(2) }.distinct()
        val servers = (0 until 3000).map { i -> server(1 + i % 3L, if (i % 10 == 0) null else codes[i % codes.size], latency = 100) }
        val ids = RoundPlan.round(sections(servers))
        assertEquals(RoundPlan.LIMIT, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `within the interval only the added, the shown and the left untested are tested`() {
        val old = List(10) { server(1, "DE", latency = 50, testedAt = 100) }
        val added = List(4) { server(1, "NL") }
        val leftTested = server(1, "FI", latency = 70, testedAt = 500)
        val leftOpen = server(1, "FI", latency = 70, testedAt = 100)
        val shown = old[7]
        val all = old + added + leftTested + leftOpen
        val record = RoundRecord(
            at = 400_000L,
            known = (old + leftTested + leftOpen).map { it.id }.toSet(),
            left = setOf(leftTested.id, leftOpen.id),
        )
        val ids = RoundPlan.followUp(sections(all), record, since = 400L, wanted = listOf(shown.id))
        // The row the user looks at first, then what the stopped round left, then a round over the added servers.
        assertEquals(listOf(shown.id, leftOpen.id), ids.take(2))
        assertEquals(setOf(shown.id, leftOpen.id) + added.take(RoundPlan.PER_COUNTRY).map { it.id }, ids.toSet())
    }

    @Test
    fun `a follow-up skips what was tested since the round began and servers that are gone`() {
        val tested = server(1, "DE", latency = 50, testedAt = 900)
        val record = RoundRecord(800_000L, setOf(tested.id, 999L), setOf(999L))
        val ids = RoundPlan.followUp(sections(listOf(tested)), record, since = 800L, wanted = listOf(tested.id))
        assertEquals(emptyList<Long>(), ids)
    }

    @Test
    fun `connect-only servers count as working`() {
        val connect = server(1, "DE", latency = ProxyEntity.LATENCY_CONNECT_ONLY, testedAt = 10)
        val dead = List(3) { server(1, "DE", latency = -1, testedAt = 20) }
        val ids = RoundPlan.round(sections(listOf(connect) + dead))
        assertEquals(connect.id, ids[0])
    }

    @Test
    fun `the record survives its encoding and an unreadable one is none`() {
        val record = RoundRecord(1_700_000_000_000L, setOf(1L, 2L, 30L), setOf(2L))
        assertEquals(record, RoundRecord.decode(record.encode()))
        assertEquals(RoundRecord(5L, emptySet(), emptySet()), RoundRecord.decode(RoundRecord(5L, emptySet(), emptySet()).encode()))
        assertEquals(RoundRecord.NONE, RoundRecord.decode(null))
        assertEquals(RoundRecord.NONE, RoundRecord.decode("garbage"))
        assertEquals(RoundRecord.NONE, RoundRecord.decode("-4|1,2|"))
        assertEquals(setOf(1L), RoundRecord.decode("9|1,x,-3|").known)
    }

    @Test
    fun `a round is due after the interval, without a record and when the clock went back`() {
        val record = RoundRecord(1_000_000L, emptySet(), emptySet())
        assertTrue(!record.due(1_000_000L + 60_000L, 120_000L))
        assertTrue(record.due(1_000_000L + 120_000L, 120_000L))
        assertTrue(record.due(999_000L, 120_000L))
        assertTrue(RoundRecord.NONE.due(5L, 120_000L))
    }
}
