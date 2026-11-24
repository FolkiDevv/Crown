package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * «Починка» and the service around its fixes (design.md D8): the subscription updates it submits — every group, in
 * `:bg`'s FIFO queue — and the successor start after them are waited for before a rollback; a fix whose VPN does not
 * connect again is no fix; a reload owed after a rollback survives any cancelled wait and fires only on a connected
 * service. Real time with short limits.
 */
class RepairVpnSyncTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /** A service whose connects are counted; [reconnects]: whether a reload connects again. */
    private class FakeVpn(var isConnected: Boolean = true, var isOff: Boolean = false, var reconnects: Boolean = true) : RepairVpn {
        val connectCount = MutableStateFlow(0)
        val reloads = AtomicInteger()

        fun connect() {
            isConnected = true
            isOff = false
            connectCount.value++
        }

        override fun connected() = isConnected
        override fun off() = isOff
        override fun connects() = connectCount.value
        override suspend fun awaitConnected(after: Int, timeoutMs: Long) =
            withTimeoutOrNull(timeoutMs) { connectCount.first { it > after }; isConnected } ?: false

        override fun sendReload() {
            reloads.incrementAndGet()
            if (reconnects) connect() else isConnected = false
        }
    }

    /**
     * `:bg`'s subscription queue: one job at a time, first in first out; [submit] returns once the job is queued
     * (after [submitDelayMs], a slow binder), [barrier] is a queued no-op that returns once it ran.
     */
    private inner class FakeQueue(private val jobMs: (Long) -> Long, private val submitDelayMs: Long = 0L) {
        private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val done: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        init {
            scope.launch { for (job in jobs) job() }
        }

        suspend fun submit(gid: Long) {
            delay(submitDelayMs)
            jobs.send {
                delay(jobMs(gid))
                done += gid
            }
        }

        suspend fun barrier() {
            val ran = CompletableDeferred<Unit>()
            jobs.send { ran.complete(Unit) }
            ran.await()
        }
    }

    private fun wait(queue: FakeQueue, vpn: RepairVpn, exists: (Long) -> Boolean = { true }) = SubscriptionWait(
        submit = queue::submit,
        barrier = queue::barrier,
        profileExists = exists,
        vpn = vpn,
        successorMs = 1_500L,
    )

    @Test
    fun everySubmittedGroupIsThroughBeforeTheWaitEnds() = runBlocking {
        // Two groups with independent durations, one far longer than any fixed limit the wait could have had.
        val queue = FakeQueue(jobMs = { gid -> if (gid == 7L) 1_200L else 100L })
        val done = wait(queue, FakeVpn()).await(listOf(7L, 8L), running = null)
        assertTrue(done)
        assertEquals(listOf(7L, 8L), queue.done.toList())
    }

    @Test
    fun aSlowSubmissionIsStillWaitedFor() = runBlocking {
        // The request reaches the queue late (a slow binder): the barrier is only queued after it.
        val queue = FakeQueue(jobMs = { 50L }, submitDelayMs = 400L)
        assertTrue(wait(queue, FakeVpn()).await(listOf(1L, 2L, 3L), running = null))
        assertEquals(listOf(1L, 2L, 3L), queue.done.toList())
    }

    @Test
    fun aQueueThatCannotBeReachedIsNoFinishedUpdate() = runBlocking {
        val wait = SubscriptionWait(
            submit = { throw IllegalStateException("cannot bind core service") },
            barrier = { },
            profileExists = { true },
            vpn = FakeVpn(),
        )
        assertFalse(wait.await(listOf(1L), running = null))
    }

    @Test
    fun theSuccessorStartOfAReplacedRunningProfileIsWaitedFor() = runBlocking {
        // The update deleted the running profile 5 and stopped the service; `:bg` starts the successor afterwards in
        // a coroutine of its own, after its queue job is through.
        val vpn = FakeVpn()
        var successorStartedAt = 0L
        val queue = FakeQueue(jobMs = { 100L })
        val started = System.nanoTime()
        val wait = SubscriptionWait(
            submit = { gid ->
                queue.submit(gid)
                scope.launch {
                    delay(150)
                    vpn.isConnected = false
                    delay(500)
                    successorStartedAt = System.nanoTime()
                    vpn.connect()
                }
            },
            barrier = queue::barrier,
            profileExists = { it != 5L },
            vpn = vpn,
            successorMs = 2_000L,
        )
        assertTrue(wait.await(listOf(1L), running = 5L))
        val returned = System.nanoTime()
        assertTrue("returned before the successor started", successorStartedAt in (started + 1)..returned)
    }

    /** The selected server; [putBack]s are recorded. */
    private class Selection(@Volatile var value: Long = 9L) {
        val putBacks: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    }

    private fun fix(vpn: FakeVpn, selection: Selection = Selection(), moveTo: Long? = null) = SubscriptionUpdateFix(
        groups = listOf(1L),
        wait = if (moveTo == null) {
            wait(FakeQueue(jobMs = { 50L }), vpn)
        } else {
            // An update that moves the selection (its running profile replaced by a successor).
            SubscriptionWait(submit = { selection.value = moveTo }, barrier = { }, profileExists = { true }, vpn = vpn)
        },
        vpn = vpn,
        reloader = VpnReloader(vpn, reconnectMs = 400L, restartShowMs = 10L),
        running = { 9L },
        reloadAllowed = { true },
        selection = { selection.value },
        putBack = { saved ->
            selection.putBacks += saved
            selection.value = saved
        },
    )

    @Test
    fun aSubscriptionFixReloadsTheRunningVpnBeforeTheRecheck() = runBlocking {
        val vpn = FakeVpn()
        fix(vpn).apply()
        assertEquals(1, vpn.reloads.get())
    }

    @Test
    fun theSelectionTheUpdateMovedGoesBackUnlessTheUserChoseAnotherSince() = runBlocking {
        // The update moved the selection 9 → 10; nothing else changed it: the rollback puts 9 back.
        val moved = Selection()
        val first = fix(FakeVpn(), moved, moveTo = 10L)
        first.apply()
        assertEquals(10L, moved.value)
        assertTrue(first.stillApplied())
        first.restore()
        assertEquals(9L, moved.value)
        assertEquals(listOf(9L), moved.putBacks.toList())
        // The user chose 11 while the update ran: the rollback keeps it (compare and set).
        val chosen = Selection()
        val second = fix(FakeVpn(), chosen, moveTo = 10L)
        second.apply()
        chosen.value = 11L
        assertFalse(second.stillApplied())
        second.restore()
        assertEquals(11L, chosen.value)
        assertTrue(chosen.putBacks.isEmpty())
        // An update that did not move it puts nothing back; its content is no undoable effect.
        val still = Selection()
        val third = fix(FakeVpn(), still)
        third.apply()
        third.restore()
        assertTrue(still.putBacks.isEmpty())
        assertFalse(third.reversible)
    }

    @Test
    fun aSubscriptionFixWhoseVpnDoesNotConnectFailsAndIsPutBack() = runBlocking {
        // The service is starting and never connects: the fix must not count; the engine puts it back.
        val vpn = FakeVpn(isConnected = false)
        val subscriptionFix = fix(vpn)
        var applied = false
        val checks = object : RepairChecks {
            // The recheck would pass: only the VPN that did not connect makes the fix fail.
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult =
                if (step == RepairStep.SUBSCRIPTION && !applied) StepResult.fail(Finding.SUBSCRIPTION_EXPIRED) else StepResult.ok()

            override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>) =
                if (step == RepairStep.SUBSCRIPTION) listOf(object : RepairFix by subscriptionFix {
                    override suspend fun apply() {
                        applied = true
                        subscriptionFix.apply()
                    }
                }) else emptyList()
        }
        val result = RepairEngine(checks, timeoutMs = { 10_000L }, fixTimeoutMs = 10_000L).run { }.results
            .getValue(RepairStep.SUBSCRIPTION)
        assertEquals(StepStatus.FAIL, result.status)
        assertNull(result.fix)
        assertEquals(0, vpn.reloads.get())
    }

    @Test
    fun aSubscriptionFixThatLeftTheUsersVpnOffIsNoFix() = runBlocking {
        // The update deleted the running profile, no successor took over: the service is off afterwards.
        val vpn = FakeVpn()
        val queue = FakeQueue(jobMs = { 50L })
        val subscriptionFix = SubscriptionUpdateFix(
            groups = listOf(1L),
            wait = SubscriptionWait(
                submit = { gid ->
                    queue.submit(gid)
                    vpn.isConnected = false
                    vpn.isOff = true
                },
                barrier = queue::barrier,
                profileExists = { false },
                vpn = vpn,
                successorMs = 200L,
            ),
            vpn = vpn,
            reloader = VpnReloader(vpn, reconnectMs = 200L, restartShowMs = 10L),
            running = { 9L },
            reloadAllowed = { true },
            selection = { 9L },
            putBack = { },
        )
        // The update itself ran through (the queue drained): the failure is the stopped VPN, nothing else.
        val failure = try {
            subscriptionFix.apply()
            null
        } catch (e: IllegalStateException) {
            e.message
        }
        assertEquals("the update stopped the VPN", failure)
        assertEquals(listOf(1L), queue.done.toList())
        assertEquals(0, vpn.reloads.get())
    }

    @Test
    fun aReloadThatDoesNotReconnectIsReported() = runBlocking {
        val vpn = FakeVpn(reconnects = false)
        assertFalse(VpnReloader(vpn, reconnectMs = 200L).reload("MTU changed"))
        val off = FakeVpn(isConnected = false)
        assertFalse(VpnReloader(off, reconnectMs = 200L, restartShowMs = 10L).reloadWhenConnected("subscriptions updated"))
        assertEquals(0, off.reloads.get())
    }

    // ------------------------------------------------------------------------------------------------ owed reload

    private fun pending(phase: () -> ServicePhase, reloads: AtomicInteger) =
        PendingReload(phase, { reloads.incrementAndGet() }, scope, pollMs = 20L, lifetimeMs = 5_000L)

    @Test
    fun aCancelledWaitDoesNotLoseTheOwedReloadAndItGoesOnlyOnceConnected() = runBlocking {
        // A value was put back while the VPN was starting with the replaced one; the run waits (settle) and the user
        // stops it during that wait.
        var phase = ServicePhase.STARTING
        val reloads = AtomicInteger()
        val pending = pending({ phase }, reloads)
        val owed = pending.arm()
        val waiter = launch { owed.await(60_000L) }
        delay(100)
        waiter.cancel()
        delay(200)
        assertEquals("never during the start", 0, reloads.get())
        assertTrue(pending.armed)
        phase = ServicePhase.CONNECTED
        assertEquals(ReloadOutcome.SENT, owed.await(2_000L))
        assertEquals(1, reloads.get())
        assertFalse(pending.armed)
    }

    @Test
    fun armingTwiceOwesOneReload() = runBlocking {
        var phase = ServicePhase.STARTING
        val reloads = AtomicInteger()
        val pending = pending({ phase }, reloads)
        val first = pending.arm()
        val second = pending.arm()
        phase = ServicePhase.CONNECTED
        assertEquals(ReloadOutcome.SENT, first.await(2_000L))
        assertEquals(ReloadOutcome.SENT, second.await(2_000L))
        assertEquals(1, reloads.get())
    }

    @Test
    fun theOwedReloadRecordsTheConnectsAtItsSend() = runBlocking {
        val vpn = FakeVpn()
        vpn.connect()
        vpn.connect()
        val reloads = AtomicInteger()
        val owed = pending({ ServicePhase.CONNECTED }, reloads).arm { vpn.connects() }
        assertEquals(ReloadOutcome.SENT, owed.await(2_000L))
        assertEquals(2, owed.connectsAtSend)
    }

    @Test
    fun anOwedReloadIsDroppedWhenTheServiceGoesOffOrIsDisarmed() = runBlocking {
        var phase = ServicePhase.STARTING
        val reloads = AtomicInteger()
        val pending = pending({ phase }, reloads)
        val off = pending.arm()
        phase = ServicePhase.OFF
        assertEquals("the next start reads the restored values", ReloadOutcome.DROPPED, off.await(2_000L))
        phase = ServicePhase.STARTING
        val stopped = pending.arm()
        pending.disarm()
        phase = ServicePhase.CONNECTED
        assertEquals("the VPN «Починка» started is being stopped", ReloadOutcome.DROPPED, stopped.await(2_000L))
        delay(200)
        assertEquals(0, reloads.get())
        assertFalse(pending.armed)
    }
}
