package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * require-consent-for-external-imports: the classic flags ([SimpleUi.EXTRA_OPEN_CLASSIC], the Wi-Fi permission flow)
 * must not let another app send its data to the classic interface, which used to import shared
 * text without a question. Only an intent the app sent itself ([SimpleUi.markOwn]) keeps its data there.
 */
class SimpleUiExternalImportTest {

    private val text = "android.intent.extra.TEXT"
    private val stream = "android.intent.extra.STREAM"
    private val wifi = "wifiPermissionFlow"

    @Test
    fun sharedDataWithAClassicFlagGoesToTheSimpleInterface() {
        assertTrue(SimpleUi.shouldRedirect(setOf(text, SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(setOf(stream, SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(setOf("importTexts", SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertTrue(SimpleUi.shouldRedirect(setOf("importTexts", wifi)))
        assertTrue(SimpleUi.shouldRedirect(setOf(text, wifi)))
        assertTrue(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_ROUTE_LINK, SimpleUi.EXTRA_OPEN_CLASSIC)))
    }

    @Test
    fun aLinkOrFileWithAClassicFlagGoesToTheSimpleInterface() {
        assertTrue(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC), hasData = true))
        assertTrue(SimpleUi.shouldRedirect(setOf(wifi), hasData = true))
    }

    @Test
    fun theAppsOwnHandOversStayClassic() {
        // A backup and a routing link the simple interface hands to the classic one.
        assertFalse(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC), hasData = true, own = true))
        assertFalse(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC, SimpleUi.EXTRA_ROUTE_LINK), own = true))
    }

    @Test
    fun classicFlagsWithoutDataStillStayClassic() {
        assertFalse(SimpleUi.shouldRedirect(setOf(SimpleUi.EXTRA_OPEN_CLASSIC)))
        assertFalse(SimpleUi.shouldRedirect(setOf(wifi)))
    }
}
