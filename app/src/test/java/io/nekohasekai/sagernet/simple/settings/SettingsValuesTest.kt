package io.nekohasekai.sagernet.simple.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValuesTest {

    @Test
    fun autoUpdateIsOnFromTheSchedulersMinimum() {
        // sub_auto_update: sign-encoded minutes, the classic default -30 is off
        assertFalse(SettingsValues.autoUpdateOn(-30))
        assertFalse(SettingsValues.autoUpdateOn(0))
        assertFalse(SettingsValues.autoUpdateOn(10))
        assertTrue(SettingsValues.autoUpdateOn(30))
        assertTrue(SettingsValues.autoUpdateOn(1440))
    }

    @Test
    fun switchKeepsTheIntervalWhileOff() {
        assertEquals(-120, SettingsValues.autoUpdate(120, on = false))
        assertEquals(120, SettingsValues.autoUpdate(-120, on = true))
        assertEquals(1440, SettingsValues.autoUpdate(1440, on = true))
        // the classic default keeps its 30 minutes
        assertEquals(30, SettingsValues.autoUpdate(-30, on = true))
    }

    @Test
    fun aTooShortOrMissingIntervalBecomesADay() {
        assertEquals(1440, SettingsValues.autoUpdate(0, on = true))
        assertEquals(1440, SettingsValues.autoUpdate(-5, on = true))
        assertEquals(-1440, SettingsValues.autoUpdate(10, on = false))
    }

    @Test
    fun themeChoicesFollowTheClassicNightMode() {
        assertEquals(NightMode.SYSTEM, NightMode.of(0))
        assertEquals(NightMode.DARK, NightMode.of(1))
        assertEquals(NightMode.LIGHT, NightMode.of(2))
        assertEquals(NightMode.BATTERY, NightMode.of(3))
        assertEquals(NightMode.SYSTEM, NightMode.of(42))
        val plain = listOf(NightMode.SYSTEM, NightMode.LIGHT, NightMode.DARK)
        assertEquals(plain, SettingsValues.nightModes(NightMode.DARK))
        // the battery-saver mode set in the classic interface stays choosable while it is in effect
        assertEquals(plain + NightMode.BATTERY, SettingsValues.nightModes(NightMode.BATTERY))
    }
}
