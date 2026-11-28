package io.nekohasekai.sagernet.simple.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRecheckTest {

    private val now = 5_000_000L

    private fun due(
        lastAt: Long,
        selectorRuns: Boolean = true,
        suspended: Boolean = false,
        hasNetwork: Boolean = true,
    ) = AutoRecheck.due(selectorRuns, suspended, hasNetwork, lastAt, now)

    @Test
    fun firstFailureAsks() {
        assertTrue(due(lastAt = 0L))
    }

    @Test
    fun notMoreOftenThanTheGap() {
        assertFalse(due(lastAt = now - 1_000L))
        assertFalse(due(lastAt = now - AutoRecheck.MIN_GAP_MS + 1))
        assertTrue(due(lastAt = now - AutoRecheck.MIN_GAP_MS))
    }

    /** Uptime restarts at a reboot: a request stored before it is later than now and must not block for long. */
    @Test
    fun aTimeFromBeforeARebootDoesNotCount() {
        assertTrue(due(lastAt = now + 10_000L))
    }

    @Test
    fun onlyWithARunningSelectorAndANetwork() {
        assertFalse(due(lastAt = 0L, selectorRuns = false))
        assertFalse(due(lastAt = 0L, suspended = true))
        assertFalse(due(lastAt = 0L, hasNetwork = false))
    }
}
