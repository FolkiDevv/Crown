package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.simple.regime.ProbeGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeGateTest {

    private val wifi = "wifi"
    private val mobile = "mobile"

    @Test
    fun latestCheckInItsNetworkIsAccepted() {
        val gate = ProbeGate<String>()
        val ticket = gate.begin(mobile, modeRevision = 3)
        assertEquals(Verdict.ACCEPTED, gate.verdict(ticket, mobile, TrafficMode.AUTO, 3))
    }

    /** traffic-modes "Смена сети во время проверки". */
    @Test
    fun wifiResultAfterSwitchToMobileIsDiscarded() {
        val gate = ProbeGate<String>()
        val wifiCheck = gate.begin(wifi, 0)
        val mobileCheck = gate.begin(mobile, 0)
        // The mobile check finds whitelists first, then the Wi-Fi check ends with "normal".
        assertEquals(Verdict.ACCEPTED, gate.verdict(mobileCheck, mobile, TrafficMode.AUTO, 0))
        assertEquals(Verdict.SUPERSEDED, gate.verdict(wifiCheck, mobile, TrafficMode.AUTO, 0))
    }

    @Test
    fun resultOfANetworkThatIsGoneIsDiscarded() {
        val gate = ProbeGate<String>()
        val ticket = gate.begin(wifi, 0)
        // The network changed and no new check began yet (or the new network is not known yet).
        assertEquals(Verdict.OTHER_NETWORK, gate.verdict(ticket, mobile, TrafficMode.AUTO, 0))
        assertEquals(Verdict.OTHER_NETWORK, gate.verdict(ticket, null, TrafficMode.AUTO, 0))
    }

    /** traffic-modes "Две проверки в одной сети": the timer check is superseded by a later one in the same network. */
    @Test
    fun overlappingChecksInOneNetworkOnlyTheLatestCounts() {
        val gate = ProbeGate<String>()
        val timer = gate.begin(mobile, 5)
        val repair = gate.begin(mobile, 5)
        assertEquals(Verdict.ACCEPTED, gate.verdict(repair, mobile, TrafficMode.AUTO, 5))
        assertEquals(Verdict.SUPERSEDED, gate.verdict(timer, mobile, TrafficMode.AUTO, 5))
        // Also when the older one ends last.
        assertEquals(Verdict.SUPERSEDED, gate.verdict(timer, mobile, TrafficMode.AUTO, 5))
    }

    /** traffic-modes "Ручная смена режима во время проверки". */
    @Test
    fun manualModeChangeDiscardsTheResult() {
        val gate = ProbeGate<String>()
        val ticket = gate.begin(mobile, modeRevision = 1)
        // The user picked «Белые списки»: the mode is not «Авто» and the revision moved on.
        assertEquals(Verdict.MODE_CHANGED, gate.verdict(ticket, mobile, TrafficMode.WHITELIST, 2))
        // Only the revision moved (picked another mode and «Авто» again before the check ended).
        assertEquals(Verdict.MODE_CHANGED, gate.verdict(ticket, mobile, TrafficMode.AUTO, 2))
        // Only the mode differs (a stored mode the revision did not count).
        assertEquals(Verdict.MODE_CHANGED, gate.verdict(ticket, mobile, TrafficMode.SMART, 1))
        // No mode chosen at all.
        assertEquals(Verdict.MODE_CHANGED, gate.verdict(ticket, mobile, null, 1))
    }

    @Test
    fun invalidateMakesEveryTicketStale() {
        val gate = ProbeGate<String>()
        val ticket = gate.begin(wifi, 0)
        assertTrue(gate.isLatest(ticket))
        gate.invalidate()
        assertFalse(gate.isLatest(ticket))
        assertEquals(Verdict.SUPERSEDED, gate.verdict(ticket, wifi, TrafficMode.AUTO, 0))
        // A new check after it is accepted again.
        val next = gate.begin(wifi, 0)
        assertEquals(Verdict.ACCEPTED, gate.verdict(next, wifi, TrafficMode.AUTO, 0))
    }

    @Test
    fun supersededWinsOverTheOtherReasons() {
        val gate = ProbeGate<String>()
        val old = gate.begin(wifi, 0)
        gate.begin(mobile, 1)
        assertEquals(Verdict.SUPERSEDED, gate.verdict(old, mobile, TrafficMode.SMART, 1))
    }

    @Test
    fun returningToTheSameNetworkStillNeedsTheLatestCheck() {
        val gate = ProbeGate<Long>()
        val first = gate.begin(1L, 0)
        gate.begin(2L, 0)
        val back = gate.begin(1L, 0)
        assertEquals(Verdict.SUPERSEDED, gate.verdict(first, 1L, TrafficMode.AUTO, 0))
        assertEquals(Verdict.ACCEPTED, gate.verdict(back, 1L, TrafficMode.AUTO, 0))
    }
}
