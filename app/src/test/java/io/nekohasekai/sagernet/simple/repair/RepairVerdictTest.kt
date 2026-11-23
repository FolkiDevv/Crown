package io.nekohasekai.sagernet.simple.repair

import io.nekohasekai.sagernet.simple.repair.RepairVerdict.Cause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairVerdictTest {

    /** A finished run where everything passed, battery included, with [changes] on top. */
    private fun run(vararg changes: Pair<RepairStep, StepResult>): Map<RepairStep, StepResult> {
        val results = RepairPlan.ORDER.associateWith { StepResult.ok() }.toMutableMap()
        results[RepairStep.WHITELISTS] = StepResult.ok(Finding.NO_WHITELISTS)
        results[RepairStep.SERVERS] = servers(total = 5, answering = 5, currentAnswers = true)
        results.putAll(changes)
        return results
    }

    private fun servers(
        total: Int,
        answering: Int,
        whitelistTotal: Int = 0,
        whitelistAnswering: Int = 0,
        currentAnswers: Boolean = answering > 0,
    ): StepResult {
        val summary = ServersSummary(total, answering, whitelistTotal, whitelistAnswering, if (answering > 0) 1L else 0L, currentAnswers)
        val (status, findings) = when {
            total == 0 -> StepStatus.FAIL to setOf(Finding.NO_SERVERS)
            answering == 0 -> StepStatus.FAIL to setOf(Finding.NO_SERVER_ANSWERS)
            !currentAnswers -> StepStatus.WARN to setOf(Finding.CURRENT_SERVER_DOWN)
            else -> StepStatus.OK to emptySet()
        }
        return StepResult(status, findings, servers = summary)
    }

    private val noServerAnswers = RepairStep.SERVERS to servers(total = 5, answering = 0)
    private val vpnChecksSkipped = arrayOf(
        RepairStep.DNS_VPN to StepResult.skipped(SkipReason.NO_WORKING_SERVER),
        RepairStep.SITES_VPN to StepResult.skipped(SkipReason.NO_WORKING_SERVER),
    )

    private fun cause(results: Map<RepairStep, StepResult>): Cause? =
        (RepairVerdict.of(results) as? RepairVerdict.CauseFound)?.cause

    // ------------------------------------------------------------------------------------------------ known causes

    @Test
    fun noInternet() {
        val skipped = RepairPlan.ORDER.filter { RepairPlan.REQUIRES.getValue(it).isNotEmpty() }
            .map { it to StepResult.skipped(SkipReason.NO_INTERNET) }.toTypedArray()
        val results = run(RepairStep.INTERNET to StepResult.fail(Finding.NO_NETWORK), *skipped)
        assertEquals(Cause.NO_INTERNET, cause(results))
    }

    @Test
    fun noInternetEvenWithOtherProblems() {
        val results = run(
            RepairStep.INTERNET to StepResult.fail(Finding.NOTHING_ANSWERS),
            RepairStep.PRIVATE_DNS_VPN to StepResult.fail(Finding.PRIVATE_DNS),
            RepairStep.BACKGROUND to StepResult.warn(Finding.BATTERY_OPTIMIZED),
        )
        assertEquals(Cause.NO_INTERNET, cause(results))
    }

    @Test
    fun clock() {
        val results = run(
            RepairStep.CLOCK to StepResult.fail(Finding.CLOCK_OFF, values = mapOf(RepairValues.CLOCK_OFFSET_S to -420L)),
            noServerAnswers,
            *vpnChecksSkipped,
        )
        assertEquals(Cause.CLOCK, cause(results))
    }

    @Test
    fun otherVpn() {
        val results = run(
            RepairStep.PRIVATE_DNS_VPN to StepResult.fail(Finding.OTHER_VPN),
            RepairStep.DNS_VPN to StepResult.skipped(SkipReason.OTHER_VPN),
            RepairStep.SITES_VPN to StepResult.skipped(SkipReason.OTHER_VPN),
        )
        assertEquals(Cause.OTHER_VPN, cause(results))
    }

    @Test
    fun privateDns() {
        assertEquals(Cause.PRIVATE_DNS, cause(run(RepairStep.PRIVATE_DNS_VPN to StepResult.fail(Finding.PRIVATE_DNS))))
    }

    @Test
    fun otherVpnBeforePrivateDnsWhenBoth() {
        val both = StepResult(StepStatus.FAIL, setOf(Finding.PRIVATE_DNS, Finding.OTHER_VPN))
        assertEquals(Cause.OTHER_VPN, cause(run(RepairStep.PRIVATE_DNS_VPN to both)))
    }

    @Test
    fun subscriptionExpired() {
        val results = run(RepairStep.SUBSCRIPTION to StepResult.fail(Finding.SUBSCRIPTION_EXPIRED), noServerAnswers, *vpnChecksSkipped)
        assertEquals(Cause.SUBSCRIPTION_ENDED, cause(results))
    }

    @Test
    fun subscriptionTrafficUsedUp() {
        val results = run(RepairStep.SUBSCRIPTION to StepResult.fail(Finding.TRAFFIC_EXHAUSTED))
        assertEquals(Cause.SUBSCRIPTION_ENDED, cause(results))
    }

    @Test
    fun emptySubscriptionWithoutAnyAnsweringServer() {
        val results = run(
            RepairStep.SUBSCRIPTION to StepResult.fail(Finding.SUBSCRIPTION_EMPTY),
            RepairStep.SERVERS to servers(total = 0, answering = 0),
            *vpnChecksSkipped,
        )
        assertEquals(Cause.SUBSCRIPTION_EMPTY, cause(results))
    }

    @Test
    fun whitelistsAndNoServersForThemInTheSubscription() {
        val results = run(
            RepairStep.WHITELISTS to StepResult.ok(Finding.WHITELISTS_FOUND),
            RepairStep.SERVERS to servers(total = 5, answering = 0, whitelistTotal = 0),
            *vpnChecksSkipped,
        )
        assertEquals(Cause.WHITELISTS_NO_SERVERS, cause(results))
    }

    @Test
    fun whitelistsAndTheirServersDoNotAnswer() {
        val results = run(
            RepairStep.WHITELISTS to StepResult.ok(Finding.WHITELISTS_FOUND),
            RepairStep.SERVERS to servers(total = 5, answering = 0, whitelistTotal = 2, whitelistAnswering = 0),
            *vpnChecksSkipped,
        )
        assertEquals(Cause.WHITELISTS_SERVERS_DOWN, cause(results))
    }

    @Test
    fun allServersDownWithoutWhitelists() {
        assertEquals(Cause.ALL_SERVERS_DOWN, cause(run(noServerAnswers, *vpnChecksSkipped)))
    }

    @Test
    fun allServersDownWhenNoServerAtAllAndNoSubscription() {
        val results = run(
            RepairStep.SUBSCRIPTION to StepResult.skipped(SkipReason.NO_SUBSCRIPTION),
            RepairStep.SERVERS to servers(total = 0, answering = 0),
            *vpnChecksSkipped,
        )
        assertEquals(Cause.ALL_SERVERS_DOWN, cause(results))
    }

    @Test
    fun currentServerDownWhileOthersAnswer() {
        val results = run(RepairStep.SERVERS to servers(total = 5, answering = 3, currentAnswers = false))
        assertEquals(Cause.CURRENT_SERVER_DOWN, cause(results))
    }

    @Test
    fun batteryWhenItIsTheOnlyProblem() {
        assertEquals(Cause.BATTERY, cause(run(RepairStep.BACKGROUND to StepResult.warn(Finding.BATTERY_OPTIMIZED))))
        assertEquals(Cause.BATTERY, cause(run(RepairStep.BACKGROUND to StepResult.fail(Finding.BACKGROUND_RESTRICTED))))
    }

    @Test
    fun batteryIsNotTheCauseOfAnotherFailure() {
        val results = run(
            RepairStep.BACKGROUND to StepResult.warn(Finding.BATTERY_OPTIMIZED),
            RepairStep.SITES_VPN to StepResult.fail(Finding.VPN_SITES_DOWN),
        )
        val verdict = RepairVerdict.of(results)
        assertTrue(verdict is RepairVerdict.Unknown)
        assertEquals(false, (verdict as RepairVerdict.Unknown).nothingFound)
    }

    // ------------------------------------------------------------------------------------------------ fixed / unknown

    @Test
    fun fixedWhenEveryProblemWasFixed() {
        val fixed = StepResult(StepStatus.FIXED, fix = FixKind.DIRECT_DNS_WHITELISTED)
        val verdict = RepairVerdict.of(run(RepairStep.DNS_DIRECT to fixed))
        assertEquals(RepairVerdict.Fixed(listOf(RepairStep.DNS_DIRECT to FixKind.DIRECT_DNS_WHITELISTED)), verdict)
    }

    @Test
    fun aKnownCauseWinsOverAFixAndKeepsIt() {
        val fixed = StepResult(StepStatus.FIXED, fix = FixKind.SUBSCRIPTION_UPDATED)
        val verdict = RepairVerdict.of(
            run(RepairStep.SUBSCRIPTION to fixed, RepairStep.CLOCK to StepResult.fail(Finding.CLOCK_OFF))
        )
        assertEquals(RepairVerdict.CauseFound(Cause.CLOCK, listOf(RepairStep.SUBSCRIPTION to FixKind.SUBSCRIPTION_UPDATED)), verdict)
    }

    @Test
    fun unknownWhenServersAnswerButSitesDoNotOpen() {
        val verdict = RepairVerdict.of(run(RepairStep.SITES_VPN to StepResult.fail(Finding.VPN_SITES_DOWN)))
        assertEquals(RepairVerdict.Unknown(nothingFound = false), verdict)
    }

    @Test
    fun unknownWithAFixThatDidNotHelpEverything() {
        val fixed = StepResult(StepStatus.FIXED, fix = FixKind.MTU_LOWERED)
        val verdict = RepairVerdict.of(
            run(RepairStep.DNS_DIRECT to fixed, RepairStep.SITES_VPN to StepResult.fail(Finding.VPN_SITES_DOWN))
        )
        assertEquals(RepairVerdict.Unknown(nothingFound = false, listOf(RepairStep.DNS_DIRECT to FixKind.MTU_LOWERED)), verdict)
    }

    @Test
    fun unknownAndCleanWhenNothingWasFound() {
        assertEquals(RepairVerdict.Unknown(nothingFound = true), RepairVerdict.of(run()))
    }

    @Test
    fun whitelistsAloneAreNoProblem() {
        val verdict = RepairVerdict.of(run(RepairStep.WHITELISTS to StepResult.ok(Finding.WHITELISTS_FOUND)))
        assertEquals(RepairVerdict.Unknown(nothingFound = true), verdict)
    }

    @Test
    fun notCheckedIsNoProblem() {
        val verdict = RepairVerdict.of(
            run(
                RepairStep.DNS_VPN to StepResult.skipped(SkipReason.VPN_OFF),
                RepairStep.SITES_VPN to StepResult.skipped(SkipReason.VPN_OFF),
                RepairStep.CLOCK to StepResult.skipped(SkipReason.NO_TIME_SOURCE),
            )
        )
        assertEquals(RepairVerdict.Unknown(nothingFound = true), verdict)
    }

    @Test
    fun aDirectDnsFailureAloneIsNoKnownCause() {
        val verdict = RepairVerdict.of(run(RepairStep.DNS_DIRECT to StepResult.fail(Finding.DNS_NO_ANSWER)))
        assertEquals(RepairVerdict.Unknown(nothingFound = false), verdict)
    }
}
