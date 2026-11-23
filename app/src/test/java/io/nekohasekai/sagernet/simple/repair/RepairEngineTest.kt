package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class RepairEngineTest {

    /** Records the order of the checks it runs; [results] overrides the default OK. */
    private class FakeChecks(val results: Map<RepairStep, StepResult> = emptyMap()) : RepairChecks {
        val ran = mutableListOf<RepairStep>()
        val seen = mutableMapOf<RepairStep, Set<RepairStep>>()

        override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
            ran += step
            seen[step] = done.keys.toSet()
            return results[step] ?: StepResult.ok()
        }
    }

    private fun engine(checks: RepairChecks, timeout: Long = 5_000L) = RepairEngine(checks, timeoutMs = { timeout })

    @Test
    fun runsEveryCheckInOrderWithTheEarlierResults() = runBlocking {
        val checks = FakeChecks()
        val progress = engine(checks).run { }
        assertEquals(RepairPlan.ORDER, checks.ran)
        RepairPlan.ORDER.forEachIndexed { i, step -> assertEquals(RepairPlan.ORDER.take(i).toSet(), checks.seen[step]) }
        assertTrue(progress.finished)
        assertTrue(progress.results.values.all { it.status == StepStatus.OK })
    }

    @Test
    fun aCheckBlockedByItsPrerequisiteIsNotRunAndNotAnError() = runBlocking {
        val checks = FakeChecks(mapOf(RepairStep.INTERNET to StepResult.fail(Finding.NO_NETWORK)))
        val progress = engine(checks).run { }
        assertEquals(
            listOf(RepairStep.INTERNET, RepairStep.PRIVATE_DNS_VPN, RepairStep.SUBSCRIPTION, RepairStep.BACKGROUND),
            checks.ran,
        )
        val results = progress.results
        for (step in listOf(RepairStep.WHITELISTS, RepairStep.CLOCK, RepairStep.DNS_DIRECT, RepairStep.SERVERS,
            RepairStep.DNS_VPN, RepairStep.SITES_VPN, RepairStep.RU_DIRECT)) {
            assertEquals(step.name, StepStatus.SKIPPED, results.getValue(step).status)
            assertEquals(step.name, SkipReason.NO_INTERNET, results.getValue(step).skip)
        }
    }

    @Test
    fun noWorkingServerMarksTheVpnChecksNotChecked() = runBlocking {
        val checks = FakeChecks(mapOf(RepairStep.SERVERS to StepResult.fail(Finding.NO_SERVER_ANSWERS)))
        val results = engine(checks).run { }.results
        assertFalse(RepairStep.SITES_VPN in checks.ran)
        assertEquals(SkipReason.NO_WORKING_SERVER, results.getValue(RepairStep.SITES_VPN).skip)
        assertEquals(SkipReason.NO_WORKING_SERVER, results.getValue(RepairStep.DNS_VPN).skip)
        assertTrue(RepairStep.RU_DIRECT in checks.ran)
    }

    @Test
    fun publishesTheRunningCheckAndThenItsResult() = runBlocking {
        val seen = mutableListOf<Pair<RepairStep?, Int>>()
        engine(FakeChecks()).run { p -> seen += p.current to p.results.size }
        // Initial, then for each check: running, done.
        assertEquals(1 + 2 * RepairPlan.ORDER.size, seen.size)
        assertEquals(null to 0, seen.first())
        assertEquals(RepairStep.INTERNET to 0, seen[1])
        assertEquals(null to 1, seen[2])
        assertEquals(null to RepairPlan.ORDER.size, seen.last())
    }

    @Test
    fun aCheckThatThrowsIsNotCheckedAndTheRunGoesOn() = runBlocking {
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
                if (step == RepairStep.CLOCK) error("boom")
                return StepResult.ok()
            }
        }
        val results = engine(checks).run { }.results
        assertEquals(StepStatus.SKIPPED, results.getValue(RepairStep.CLOCK).status)
        assertEquals(SkipReason.ERROR, results.getValue(RepairStep.CLOCK).skip)
        assertEquals(StepStatus.OK, results.getValue(RepairStep.BACKGROUND).status)
    }

    @Test
    fun aCheckOverItsTimeLimitIsNotChecked() = runBlocking {
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
                if (step == RepairStep.SUBSCRIPTION) awaitCancellation()
                return StepResult.ok()
            }
        }
        val results = engine(checks, timeout = 50L).run { }.results
        assertEquals(SkipReason.TIMED_OUT, results.getValue(RepairStep.SUBSCRIPTION).skip)
        assertEquals(StepStatus.OK, results.getValue(RepairStep.SERVERS).status)
    }

    @Test
    fun stopEndsTheRunAndMarksTheRestStopped() = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val ran = mutableListOf<RepairStep>()
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
                ran += step
                if (step == RepairStep.SERVERS) {
                    reached.complete(Unit)
                    awaitCancellation()
                }
                return StepResult.ok()
            }
        }
        var last: RepairProgress? = null
        val run = async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
            RepairEngine(checks, timeoutMs = { 60_000L }).run { last = it }
        }
        reached.await()
        run.cancel()
        val stopped = try {
            run.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        assertEquals(RepairStep.SERVERS, ran.last())
        val results = last!!.results
        assertEquals(RepairPlan.ORDER.size, results.size)
        assertEquals(StepStatus.OK, results.getValue(RepairStep.SUBSCRIPTION).status)
        for (step in listOf(RepairStep.SERVERS, RepairStep.DNS_VPN, RepairStep.SITES_VPN, RepairStep.RU_DIRECT, RepairStep.BACKGROUND)) {
            assertEquals(step.name, SkipReason.STOPPED, results.getValue(step).skip)
        }
    }

    @Test
    fun recordsWhenAndHowLongEachCheckRan() = runBlocking {
        var clock = 1_000L
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
                clock += 10
                return StepResult.ok()
            }
        }
        val results = RepairEngine(checks, now = { clock }).run { }.results
        assertEquals(1_000L, results.getValue(RepairStep.INTERNET).startedAt)
        assertEquals(10L, results.getValue(RepairStep.INTERNET).elapsedMs)
        assertEquals(1_010L, results.getValue(RepairStep.WHITELISTS).startedAt)
    }
}
