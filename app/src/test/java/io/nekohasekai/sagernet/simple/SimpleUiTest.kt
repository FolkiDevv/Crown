package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleUiTest {

    private class MapStore : SimpleUi.Store {
        val values = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as Boolean? ?: default
        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getString(key: String) = values[key] as String?
        override fun putString(key: String, value: String) {
            values[key] = value
        }
    }

    @Test
    fun simpleInterfaceIsTheDefault() {
        val store = MapStore()
        assertTrue(SimpleUi.isSimpleSelected(store))
        assertTrue(SimpleUi.shouldRedirect(store, "android.intent.action.MAIN", emptySet()))
        assertTrue(SimpleUi.shouldRedirect(store, null, emptySet()))
    }

    @Test
    fun choiceIsKept() {
        val store = MapStore()
        SimpleUi.select(store, simple = false)
        assertFalse(SimpleUi.isSimpleSelected(store))
        assertFalse(SimpleUi.shouldRedirect(store, "android.intent.action.MAIN", emptySet()))
        SimpleUi.select(store, simple = true)
        assertTrue(SimpleUi.isSimpleSelected(store))
        assertEquals(true, store.values[SimpleUi.KEY_SIMPLE])
    }

    @Test
    fun classicOnlyIntentsStayInMainActivity() {
        val store = MapStore()
        assertFalse(
            SimpleUi.shouldRedirect(store, "android.service.quicksettings.action.QS_TILE_PREFERENCES", emptySet())
        )
        assertFalse(SimpleUi.shouldRedirect(store, null, setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertFalse(SimpleUi.shouldRedirect(store, null, setOf("wifiPermissionFlow")))
        assertTrue(SimpleUi.shouldRedirect(store, null, setOf("someOtherExtra")))
    }

    @Test
    fun importsOpenTheSimpleInterface() {
        // Batch 4: links, files, shares and the QR scanner's texts are imported by the simple interface.
        val store = MapStore()
        assertTrue(SimpleUi.shouldRedirect(store, "android.intent.action.VIEW", emptySet()))
        assertTrue(SimpleUi.shouldRedirect(store, "android.intent.action.SEND", setOf("android.intent.extra.TEXT")))
        assertTrue(SimpleUi.shouldRedirect(store, null, setOf("importTexts")))
        // A backup the simple interface hands to the classic restore carries the classic flag on its VIEW.
        assertFalse(SimpleUi.shouldRedirect(store, "android.intent.action.VIEW", setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        SimpleUi.select(store, simple = false)
        assertFalse(SimpleUi.shouldRedirect(store, "android.intent.action.VIEW", emptySet()))
    }

    @Test
    fun freshInstallIsNotAnUpgrade() {
        val store = MapStore()
        assertFalse(SimpleUi.isUpgrade(store, firstInstallTime = 1000, lastUpdateTime = 1000))
        assertEquals(SimpleUi.INSTALL_FRESH, store.values[SimpleUi.KEY_INSTALL_KIND])
        // a later update of this version must not turn a fresh install into an upgrade
        assertFalse(SimpleUi.isUpgrade(store, firstInstallTime = 1000, lastUpdateTime = 5000))
    }

    @Test
    fun upgradeIsRecognisedAndKept() {
        val store = MapStore()
        assertTrue(SimpleUi.isUpgrade(store, firstInstallTime = 1000, lastUpdateTime = 5000))
        assertEquals(SimpleUi.INSTALL_UPGRADE, store.values[SimpleUi.KEY_INSTALL_KIND])
        assertTrue(SimpleUi.isUpgrade(store, firstInstallTime = 1000, lastUpdateTime = 9000))
    }

    @Test
    fun upgradeAlsoOpensTheSimpleInterface() {
        val store = MapStore()
        SimpleUi.isUpgrade(store, firstInstallTime = 1000, lastUpdateTime = 5000)
        assertTrue(SimpleUi.isSimpleSelected(store))
    }
}
