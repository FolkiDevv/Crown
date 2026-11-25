package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.simple.regime.WhitelistZone.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** traffic-modes «Подсказка вне зоны белых списков». */
class WhitelistZoneTest {

    private val wifi = 10L
    private val mobile = 20L

    @Test
    fun whitelistModeOrServerIsAConfigurationForWhitelists() {
        assertEquals(Use.MODE, WhitelistZone.use(TrafficMode.WHITELIST, whitelistServer = false))
        // The mode names it even when a server for whitelists runs in it too.
        assertEquals(Use.MODE, WhitelistZone.use(TrafficMode.WHITELIST, whitelistServer = true))
        assertEquals(Use.SERVER, WhitelistZone.use(TrafficMode.SMART, whitelistServer = true))
        assertEquals(Use.SERVER, WhitelistZone.use(TrafficMode.AUTO, whitelistServer = true))
        assertEquals(Use.SERVER, WhitelistZone.use(null, whitelistServer = true))
    }

    /** «Авто» follows the network by itself; so does any mode with an ordinary server. */
    @Test
    fun ordinaryConfigurationIsNot() {
        for (mode in listOf(TrafficMode.AUTO, TrafficMode.SMART, TrafficMode.APPS, null)) {
            assertNull(WhitelistZone.use(mode, whitelistServer = false))
        }
    }

    @Test
    fun warnsInANetworkFoundWithoutWhitelists() {
        assertTrue(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NORMAL, wifi), wifi, keptNetwork = null))
        assertTrue(WhitelistZone.warns(Use.SERVER, RegimeRecord(Regime.NORMAL, mobile), mobile, keptNetwork = wifi))
    }

    @Test
    fun noWarningWhereWhitelistsAreOrNothingIsKnown() {
        // Whitelists found: the configuration is right.
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.WHITELIST, mobile), mobile, null))
        // No internet says nothing about whitelists (and is never stored), a network not checked yet neither.
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NO_INTERNET, mobile), mobile, null))
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(null, mobile), mobile, null))
        assertFalse(WhitelistZone.warns(Use.MODE, null, mobile, null))
    }

    /** The result of the Wi-Fi network says nothing once the device is in the mobile one (or in none). */
    @Test
    fun resultOfAnotherNetworkDoesNotWarn() {
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NORMAL, wifi), mobile, null))
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NORMAL, wifi), null, null))
    }

    @Test
    fun noConfigurationForWhitelistsNoWarning() {
        assertFalse(WhitelistZone.warns(null, RegimeRecord(Regime.NORMAL, wifi), wifi, null))
    }

    /** «Не напоминать» keeps the configuration in that network only. */
    @Test
    fun keptNetworkWarnsNoMore() {
        assertFalse(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NORMAL, wifi), wifi, keptNetwork = wifi))
        assertTrue(WhitelistZone.warns(Use.MODE, RegimeRecord(Regime.NORMAL, mobile), mobile, keptNetwork = wifi))
    }
}
