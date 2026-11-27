package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * require-consent-for-external-imports: the classic flags ([SimpleUi.EXTRA_OPEN_CLASSIC], the Wi-Fi permission flow,
 * the tile's long press) must not let another app send its data to the classic interface, which used to import shared
 * text without a question. Only an intent the app sent itself ([SimpleUi.markOwn]) keeps its data there.
 */
class SimpleUiExternalImportTest {

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

    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"
    private val tilePreferences = "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    private val text = "android.intent.extra.TEXT"
    private val stream = "android.intent.extra.STREAM"
    private val wifi = "wifiPermissionFlow"

    @Test
    fun sharedDataWithAClassicFlagGoesToTheSimpleInterface() {
        val store = MapStore()
        assertTrue(SimpleUi.shouldRedirect(store, send, setOf(text, SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(store, send, setOf(stream, SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(store, null, setOf("importTexts", SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(store, null, setOf("importTexts", wifi)))
        assertTrue(SimpleUi.shouldRedirect(store, send, setOf(text, wifi)))
        assertTrue(SimpleUi.shouldRedirect(store, tilePreferences, setOf(text)))
    }

    @Test
    fun aLinkOrFileWithAClassicFlagGoesToTheSimpleInterface() {
        val store = MapStore()
        assertTrue(SimpleUi.shouldRedirect(store, view, setOf(SimpleUi.EXTRA_OPEN_CLASSIC), hasData = true))
        assertTrue(SimpleUi.shouldRedirect(store, view, setOf(wifi), hasData = true))
        assertTrue(SimpleUi.shouldRedirect(store, tilePreferences, emptySet(), hasData = true))
    }

    @Test
    fun theAppsOwnHandOversStayClassic() {
        val store = MapStore()
        // A backup and a routing link the simple interface hands to the classic one.
        assertFalse(SimpleUi.shouldRedirect(store, view, setOf(SimpleUi.EXTRA_OPEN_CLASSIC), hasData = true, own = true))
        assertFalse(SimpleUi.shouldRedirect(store, null, setOf(SimpleUi.EXTRA_OPEN_CLASSIC, "importTexts"), own = true))
    }

    @Test
    fun classicFlagsWithoutDataStillStayClassic() {
        val store = MapStore()
        assertFalse(SimpleUi.shouldRedirect(store, null, setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertFalse(SimpleUi.shouldRedirect(store, null, setOf(wifi)))
        assertFalse(SimpleUi.shouldRedirect(store, tilePreferences, emptySet()))
    }

    @Test
    fun theClassicInterfaceChosenKeepsEverything() {
        val store = MapStore()
        SimpleUi.select(store, simple = false)
        assertFalse(SimpleUi.shouldRedirect(store, send, setOf(text, SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertFalse(SimpleUi.shouldRedirect(store, view, setOf(SimpleUi.EXTRA_OPEN_CLASSIC), hasData = true))
    }
}
