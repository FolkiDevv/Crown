package io.nekohasekai.sagernet.simple.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficModePlanTest {

    private val own = "com.nb4a.throne"
    private val browser = "org.mozilla.firefox"
    private val bank = "ru.sberbankmobile"
    private val messenger = "org.telegram.messenger"

    @Test
    fun autoIsTheDefaultMode() {
        assertEquals(TrafficMode.AUTO, TrafficMode.of(null))
        assertEquals(TrafficMode.AUTO, TrafficMode.of("unknown"))
        TrafficMode.entries.forEach { assertEquals(it, TrafficMode.of(it.key)) }
    }

    // ------------------------------------------------------------------------------------------------ per-app scope

    @Test
    fun appsModeIncludesOnlyTheSelectedApps() {
        val scope = TrafficModePlan.scope(TrafficMode.APPS, listOf(browser), listOf(bank), own)
        assertTrue(scope.enabled)
        assertFalse(scope.bypass)
        assertEquals(listOf(browser, own), scope.packages)
    }

    @Test
    fun otherModesWithoutBypassListHaveNoPerAppRule() {
        for (mode in listOf(TrafficMode.AUTO, TrafficMode.SMART, TrafficMode.WHITELIST)) {
            val scope = TrafficModePlan.scope(mode, listOf(browser), emptyList(), own)
            assertFalse(mode.name, scope.enabled)
            assertEquals(mode.name, emptyList<String>(), scope.packages)
        }
    }

    @Test
    fun otherModesExcludeTheBypassList() {
        for (mode in listOf(TrafficMode.AUTO, TrafficMode.SMART, TrafficMode.WHITELIST)) {
            // The selection of «Только выбранные приложения» does not leak into the other modes.
            val scope = TrafficModePlan.scope(mode, listOf(browser), listOf(bank, messenger), own)
            assertTrue(mode.name, scope.enabled)
            assertTrue(mode.name, scope.bypass)
            assertEquals(mode.name, listOf(bank, messenger), scope.packages)
        }
    }

    @Test
    fun theAppItselfAlwaysStaysInTheTunnel() {
        val bypass = TrafficModePlan.scope(TrafficMode.WHITELIST, emptyList(), listOf(own, bank), own)
        assertEquals(listOf(bank), bypass.packages)
        val onlyOwn = TrafficModePlan.scope(TrafficMode.SMART, emptyList(), listOf(own), own)
        assertFalse(onlyOwn.enabled)
        val include = TrafficModePlan.scope(TrafficMode.APPS, listOf(own, browser), emptyList(), own)
        assertEquals(listOf(own, browser), include.packages)
    }

    @Test
    fun emptySelectionFallsBackToAutoNotToAnEmptyIncludeList() {
        assertEquals(TrafficMode.AUTO, TrafficModePlan.applicable(TrafficMode.APPS, emptyList()))
        assertEquals(TrafficMode.AUTO, TrafficModePlan.applicable(TrafficMode.APPS, listOf(" ", "")))
        assertEquals(TrafficMode.APPS, TrafficModePlan.applicable(TrafficMode.APPS, listOf(browser)))
        for (mode in listOf(TrafficMode.AUTO, TrafficMode.SMART, TrafficMode.WHITELIST)) {
            assertEquals(mode, TrafficModePlan.applicable(mode, emptyList()))
        }
        val fallback = TrafficModePlan.applicable(TrafficMode.APPS, emptyList())
        val scope = TrafficModePlan.scope(fallback, emptyList(), emptyList(), own)
        // Not an include list: every app keeps the routing of «Авто» instead of all apps being forced into the VPN.
        assertFalse(scope.enabled && !scope.bypass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun anIncludeListWithoutAppsIsNeverBuilt() {
        TrafficModePlan.scope(TrafficMode.APPS, emptyList(), listOf(bank), own)
    }

    @Test
    fun bothListsSurviveAModeRoundTrip() {
        val selected = listOf(browser)
        val bypass = listOf(bank)
        val apps = TrafficModePlan.scope(TrafficMode.APPS, selected, bypass, own)
        val whitelist = TrafficModePlan.scope(TrafficMode.WHITELIST, selected, bypass, own)
        val back = TrafficModePlan.scope(TrafficMode.APPS, selected, bypass, own)
        assertEquals(listOf(bank), whitelist.packages)
        assertEquals(apps, back)
    }

    @Test
    fun listsAreParsedLineByLine() {
        assertEquals(listOf(browser, bank), TrafficModePlan.parseList(" $browser \n\n$bank\n$browser\r\n"))
        assertEquals(emptyList<String>(), TrafficModePlan.parseList(null))
        assertEquals("$browser\n$own", PerAppScope(true, false, listOf(browser, own)).individual)
    }

    // ------------------------------------------------------------------------------------------------ routing, server

    @Test
    fun routingOfEachMode() {
        assertEquals(Routing.SMART, TrafficModePlan.routing(TrafficMode.AUTO, whitelistsDetected = false))
        assertEquals(Routing.WHITELIST, TrafficModePlan.routing(TrafficMode.AUTO, whitelistsDetected = true))
        assertEquals(Routing.SMART, TrafficModePlan.routing(TrafficMode.SMART, whitelistsDetected = true))
        assertEquals(Routing.WHITELIST, TrafficModePlan.routing(TrafficMode.WHITELIST, whitelistsDetected = false))
        assertEquals(Routing.SMART, TrafficModePlan.routing(TrafficMode.APPS, whitelistsDetected = true))
    }

    @Test
    fun aManuallyChosenServerIsNeverChanged() {
        for (routing in Routing.entries) {
            assertEquals(42L, TrafficModePlan.server(42L, auto = 7L, autoWhitelist = 8L, routing = routing))
            assertEquals(0L, TrafficModePlan.server(0L, auto = 7L, autoWhitelist = 8L, routing = routing))
        }
    }

    @Test
    fun theManagedAutoSelectorFollowsTheRouting() {
        assertEquals(8L, TrafficModePlan.server(7L, auto = 7L, autoWhitelist = 8L, routing = Routing.WHITELIST))
        assertEquals(7L, TrafficModePlan.server(8L, auto = 7L, autoWhitelist = 8L, routing = Routing.SMART))
        assertEquals(7L, TrafficModePlan.server(7L, auto = 7L, autoWhitelist = 8L, routing = Routing.SMART))
        // A missing selector leaves the choice as it is.
        assertEquals(7L, TrafficModePlan.server(7L, auto = 7L, autoWhitelist = 0L, routing = Routing.WHITELIST))
    }

    // ------------------------------------------------------------------------------------------------ service

    private fun applied(route: Long, scope: PerAppScope, server: Long = 1L) = TrafficModePlan.Applied(route, scope, server)

    @Test
    fun aChangedAppScopeRestartsTheTunnel() {
        val none = PerAppScope(false, true, emptyList())
        val bypass = PerAppScope(true, true, listOf(bank))
        val include = PerAppScope(true, false, listOf(browser, own))
        assertEquals(ServiceChange.RESTART, TrafficModePlan.change(applied(1, none), applied(1, bypass)))
        assertEquals(ServiceChange.RESTART, TrafficModePlan.change(applied(1, bypass), applied(2, include)))
        assertEquals(ServiceChange.RELOAD, TrafficModePlan.change(applied(1, bypass), applied(2, bypass)))
        assertEquals(ServiceChange.RELOAD, TrafficModePlan.change(applied(1, bypass, 3), applied(1, bypass, 4)))
        assertEquals(ServiceChange.NONE, TrafficModePlan.change(applied(1, bypass), applied(1, bypass)))
    }

    @Test
    fun aRewrittenRouteInUseReloads() {
        val scope = PerAppScope(true, true, listOf(bank))
        assertEquals(ServiceChange.RELOAD, TrafficModePlan.change(applied(11, scope), applied(11, scope), changedRoutes = setOf(11L)))
        // A rewritten route that does not run changes nothing.
        assertEquals(ServiceChange.NONE, TrafficModePlan.change(applied(10, scope), applied(10, scope), changedRoutes = setOf(11L)))
    }

    @Test
    fun aStartDuringAnAppListEditRebuildsTheTunnel() {
        // The keys look unchanged, but the core that started meanwhile built its TUN from the staged editor keys.
        val scope = PerAppScope(false, true, emptyList())
        assertEquals(ServiceChange.RESTART, TrafficModePlan.change(applied(10, scope), applied(10, scope), stagedScopeMayRun = true))
        assertEquals(ServiceChange.NONE, TrafficModePlan.change(applied(10, scope), applied(10, scope), stagedScopeMayRun = false))
    }

    @Test
    fun aStartThatMayHaveReadTheStagedKeysIsSeen() {
        // Nothing started while the list was open: the restored keys are what runs.
        assertFalse(TrafficModePlan.stagedScopeMayRun(startsAtEdit = 5, startsNow = 5, startInProgress = false))
        // A completed start (fresh: counted at Connected; restart in place: counted when the old core stopped).
        assertTrue(TrafficModePlan.stagedScopeMayRun(startsAtEdit = 5, startsNow = 6, startInProgress = false))
        // The list closes after a fresh start read its settings but before it counted itself at Connected.
        assertTrue(TrafficModePlan.stagedScopeMayRun(startsAtEdit = 5, startsNow = 5, startInProgress = true))
        // The counter recorded at edit time was lost (process death): assume the worst.
        assertTrue(TrafficModePlan.stagedScopeMayRun(startsAtEdit = null, startsNow = 5, startInProgress = false))
        // Either way the application then rebuilds the TUN even though the restored keys match.
        val scope = PerAppScope(false, true, emptyList())
        val mayRun = TrafficModePlan.stagedScopeMayRun(startsAtEdit = 5, startsNow = 5, startInProgress = true)
        assertEquals(ServiceChange.RESTART, TrafficModePlan.change(applied(10, scope), applied(10, scope), stagedScopeMayRun = mayRun))
    }

    @Test
    fun scopesThatTheTunnelSeesAlikeAreNoChange() {
        // Per-app off: whatever list and flag are stored, the TUN takes every app.
        val offA = PerAppScope(false, false, listOf(browser))
        val offB = PerAppScope(false, true, emptyList())
        assertEquals(ServiceChange.NONE, TrafficModePlan.change(applied(1, offA), applied(1, offB)))
        val ab = PerAppScope(true, true, listOf(bank, messenger))
        val ba = PerAppScope(true, true, listOf(messenger, bank))
        assertEquals(ServiceChange.NONE, TrafficModePlan.change(applied(1, ab), applied(1, ba)))
    }

    // ------------------------------------------------------------------------------------------------ mode card

    private val managed = Managed(smartRoute = 10L, whitelistRoute = 11L, auto = 20L, autoWhitelist = 21L)
    private val noScope = PerAppScope(false, true, emptyList())

    @Test
    fun theCardShowsTheModeInEffect() {
        assertEquals(ModeCard.AUTO_SMART, TrafficModePlan.card(TrafficMode.AUTO, 10L, managed, noScope))
        assertEquals(ModeCard.AUTO_WHITELIST, TrafficModePlan.card(TrafficMode.AUTO, 11L, managed, noScope))
        assertEquals(ModeCard.SMART, TrafficModePlan.card(TrafficMode.SMART, 10L, managed, noScope))
        assertEquals(ModeCard.WHITELIST, TrafficModePlan.card(TrafficMode.WHITELIST, 11L, managed, noScope))
        val include = PerAppScope(true, false, listOf(browser, own))
        assertEquals(ModeCard.APPS, TrafficModePlan.card(TrafficMode.APPS, 10L, managed, include))
        assertTrue(ModeCard.AUTO_WHITELIST.whitelists && ModeCard.WHITELIST.whitelists)
        assertFalse(ModeCard.AUTO_SMART.whitelists || ModeCard.SMART.whitelists || ModeCard.APPS.whitelists)
    }

    @Test
    fun routingFromTheClassicInterfaceShowsAsCustom() {
        assertEquals(ModeCard.CUSTOM, TrafficModePlan.card(TrafficMode.AUTO, 3L, managed, noScope))
        assertEquals(ModeCard.CUSTOM, TrafficModePlan.card(TrafficMode.SMART, 3L, Managed(0, 0, 0, 0), noScope))
        // A bypass list in «Умный» is still «Умный».
        val bypass = PerAppScope(true, true, listOf(bank))
        assertEquals(ModeCard.SMART, TrafficModePlan.card(TrafficMode.SMART, 10L, managed, bypass))
    }
}
