package io.nekohasekai.sagernet.simple.home

import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.simple.ConnectionClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeStatusTest {

    private fun phase(
        state: State,
        userStopping: Boolean = false,
        restartPending: Boolean = false,
        previous: ConnectionPhase = ConnectionPhase.OFF,
    ) = HomeStatus.phase(state, userStopping, restartPending, previous)

    @Test
    fun freshConnectGoesThroughConnectingToProtected() {
        assertEquals(ConnectionPhase.OFF, phase(State.Idle))
        assertEquals(ConnectionPhase.OFF, phase(State.Stopped))
        assertEquals(ConnectionPhase.CONNECTING, phase(State.Connecting))
        assertEquals(ConnectionPhase.PROTECTED, phase(State.Connected, previous = ConnectionPhase.CONNECTING))
    }

    @Test
    fun restartInPlaceIsReconnecting() {
        // Stopping of a restart nobody here asked for keeps "protected" until the next state tells
        assertEquals(ConnectionPhase.PROTECTED, phase(State.Stopping, previous = ConnectionPhase.PROTECTED))
        assertEquals(
            ConnectionPhase.RECONNECTING,
            phase(State.Stopped, restartPending = true, previous = ConnectionPhase.PROTECTED),
        )
        assertEquals(
            ConnectionPhase.RECONNECTING,
            phase(State.Connecting, restartPending = true, previous = ConnectionPhase.RECONNECTING),
        )
        assertEquals(ConnectionPhase.PROTECTED, phase(State.Connected, previous = ConnectionPhase.RECONNECTING))
    }

    @Test
    fun stopFromElsewhereEndsOff() {
        assertEquals(ConnectionPhase.PROTECTED, phase(State.Stopping, previous = ConnectionPhase.PROTECTED))
        assertEquals(ConnectionPhase.OFF, phase(State.Stopped, previous = ConnectionPhase.PROTECTED))
        // a cancelled or failed connect never showed "protected"
        assertEquals(ConnectionPhase.OFF, phase(State.Stopping, previous = ConnectionPhase.CONNECTING))
    }

    @Test
    fun userStopIsNeverReconnecting() {
        assertEquals(
            ConnectionPhase.DISCONNECTING,
            phase(State.Stopping, userStopping = true, previous = ConnectionPhase.PROTECTED),
        )
        assertEquals(ConnectionPhase.OFF, phase(State.Stopped, userStopping = true, restartPending = true))
        assertEquals(ConnectionPhase.CONNECTING, phase(State.Connecting, userStopping = true, restartPending = true))
    }

    @Test
    fun buttonStopsWhileConnectingConnectedOrReconnecting() {
        assertTrue(HomeStatus.buttonStops(ConnectionPhase.CONNECTING))
        assertTrue(HomeStatus.buttonStops(ConnectionPhase.PROTECTED))
        assertTrue(HomeStatus.buttonStops(ConnectionPhase.RECONNECTING))
        assertFalse(HomeStatus.buttonStops(ConnectionPhase.OFF))
        assertFalse(HomeStatus.buttonStops(ConnectionPhase.DISCONNECTING))
    }

    @Test
    fun serverNotRespondingNeedsRepeatedFailuresWhileConnected() {
        assertNull(HomeStatus.problem(null, connected = true, urlTestFailures = 1, autoSelectorExhausted = false))
        assertEquals(
            HomeProblem.ServerNotResponding,
            HomeStatus.problem(null, connected = true, urlTestFailures = 2, autoSelectorExhausted = false),
        )
        assertEquals(
            HomeProblem.ServerNotResponding,
            HomeStatus.problem(null, connected = true, urlTestFailures = 0, autoSelectorExhausted = true),
        )
        // failures of an earlier connection do not count once stopped
        assertNull(HomeStatus.problem(null, connected = false, urlTestFailures = 5, autoSelectorExhausted = true))
    }

    @Test
    fun lossShowsOnlyWhileNotConnected() {
        val lost = HomeProblem.ConnectionLost("boom")
        assertEquals(lost, HomeStatus.problem(lost, connected = false, urlTestFailures = 0, autoSelectorExhausted = false))
        assertNull(HomeStatus.problem(lost, connected = true, urlTestFailures = 0, autoSelectorExhausted = false))
        assertTrue(HomeStatus.isLoss("failed", userStopping = false))
        assertFalse(HomeStatus.isLoss("failed", userStopping = true))
        assertFalse(HomeStatus.isLoss(null, userStopping = false))
        assertFalse(HomeStatus.isLoss(" ", userStopping = false))
    }

    @Test
    fun durationFormat() {
        assertEquals("0:00", HomeStatus.formatDuration(0))
        assertEquals("0:00", HomeStatus.formatDuration(-5))
        assertEquals("1:05", HomeStatus.formatDuration(65_400))
        assertEquals("59:59", HomeStatus.formatDuration(3_599_999))
        assertEquals("1:00:00", HomeStatus.formatDuration(3_600_000))
        assertEquals("26:03:04", HomeStatus.formatDuration((26 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test
    fun connectionClockKeepsTheStartOnlyAcrossARestart() {
        assertEquals(500L, ConnectionClock.startedAt(stored = 0L, restarting = false, now = 500L))
        assertEquals(500L, ConnectionClock.startedAt(stored = 100L, restarting = false, now = 500L))
        assertEquals(100L, ConnectionClock.startedAt(stored = 100L, restarting = true, now = 500L))
        // left from an earlier boot: elapsedRealtime started again
        assertEquals(500L, ConnectionClock.startedAt(stored = 9_000L, restarting = true, now = 500L))
        assertEquals(500L, ConnectionClock.startedAt(stored = 0L, restarting = true, now = 500L))
        assertTrue(ConnectionClock.isValid(1L, 1L))
        assertFalse(ConnectionClock.isValid(0L, 10L))
        assertFalse(ConnectionClock.isValid(11L, 10L))
    }
}
