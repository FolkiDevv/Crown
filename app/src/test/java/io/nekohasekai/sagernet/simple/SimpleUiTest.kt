package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleUiTest {

    private class MapStore : SimpleUi.Store {
        val values = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = values[key] ?: default
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }
    }

    @Test
    fun simpleInterfaceIsTheDefault() {
        assertTrue(SimpleUi.shouldRedirect(emptySet()))
    }

    @Test
    fun classicIsNotRemembered() {
        // Only an intent carrying the classic flag stays in MainActivity; the next plain launch opens the simple
        // interface again. A choice an older version stored is not read.
        assertFalse(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(emptySet()))
    }

    @Test
    fun classicOnlyIntentsStayInMainActivity() {
        assertFalse(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertFalse(SimpleUi.shouldRedirect(setOf("wifiPermissionFlow")))
        assertTrue(SimpleUi.shouldRedirect(setOf("someOtherExtra")))
    }

    @Test
    fun importsOpenTheSimpleInterface() {
        // Batch 4: links, files, shares and the QR scanner's texts are imported by the simple interface.
        assertTrue(SimpleUi.shouldRedirect(setOf("android.intent.extra.TEXT")))
        assertTrue(SimpleUi.shouldRedirect(setOf("importTexts")))
    }

    @Test
    fun advancedModeIsOffUntilTurnedOn() {
        val store = MapStore()
        assertFalse(SimpleUi.isAdvanced(store))
        SimpleUi.setAdvanced(store, true)
        assertTrue(SimpleUi.isAdvanced(store))
        SimpleUi.setAdvanced(store, false)
        assertFalse(SimpleUi.isAdvanced(store))
    }

    @Test
    fun classicScreensStayInMainActivity() {
        // The advanced mode's links carry the classic flag and the screen; the redirect leaves them to MainActivity.
        assertFalse(
            SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC, "io.nekohasekai.sagernet.simple.CLASSIC_SCREEN"))
        )
    }
}
