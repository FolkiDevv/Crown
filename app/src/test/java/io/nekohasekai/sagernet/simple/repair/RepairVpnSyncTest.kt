package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * «Починка» and the service around its fixes (design.md D8): the subscription update and the successor start `:bg`
 * makes after it are waited for before a rollback; a fix whose VPN does not connect again is no fix; a reload owed by
 * a finished run fires on the next connected state. Real time with short limits.
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

    /** The `:bg` queue: [run] plays what a refresh does after it was asked for. */
    private class FakeQueue {
        val states = MutableStateFlow<Map<Long, Int>>(emptyMap())
        val stamps = ConcurrentHashMap<Long, Long>()
        @Volatile var finishedAt = 0L

        // Atomic: the updates of several groups run at once, like the callbacks of the real queue.
        fun set(gid: Long, state: Int?) {
            states.update { if (state == null) it - gid else it + (gid to state) }
        }
    }

    private fun wait(queue: FakeQueue, vpn: RepairVpn, request: (Long) -> Unit, exists: (Long) -> Boolean = { true }) =
        SubscriptionWait(
            states = queue.states,
            request = request,
            lastUpdate = { queue.stamps[it] ?: 0L },
            profileExists = exists,
            vpn = vpn,
            startMs = 300L,
            perGroupMs = 1_000L,
            maxMs = 2_000L,
            successorMs = 1_500L,
        )

    @Test
    fun aDelayedQueueIsWaitedForUntilEveryUpdateIsThrough() = runBlocking {
        val queue = FakeQueue()
        val vpn = FakeVpn()
        val done = wait(queue, vpn, request = { gid ->
            scope.launch {
                delay(100)
                queue.set(gid, 1)
                delay(150)
                queue.set(gid, 2)
                delay(250)
                queue.stamps[gid] = 42L
                queue.finishedAt = System.nanoTime()
                queue.set(gid, null)
            }
        }).await(listOf(7L, 8L), running = null)
        assertTrue(done)
        assertTrue(queue.finishedAt > 0L)
        assertEquals(emptyMap<Long, Int>(), queue.states.value)
    }

    @Test
    fun anUpdateTooQuickToBeSeenCountsByItsLastUpdateTime() = runBlocking {
        val queue = FakeQueue()
        val done = wait(queue, FakeVpn(), request = { gid -> queue.stamps[gid] = 1L }).await(listOf(3L), running = null)
        assertTrue(done)
    }

    @Test
    fun anUpdateThatNeverRanOrDoesNotFinishIsNoFinishedUpdate() = runBlocking {
        val queue = FakeQueue()
        assertFalse(wait(queue, FakeVpn(), request = { }).await(listOf(3L), running = null))
        val stuck = FakeQueue()
        assertFalse(wait(stuck, FakeVpn(), request = { gid -> stuck.set(gid, 2) }).await(listOf(3L), running = null))
    }

    @Test
    fun theSuccessorStartOfAReplacedRunningProfileIsWaitedFor() = runBlocking {
        // The update deleted the running profile 5: `:bg` stopped the service and starts the successor afterwards, in
        // a coroutine of its own, after the queue already reported the update through.
        val queue = FakeQueue()
        val vpn = FakeVpn()
        var successorStartedAt = 0L
        val started = System.nanoTime()
        val done = wait(queue, vpn, exists = { it != 5L }, request = { gid ->
            scope.launch {
                queue.set(gid, 2)
                delay(100)
                vpn.isConnected = false
                queue.stamps[gid] = 1L
                queue.set(gid, null)
                delay(600)
                successorStartedAt = System.nanoTime()
                vpn.connect()
            }
        }).await(listOf(1L), running = 5L)
        val returned = System.nanoTime()
        assertTrue(done)
        assertTrue("returned before the successor started", successorStartedAt in (started + 1)..returned)
    }

    private fun fix(queue: FakeQueue, vpn: FakeVpn, restored: MutableList<String>) = SubscriptionUpdateFix(
        groups = listOf(1L),
        wait = wait(queue, vpn, request = { gid ->
            scope.launch {
                queue.set(gid, 2)
                delay(100)
                queue.stamps[gid] = 1L
                queue.set(gid, null)
            }
        }),
        vpn = vpn,
        reloader = VpnReloader(vpn, reconnectMs = 400L, restartShowMs = 10L),
        running = { 9L },
        reloadAllowed = { true },
        restoreSelection = { restored += "selection" },
    )

    @Test
    fun aSubscriptionFixReloadsTheRunningVpnBeforeTheRecheck() = runBlocking {
        val vpn = FakeVpn()
        fix(FakeQueue(), vpn, mutableListOf()).apply()
        assertEquals(1, vpn.reloads.get())
    }

    @Test
    fun aSubscriptionFixWhoseVpnDoesNotConnectFailsAndIsPutBack() = runBlocking {
        // The service is starting and never connects: the fix must not count, the engine puts it back.
        val vpn = FakeVpn(isConnected = false)
        val restored = mutableListOf<String>()
        val subscriptionFix = fix(FakeQueue(), vpn, restored)
        // The recheck would pass: only the VPN that did not connect makes the fix fail.
        var applied = false
        val failing = object : RepairChecks {
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
        val result = RepairEngine(failing, timeoutMs = { 10_000L }, fixTimeoutMs = 10_000L).run { }.results
            .getValue(RepairStep.SUBSCRIPTION)
        assertEquals(StepStatus.FAIL, result.status)
        assertEquals(listOf("selection"), restored)
        assertEquals(0, vpn.reloads.get())
    }

    @Test
    fun aSubscriptionFixThatLeftTheUsersVpnOffIsNoFix() = runBlocking {
        // The update deleted the running profile and no successor took over: the service is off afterwards.
        val vpn = FakeVpn()
        val queue = FakeQueue()
        val subscriptionFix = SubscriptionUpdateFix(
            groups = listOf(1L),
            wait = wait(queue, vpn, exists = { false }, request = { gid ->
                scope.launch {
                    queue.set(gid, 2)
                    delay(50)
                    vpn.isConnected = false
                    vpn.isOff = true
                    queue.stamps[gid] = 1L
                    queue.set(gid, null)
                }
            }),
            vpn = vpn,
            reloader = VpnReloader(vpn, reconnectMs = 200L, restartShowMs = 10L),
            running = { 9L },
            reloadAllowed = { true },
            restoreSelection = { },
        )
        val failed = try {
            subscriptionFix.apply()
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue(failed)
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

    @Test
    fun anOwedReloadFiresOnceTheServiceConnectsAfterTheRunGaveUpWaiting() = runBlocking {
        // The run waited for a start in flight and gave up; the start connects later, with the replaced value.
        var phase = ServicePhase.STARTING
        val reloads = AtomicInteger()
        val pending = PendingReload({ phase }, { reloads.incrementAndGet() }, scope, pollMs = 20L, lifetimeMs = 5_000L)
        pending.arm()
        pending.arm()
        delay(300)
        assertEquals("never during the start", 0, reloads.get())
        phase = ServicePhase.CONNECTED
        withTimeoutOrNull(2_000L) { while (pending.armed) delay(20) }
        assertEquals(1, reloads.get())
        assertFalse(pending.armed)
    }

    @Test
    fun anOwedReloadIsDroppedWhenTheServiceGoesOff() = runBlocking {
        var phase = ServicePhase.STARTING
        val reloads = AtomicInteger()
        val pending = PendingReload({ phase }, { reloads.incrementAndGet() }, scope, pollMs = 20L, lifetimeMs = 5_000L)
        pending.arm()
        delay(100)
        phase = ServicePhase.OFF
        withTimeoutOrNull(2_000L) { while (pending.armed) delay(20) }
        phase = ServicePhase.CONNECTED
        delay(200)
        assertEquals("the next start reads the restored values", 0, reloads.get())
    }
}
