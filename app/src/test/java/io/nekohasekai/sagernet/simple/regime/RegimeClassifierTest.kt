package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.regime.ProbeOutcome.REACHABLE
import io.nekohasekai.sagernet.simple.regime.ProbeOutcome.TIMEOUT
import io.nekohasekai.sagernet.simple.regime.ProbeOutcome.UNREACHABLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegimeClassifierTest {

    private fun classify(allowed: List<ProbeOutcome>, blocked: List<ProbeOutcome>) =
        RegimeClassifier.classify(allowed, blocked)

    // ------------------------------------------------------------------------------------------------ the three outcomes

    @Test
    fun whitelistsWhenAllowedAnswerAndBlockedDoNot() {
        assertEquals(Regime.WHITELIST, classify(List(4) { REACHABLE }, List(4) { UNREACHABLE }))
    }

    @Test
    fun normalWhenEverythingAnswers() {
        assertEquals(Regime.NORMAL, classify(List(4) { REACHABLE }, List(4) { REACHABLE }))
    }

    @Test
    fun noInternetWhenNothingAnswers() {
        assertEquals(Regime.NO_INTERNET, classify(List(4) { UNREACHABLE }, List(4) { UNREACHABLE }))
    }

    // ------------------------------------------------------------------------------------------------ edge cases

    @Test
    fun oneAllowedAddressIsEnoughForWhitelists() {
        assertEquals(Regime.WHITELIST, classify(listOf(UNREACHABLE, TIMEOUT, REACHABLE, UNREACHABLE), List(4) { TIMEOUT }))
    }

    @Test
    fun oneBlockedAddressMakesTheNetworkNormal() {
        assertEquals(Regime.NORMAL, classify(List(4) { REACHABLE }, listOf(TIMEOUT, UNREACHABLE, UNREACHABLE, REACHABLE)))
        // Partly reachable allowed addresses do not change that.
        assertEquals(Regime.NORMAL, classify(listOf(REACHABLE, TIMEOUT, UNREACHABLE, UNREACHABLE), listOf(REACHABLE)))
    }

    @Test
    fun onlyBlockedAddressesAnsweringIsNormalNotWhitelists() {
        assertEquals(Regime.NORMAL, classify(List(4) { TIMEOUT }, listOf(REACHABLE, UNREACHABLE)))
    }

    @Test
    fun timeoutsCountAsUnreachable() {
        assertEquals(Regime.NO_INTERNET, classify(List(4) { TIMEOUT }, List(4) { TIMEOUT }))
        assertEquals(Regime.WHITELIST, classify(listOf(TIMEOUT, REACHABLE), listOf(TIMEOUT, TIMEOUT)))
        assertEquals(Regime.NO_INTERNET, classify(listOf(TIMEOUT, UNREACHABLE), listOf(UNREACHABLE, TIMEOUT)))
    }

    @Test
    fun emptySetsAreNoInternet() {
        assertEquals(Regime.NO_INTERNET, classify(emptyList(), emptyList()))
        assertEquals(Regime.WHITELIST, classify(listOf(REACHABLE), emptyList()))
    }

    // ------------------------------------------------------------------------------------------------ network gone

    @Test
    fun aRequestLostWithTheNetworkMakesTheCheckInconclusive() {
        val gone = ProbeOutcome.NETWORK_GONE
        // Two allowed addresses answered before the drop: this alone would read as whitelists.
        val allowed = listOf(REACHABLE, REACHABLE, gone, gone)
        val blocked = List(4) { gone }
        assertEquals(Regime.WHITELIST, classify(allowed, blocked))
        assertTrue(RegimeClassifier.inconclusive(allowed, blocked))
        assertEquals(false, RegimeClassifier.inconclusive(List(4) { REACHABLE }, List(4) { TIMEOUT }))
        assertEquals(false, RegimeClassifier.inconclusive(List(4) { UNREACHABLE }, List(4) { UNREACHABLE }))
        assertEquals(false, gone.reachable)
    }

    @Test
    fun networkGoneErrorsAreToldFromAddressErrors() {
        assertTrue(ProbeFailures.isNetworkGone(java.net.ConnectException("failed to connect to /1.2.3.4 (port 443): connect failed: ENETUNREACH (Network is unreachable)")))
        assertTrue(ProbeFailures.isNetworkGone(java.net.SocketException("Software caused connection abort")))
        assertTrue(ProbeFailures.isNetworkGone(java.net.SocketException("Binding socket to network 101 failed: EPERM (Operation not permitted)")))
        assertTrue(ProbeFailures.isNetworkGone(java.net.UnknownHostException("ya.ru").apply { initCause(java.io.IOException("sendto failed: ENONET (Machine is not on the network)")) }))
        assertEquals(false, ProbeFailures.isNetworkGone(java.net.SocketTimeoutException("timeout")))
        assertEquals(false, ProbeFailures.isNetworkGone(java.net.ConnectException("failed to connect: ECONNREFUSED (Connection refused)")))
        assertEquals(false, ProbeFailures.isNetworkGone(javax.net.ssl.SSLHandshakeException("Connection reset by peer")))
        assertEquals(false, ProbeFailures.isNetworkGone(java.net.UnknownHostException("ya.ru")))
    }

    // ------------------------------------------------------------------------------------------------ the stored record

    @Test
    fun recordRoundTrips() {
        for (record in listOf(
            RegimeRecord(Regime.WHITELIST, 432_000_000_123L),
            RegimeRecord(Regime.NORMAL, 1L),
            RegimeRecord(Regime.NO_INTERNET, 7L),
            RegimeRecord(null, 99L),
        )) {
            assertEquals(record, RegimeRecord.parse(record.encode()))
        }
    }

    @Test
    fun damagedRecordsAreIgnored() {
        for (text in listOf(null, "", "whitelist", "@12", "whitelist@", "whitelist@abc", "unknown@12")) {
            assertNull(text, RegimeRecord.parse(text))
        }
    }

    @Test
    fun whitelistsOnlyCountInTheirOwnNetwork() {
        val record = RegimeRecord(Regime.WHITELIST, 10L)
        assertTrue(record.whitelistsIn(10L))
        assertEquals(false, record.whitelistsIn(11L))
        assertEquals(false, record.whitelistsIn(null))
        assertEquals(false, RegimeRecord(Regime.NORMAL, 10L).whitelistsIn(10L))
        assertEquals(false, RegimeRecord(null, 10L).whitelistsIn(10L))
    }
}
