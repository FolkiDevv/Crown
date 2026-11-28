package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.modes.Managed
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportCountsTest {

    private val subscription = listOf(
        ImportCounts.Row(101L, "🇩🇪 Germany 1", null),
        ImportCounts.Row(102L, "🇩🇪 Germany 2", null),
        ImportCounts.Row(103L, "🇳🇱 Amsterdam", null),
        // No country in the name: the exit IP check tells it.
        ImportCounts.Row(104L, "Server 4", "FI"),
        // Neither: no country.
        ImportCounts.Row(105L, "Server 5", null),
    )

    @Test
    fun summaryOfASubscriptionWithoutAManagedSelector() {
        val servers = ImportCounts.servers(subscription, Managed(10L, 11L, 20L, 21L, 30L).selectors)
        assertEquals(5, servers.size)
        assertEquals(3, ImportCounts.countries(servers))
    }

    @Test
    fun aManagedSelectorInTheGroupIsNoServer() {
        val rows = subscription + ImportCounts.Row(20L, "Auto", null) + ImportCounts.Row(30L, "🇺🇸 United States", null)
        val servers = ImportCounts.servers(rows, Managed(10L, 11L, auto = 20L, autoWhitelist = 21L, country = 30L).selectors)
        assertEquals(listOf(101L, 102L, 103L, 104L, 105L), servers.map { it.id })
        // The country selector, named after its country, does not add one.
        assertEquals(3, ImportCounts.countries(servers))
    }

    @Test
    fun missingSelectorsAreNoIds() {
        assertEquals(emptySet<Long>(), Managed(10L, 11L, 0L, 0L).selectors)
        assertEquals(setOf(20L, 30L), Managed(10L, 11L, auto = 20L, autoWhitelist = 0L, country = 30L).selectors)
        // The routing profiles are no auto-selectors.
        assertEquals(setOf(21L), Managed(smartRoute = 10L, whitelistRoute = 11L, auto = -1L, autoWhitelist = 21L).selectors)
        // A profile without an id (0) is never taken for a missing selector.
        val rows = listOf(ImportCounts.Row(0L, "🇩🇪 Germany", null))
        assertEquals(1, ImportCounts.servers(rows, Managed(0L, 0L, 0L, 0L).selectors).size)
    }

    @Test
    fun noServersNoCountries() {
        assertEquals(0, ImportCounts.countries(emptyList()))
    }
}
