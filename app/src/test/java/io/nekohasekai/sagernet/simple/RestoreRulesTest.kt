package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRulesTest {

    private val minute = 60_000L

    @Test
    fun markerIsNeverZero() {
        assertEquals(1L, RestoreRules.marker(0))
        assertEquals(43L, RestoreRules.marker(42))
        assertEquals(RestoreRules.ANY_BOOT, RestoreRules.marker(null))
        assertEquals(RestoreRules.ANY_BOOT, RestoreRules.marker(-1))
    }

    @Test
    fun wantedOnlyInTheBootItWasStarted() {
        assertFalse(RestoreRules.isWanted(0L, 5L))
        assertTrue(RestoreRules.isWanted(5L, 5L))
        // The device restarted since: the connection was not lost, nothing is restored or reported.
        assertFalse(RestoreRules.isWanted(5L, 6L))
        assertTrue(RestoreRules.isWanted(RestoreRules.ANY_BOOT, 6L))
        assertTrue(RestoreRules.isWanted(5L, RestoreRules.ANY_BOOT))
        assertFalse(RestoreRules.isWanted(0L, RestoreRules.ANY_BOOT))
    }

    @Test
    fun atMostThreeRestoresInTenMinutes() {
        var history = emptyList<Long>()
        for (now in listOf(10_000L, 21_000L, 65_000L)) {
            history = RestoreRules.admit(history, now) ?: error("restore at $now refused")
        }
        assertEquals(3, history.size)
        assertNull(RestoreRules.admit(history, 3 * minute))
        // Once the first one is out of the window, one more is allowed.
        assertNotNull(RestoreRules.admit(history, 10_000L + RestoreRules.WINDOW_MS))
    }

    @Test
    fun timesFromAnEarlierBootDoNotCount() {
        val history = listOf(50 * minute, 51 * minute, 52 * minute)
        assertEquals(listOf(2 * minute), RestoreRules.admit(history, 2 * minute))
    }

    /** A profile that kills the process on every start: the system restarts it with a growing delay, forever. */
    @Test
    fun crashOnEveryStartStopsAfterThreeAttempts() {
        var stored = RestoreRules.encode(emptyList())
        var now = 5_000L
        var delay = 1_000L
        var attempts = 0
        while (now < 60 * minute) {
            val admitted = RestoreRules.admit(RestoreRules.decode(stored), now) ?: break
            stored = RestoreRules.encode(admitted) // stored before the start, which then crashes
            attempts++
            now += delay
            delay = (delay * 4).coerceAtMost(minute)
        }
        assertEquals(RestoreRules.MAX_RESTORES, attempts)
        assertTrue(now < RestoreRules.WINDOW_MS)
    }

    /**
     * The periodic check (every 15 min, longer than the window) restarts a profile that never connects: the window alone
     * would admit every one of them, the count of restores without a connect stops it after three.
     */
    @Test
    fun startsFurtherApartThanTheWindowStopAfterThreeWithoutAConnect() {
        var stored = RestoreRules.encode(emptyList())
        var unconnected = 0L
        var attempts = 0
        var now = 15 * minute
        while (now < 24 * 60 * minute) {
            val admitted = RestoreRules.admit(RestoreRules.decode(stored), now, unconnected) ?: break
            stored = RestoreRules.encode(admitted)
            unconnected++ // the start dies before Connected
            attempts++
            now += 15 * minute
        }
        assertEquals(RestoreRules.MAX_RESTORES, attempts)
        // A start that connects (onConnected clears the count) keeps the restores of a later death going.
        assertNotNull(RestoreRules.admit(RestoreRules.decode(stored), now, 0L))
    }

    @Test
    fun decodeToleratesGarbage() {
        assertEquals(emptyList<Long>(), RestoreRules.decode(null))
        assertEquals(emptyList<Long>(), RestoreRules.decode(""))
        assertEquals(listOf(1L, 3L), RestoreRules.decode("1, x,3,"))
        assertEquals(listOf(7L, 8L), RestoreRules.decode(RestoreRules.encode(listOf(7L, 8L))))
    }
}
