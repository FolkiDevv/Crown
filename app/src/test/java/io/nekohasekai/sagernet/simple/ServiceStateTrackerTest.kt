package io.nekohasekai.sagernet.simple

import io.nekohasekai.sagernet.bg.BaseService.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** proxy-connection «UI process binding»: what SagerConnection stores outside `:bg` (touch point 16). */
class ServiceStateTrackerTest {

    private var stored = State.Idle
    private val tracker = ServiceStateTracker { stored = it }
    private val binding = Any()

    @Test
    fun bindingToARunningServiceStoresItsStateBeforeAnyChange() {
        // A new UI process while the VPN runs: no change is reported, the state comes from the bind.
        tracker.bound(binding) { State.Connected }
        assertEquals(State.Connected, stored)
        assertTrue(tracker.isLive())
    }

    @Test
    fun deathOfTheServiceProcessGivesIdle() {
        tracker.bound(binding) { State.Connected }
        tracker.lost(binding)
        assertEquals(State.Idle, stored)
        assertFalse(tracker.isLive())
    }

    @Test
    fun aChangeReportedDuringTheReadWins() {
        // The service reported Connected while the bind was still reading the older Connecting.
        tracker.bound(binding) {
            tracker.changed(binding, State.Connected)
            State.Connecting
        }
        assertEquals(State.Connected, stored)
    }

    @Test
    fun laterChangesAreStored() {
        tracker.bound(binding) { State.Connected }
        tracker.changed(binding, State.Stopping)
        tracker.changed(binding, State.Stopped)
        assertEquals(State.Stopped, stored)
    }

    @Test
    fun aFailedReadStoresNothingAndDoesNotVouch() {
        stored = State.Connected
        tracker.bound(binding) { throw IllegalStateException("dead object") }
        assertEquals(State.Connected, stored)
        assertFalse(tracker.isLive())
        tracker.bound(binding) { null }
        assertFalse(tracker.isLive())
    }

    @Test
    fun lettingABindingGoKeepsTheStateButNoLongerVouches() {
        val other = Any()
        tracker.bound(binding) { State.Connected }
        tracker.bound(other) { State.Connected }
        tracker.unbound(binding)
        assertTrue(tracker.isLive())
        tracker.unbound(other)
        assertEquals(State.Connected, stored)
        assertFalse(tracker.isLive())
    }

    @Test
    fun aLateReportOfABindingLetGoDoesNotMakeItLive() {
        tracker.bound(binding) { State.Connected }
        tracker.unbound(binding)
        // The callback is one-way: a change sent before the unregistration can still arrive.
        tracker.changed(binding, State.Stopping)
        assertEquals(State.Stopping, stored)
        assertFalse(tracker.isLive())
    }

    @Test
    fun aBindingThatDiesWhileItReadsIsNotLive() {
        tracker.bound(binding) {
            tracker.lost(binding)
            State.Connected
        }
        assertEquals(State.Idle, stored)
        assertFalse(tracker.isLive())
    }

    @Test
    fun aServiceStartedAgainAfterDeathIsReadAgain() {
        tracker.bound(binding) { State.Connected }
        tracker.lost(binding)
        // The framework binds again once Android brought the service back.
        tracker.bound(binding) { State.Connecting }
        assertEquals(State.Connecting, stored)
        assertTrue(tracker.isLive())
    }
}
