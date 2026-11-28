package io.nekohasekai.sagernet.simple.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * restrict-exported-vpn-controls: what another app may reach in the manifest. A new exported component is a decision,
 * so it is listed here; the launcher shortcut executors are never among them.
 */
class ExportedComponentsTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private val components: List<Element> by lazy {
        // Unit tests run in the module directory.
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val app = doc.documentElement.getElementsByTagName("application").item(0) as Element
        (0 until app.childNodes.length).mapNotNull { app.childNodes.item(it) as? Element }
            .filter { it.tagName in setOf("activity", "activity-alias", "service", "receiver", "provider") }
    }

    private fun Element.attr(name: String) = getAttributeNS(android, name)
    private fun Element.name() = attr("name").let { if (it.startsWith(".")) "io.nekohasekai.sagernet$it" else it }
    private fun Element.hasFilter() = getElementsByTagName("intent-filter").length > 0
    private fun Element.actions(): Set<String> {
        val nodes = getElementsByTagName("action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).attr("name") }.toSet()
    }

    private fun component(name: String) = components.single { it.name() == name }

    @Test
    fun everyComponentWithAFilterSaysWhetherItIsExported() {
        // targetSdk 31+ requires it; an implicit default would read as "exported" on older targets.
        val missing = components.filter { it.hasFilter() && it.attr("exported").isEmpty() }.map { it.name() }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun onlyTheseComponentsAreExported() {
        val exported = components.filter { it.attr("exported") == "true" }.map { it.name() }.toSet()
        assertEquals(
            setOf(
                "io.nekohasekai.sagernet.ui.MainActivity",
                "io.nekohasekai.sagernet.ui.ScannerActivity",
                "io.nekohasekai.sagernet.simple.control.ShortcutEntryActivity",
                "io.nekohasekai.sagernet.bg.TileService",
                "io.nekohasekai.sagernet.BootReceiver",
                "io.nekohasekai.sagernet.simple.control.ExternalControlReceiver",
            ),
            exported,
        )
    }

    @Test
    fun shortcutExecutorsAreNotExported() {
        for (name in listOf(
            "io.nekohasekai.sagernet.QuickToggleShortcut",
            "io.nekohasekai.sagernet.ui.QuickEnableShortcut",
            "io.nekohasekai.sagernet.ui.QuickDisableShortcut",
        )) {
            val executor = component(name)
            assertEquals(name, "false", executor.attr("exported"))
            assertFalse(name, executor.hasFilter())
        }
        // The launcher's shortcut picker goes to the entry, which only hands out the shortcut.
        assertEquals(
            setOf("android.intent.action.CREATE_SHORTCUT"),
            component("io.nekohasekai.sagernet.simple.control.ShortcutEntryActivity").actions(),
        )
    }

    @Test
    fun systemOnlyEntriesKeepTheirGuards() {
        assertEquals(
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            component("io.nekohasekai.sagernet.bg.TileService").attr("permission"),
        )
        assertEquals(
            setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.LOCKED_BOOT_COMPLETED"),
            component("io.nekohasekai.sagernet.BootReceiver").actions(),
        )
        assertEquals(
            setOf("\${applicationId}.action.CONNECT", "\${applicationId}.action.DISCONNECT", "\${applicationId}.action.TOGGLE"),
            component("io.nekohasekai.sagernet.simple.control.ExternalControlReceiver").actions(),
        )
    }
}
