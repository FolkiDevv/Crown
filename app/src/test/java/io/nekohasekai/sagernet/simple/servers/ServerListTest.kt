package io.nekohasekai.sagernet.simple.servers

import io.nekohasekai.sagernet.simple.modes.WhitelistKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerListTest {

    private val keywords = listOf("LTE", "БС")
    private val names = mapOf("DE" to "Germany", "NL" to "Netherlands", "FI" to "Finland", "US" to "United States")

    private var nextId = 1L

    private fun server(group: Long, name: String, latency: Int = 0, testCountry: String? = null, detecting: Boolean = false) =
        ServerEntry(
            id = nextId++,
            groupId = group,
            name = name,
            latency = latency,
            country = CountryFromName.resolve(name, testCountry, detecting),
            whitelist = WhitelistKeywords.isWhitelistServer(name, keywords),
        )

    private val subscription = GroupEntry(1L, "Provider", subscription = true)
    private val mine = GroupEntry(2L, "My servers", subscription = false)

    private fun build(servers: List<ServerEntry>, groups: List<GroupEntry> = listOf(mine, subscription)) =
        ServerList.build(groups, servers) { names[it] ?: it }

    @Test
    fun subscriptionByCountryAndOwnServersByName() {
        val sections = build(
            listOf(
                server(1L, "🇳🇱 Amsterdam 1", latency = 80),
                server(1L, "🇩🇪 Germany 10", latency = 50),
                server(1L, "🇩🇪 Germany 2", latency = -1),
                server(1L, "🇫🇮 Helsinki"),
                server(2L, "zeta"),
                server(2L, "Alpha"),
            )
        )
        val sub = sections.subscriptions.single()
        // Countries by their name in the interface language; servers by name, numbers by value.
        assertEquals(listOf("FI", "DE", "NL"), sub.countries.map { it.code })
        assertEquals(listOf("🇩🇪 Germany 2", "🇩🇪 Germany 10"), sub.countries[1].servers.map { it.name })
        assertEquals(50, sub.countries[1].bestLatency)
        assertNull(sub.countries[0].bestLatency)
        assertEquals(listOf("Alpha", "zeta"), sections.mine.map { it.name })
        assertTrue(sections.whitelist.isEmpty())
        assertFalse(sections.isEmpty)
    }

    @Test
    fun whitelistServersOnlyInTheirOwnSection() {
        val sections = build(
            listOf(
                server(1L, "🇷🇺 RU LTE обход БС"),
                server(1L, "🇩🇪 Germany 1"),
                server(1L, "БС-2 Moscow"),
                server(2L, "my LTE box"),
                server(2L, "home"),
            )
        )
        // By the shown name (without the leading flag).
        assertEquals(listOf("my LTE box", "🇷🇺 RU LTE обход БС", "БС-2 Moscow"), sections.whitelist.map { it.name })
        val listed = sections.subscriptions.flatMap { s -> s.countries.flatMap { it.servers } } + sections.mine
        assertTrue(listed.none { it.whitelist })
        assertEquals(listOf("DE"), sections.subscriptions.single().countries.map { it.code })
        assertEquals(listOf("home"), sections.mine.map { it.name })
        // Every server is in exactly one section.
        assertEquals(5, sections.allServers.map { it.id }.distinct().size)
        assertEquals(5, sections.allServers.size)
    }

    @Test
    fun unknownCountriesLastAndIpCheckResult() {
        val sections = build(
            listOf(
                server(1L, "Node A"),
                server(1L, "Node B", detecting = true),
                server(1L, "Node C", testCountry = "FI"),
                server(1L, "Germany 1"),
            )
        )
        val rows = sections.subscriptions.single().countries
        assertEquals(listOf("FI", "DE", null, null), rows.map { it.code })
        assertEquals(ServerCountry.Detecting, rows[2].country)
        assertEquals(ServerCountry.Unknown, rows[3].country)
        assertEquals(listOf("Node C"), rows[0].servers.map { it.name })
    }

    @Test
    fun groupsKeepTheirOrderAndUnlistedGroupsAreLeftOut() {
        val second = GroupEntry(3L, "Second", subscription = true)
        val sections = build(
            listOf(server(3L, "🇺🇸 New York"), server(1L, "🇩🇪 Berlin"), server(9L, "🇳🇱 orphan")),
            listOf(second, subscription),
        )
        assertEquals(listOf(3L, 1L), sections.subscriptions.map { it.group.id })
        assertTrue(sections.allServers.none { it.groupId == 9L })
    }

    @Test
    fun emptySubscriptionIsStillListed() {
        val sections = build(emptyList())
        assertEquals(listOf(1L), sections.subscriptions.map { it.group.id })
        assertTrue(sections.isEmpty)
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("Server 10", "server 2", "Server 1", "Server 02a", "Alpha").sortedWith(ServerList.NaturalOrder)
        assertEquals(listOf("Alpha", "Server 1", "server 2", "Server 02a", "Server 10"), sorted)
    }

    @Test
    fun countrySelectorFilter() {
        // Compiled the way the auto-selector compiles its filters.
        fun matches(filter: String, name: String) =
            java.util.regex.Pattern.compile(filter, WhitelistKeywords.FLAGS).matcher(name).find()
        val filter = ServerList.exactNameFilter(listOf("Edge", "🇩🇪 Berlin (1)", "Edge", ""))
        assertTrue(matches(filter, "Edge"))
        assertTrue(matches(filter, "🇩🇪 Berlin (1)"))
        assertFalse(matches(filter, "edge"))
        assertFalse(matches(filter, "EDGE"))
        assertFalse(matches(filter, "Edge 2"))
        assertFalse(matches(filter, "🇩🇪 Berlin (1) copy"))
        // A name with a line break after it is another name.
        assertFalse(matches(filter, "Edge\n"))
        assertFalse(matches(filter, "x\nEdge"))
        assertFalse(matches(ServerList.exactNameFilter(emptyList()), "Edge"))
        // Cyrillic is compared exactly too.
        assertFalse(matches(ServerList.exactNameFilter(listOf("Германия")), "германия"))
        // A name another server of the group also has is left out, so the filter cannot admit that server.
        assertEquals(
            listOf("Berlin"),
            ServerList.exclusiveNames(listOf("Berlin", "Edge", "Berlin"), listOf("Edge", "edge", "Paris")),
        )
    }

    @Test
    fun countryChoiceCodec() {
        assertEquals(CountryChoice(12L, "DE"), CountryChoice.parse("12:DE"))
        assertEquals(CountryChoice(12L, "DE"), CountryChoice.parse(CountryChoice(12L, "de").encode()))
        assertNull(CountryChoice.parse(null))
        assertNull(CountryChoice.parse("x:DE"))
        assertNull(CountryChoice.parse("0:DE"))
        assertNull(CountryChoice.parse("12:DEU"))
    }
}
