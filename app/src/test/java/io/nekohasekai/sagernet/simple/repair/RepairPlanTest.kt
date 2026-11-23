package io.nekohasekai.sagernet.simple.repair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairPlanTest {

    private val ok = StepResult.ok()

    private fun allOk(): MutableMap<RepairStep, StepResult> = RepairPlan.ORDER.associateWith { ok }.toMutableMap()

    // ------------------------------------------------------------------------------------------------ the table

    @Test
    fun elevenChecksInTheOrderOfTheSpec() {
        assertEquals(
            listOf(
                RepairStep.INTERNET,
                RepairStep.WHITELISTS,
                RepairStep.CLOCK,
                RepairStep.PRIVATE_DNS_VPN,
                RepairStep.DNS_DIRECT,
                RepairStep.SUBSCRIPTION,
                RepairStep.SERVERS,
                RepairStep.DNS_VPN,
                RepairStep.SITES_VPN,
                RepairStep.RU_DIRECT,
                RepairStep.BACKGROUND,
            ),
            RepairPlan.ORDER,
        )
    }

    @Test
    fun theDependencyTable() {
        val expected = mapOf(
            RepairStep.INTERNET to emptySet(),
            RepairStep.WHITELISTS to setOf(RepairStep.INTERNET),
            RepairStep.CLOCK to setOf(RepairStep.INTERNET),
            RepairStep.PRIVATE_DNS_VPN to emptySet(),
            RepairStep.DNS_DIRECT to setOf(RepairStep.INTERNET),
            RepairStep.SUBSCRIPTION to emptySet(),
            RepairStep.SERVERS to setOf(RepairStep.INTERNET),
            RepairStep.DNS_VPN to setOf(RepairStep.SERVERS, RepairStep.PRIVATE_DNS_VPN),
            RepairStep.SITES_VPN to setOf(RepairStep.SERVERS, RepairStep.PRIVATE_DNS_VPN),
            RepairStep.RU_DIRECT to setOf(RepairStep.INTERNET),
            RepairStep.BACKGROUND to emptySet(),
        )
        assertEquals(expected, RepairPlan.REQUIRES)
    }

    @Test
    fun everyPrerequisiteComesEarlier() {
        for ((step, requires) in RepairPlan.REQUIRES) {
            for (prerequisite in requires) {
                assertTrue(
                    "$prerequisite must run before $step",
                    RepairPlan.ORDER.indexOf(prerequisite) < RepairPlan.ORDER.indexOf(step),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ skip reasons

    @Test
    fun nothingIsSkippedWhenEverythingPassed() {
        val done = allOk()
        for (step in RepairPlan.ORDER) assertNull(step.name, RepairPlan.skipReason(step, done))
    }

    @Test
    fun withoutInternetEveryCheckThatNeedsItIsNotChecked() {
        val done = mutableMapOf(RepairStep.INTERNET to StepResult.fail(Finding.NO_NETWORK))
        for (step in RepairPlan.ORDER.drop(1)) {
            val expected = if (RepairPlan.REQUIRES.getValue(step).isEmpty()) null else SkipReason.NO_INTERNET
            assertEquals(step.name, expected, RepairPlan.skipReason(step, done))
            // What the engine records for a skipped check feeds the next ones.
            done[step] = expected?.let { StepResult.skipped(it) } ?: ok
        }
    }

    @Test
    fun theChecksWithoutDependenciesRunWithoutInternet() {
        val done = mapOf(RepairStep.INTERNET to StepResult.fail(Finding.NOTHING_ANSWERS))
        assertNull(RepairPlan.skipReason(RepairStep.PRIVATE_DNS_VPN, done))
        assertNull(RepairPlan.skipReason(RepairStep.SUBSCRIPTION, done + (RepairStep.PRIVATE_DNS_VPN to ok)))
        assertNull(RepairPlan.skipReason(RepairStep.BACKGROUND, done))
    }

    @Test
    fun noWorkingServerSkipsTheChecksThroughTheVpnNotAsErrors() {
        val done = allOk()
        done[RepairStep.SERVERS] = StepResult.fail(Finding.NO_SERVER_ANSWERS)
        assertEquals(SkipReason.NO_WORKING_SERVER, RepairPlan.skipReason(RepairStep.DNS_VPN, done))
        assertEquals(SkipReason.NO_WORKING_SERVER, RepairPlan.skipReason(RepairStep.SITES_VPN, done))
        // The direct checks after it do not depend on the servers.
        assertNull(RepairPlan.skipReason(RepairStep.RU_DIRECT, done))
        assertNull(RepairPlan.skipReason(RepairStep.BACKGROUND, done))
    }

    @Test
    fun noServersAtAllIsNoWorkingServerToo() {
        val done = allOk()
        done[RepairStep.SERVERS] = StepResult.fail(Finding.NO_SERVERS)
        assertEquals(SkipReason.NO_WORKING_SERVER, RepairPlan.skipReason(RepairStep.SITES_VPN, done))
    }

    @Test
    fun aServerCheckWithWarningsLetsTheVpnChecksRun() {
        val done = allOk()
        done[RepairStep.SERVERS] = StepResult.warn(Finding.CURRENT_SERVER_DOWN)
        assertNull(RepairPlan.skipReason(RepairStep.DNS_VPN, done))
        assertNull(RepairPlan.skipReason(RepairStep.SITES_VPN, done))
    }

    @Test
    fun anotherVpnSkipsTheChecksThroughTheVpn() {
        val done = allOk()
        done[RepairStep.PRIVATE_DNS_VPN] = StepResult.fail(Finding.OTHER_VPN)
        assertEquals(SkipReason.OTHER_VPN, RepairPlan.skipReason(RepairStep.DNS_VPN, done))
        assertEquals(SkipReason.OTHER_VPN, RepairPlan.skipReason(RepairStep.SITES_VPN, done))
        assertNull(RepairPlan.skipReason(RepairStep.RU_DIRECT, done))
    }

    @Test
    fun anotherVpnComesBeforeAMissingServer() {
        val done = allOk()
        done[RepairStep.PRIVATE_DNS_VPN] = StepResult.fail(Finding.OTHER_VPN)
        done[RepairStep.SERVERS] = StepResult.fail(Finding.NO_SERVER_ANSWERS)
        assertEquals(SkipReason.OTHER_VPN, RepairPlan.skipReason(RepairStep.SITES_VPN, done))
    }

    @Test
    fun aPrivateDnsAloneBlocksNothing() {
        val done = allOk()
        done[RepairStep.PRIVATE_DNS_VPN] = StepResult.fail(Finding.PRIVATE_DNS)
        assertNull(RepairPlan.skipReason(RepairStep.DNS_VPN, done))
        assertNull(RepairPlan.skipReason(RepairStep.SITES_VPN, done))
    }

    @Test
    fun aSkippedPrerequisitePassesItsReasonOn() {
        val done = allOk()
        done[RepairStep.SERVERS] = StepResult.skipped(SkipReason.TESTS_BUSY)
        assertEquals(SkipReason.TESTS_BUSY, RepairPlan.skipReason(RepairStep.DNS_VPN, done))
    }

    @Test
    fun noInternetWinsOverEverythingElse() {
        val done = allOk()
        done[RepairStep.INTERNET] = StepResult.fail(Finding.NO_NETWORK)
        done[RepairStep.PRIVATE_DNS_VPN] = StepResult.fail(Finding.OTHER_VPN)
        done[RepairStep.SERVERS] = StepResult.skipped(SkipReason.NO_INTERNET)
        assertEquals(SkipReason.NO_INTERNET, RepairPlan.skipReason(RepairStep.SITES_VPN, done))
    }

    @Test
    fun onlyFailedAndSkippedResultsBlock() {
        assertTrue(StepStatus.OK.passed)
        assertTrue(StepStatus.WARN.passed)
        assertTrue(StepStatus.FIXED.passed)
        assertTrue(!StepStatus.FAIL.passed)
        assertTrue(!StepStatus.SKIPPED.passed)
    }
}
