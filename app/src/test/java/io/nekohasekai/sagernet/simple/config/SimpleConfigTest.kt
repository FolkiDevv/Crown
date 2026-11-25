package io.nekohasekai.sagernet.simple.config

import io.nekohasekai.sagernet.simple.modes.WhitelistKeywords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SimpleConfigTest {

    private fun config(
        version: String = "2",
        allowed: String = """["https://ya.ru/", "https://vk.com"]""",
        blocked: String = """["https://www.google.com/generate_204"]""",
        dns: String = """["77.88.8.8", "https://common.dot.dns.yandex.net/dns-query"]""",
        keywords: String = """["БС", "LTE"]""",
        timeHosts: String = """["ya.ru"]""",
    ) = """
        {
          "version": $version,
          "probes": { "allowed": $allowed, "blocked": $blocked },
          "whitelistDns": $dns,
          "wlKeywords": $keywords,
          "timeHosts": $timeHosts,
          "comment": "unknown keys are ignored"
        }
    """.trimIndent()

    private fun assertRefused(text: String) {
        try {
            SimpleConfig.parse(text)
            fail("accepted: $text")
        } catch (_: SimpleConfig.InvalidConfigException) {
        }
        assertNull(SimpleConfig.parseOrNull(text))
    }

    @Test
    fun parsesAValidConfig() {
        val parsed = SimpleConfig.parse(config())
        assertEquals(2, parsed.version)
        assertEquals(listOf("https://ya.ru/", "https://vk.com"), parsed.probesAllowed)
        assertEquals(listOf("https://www.google.com/generate_204"), parsed.probesBlocked)
        assertEquals(listOf("77.88.8.8", "https://common.dot.dns.yandex.net/dns-query"), parsed.whitelistDns)
        assertEquals(listOf("БС", "LTE"), parsed.wlKeywords)
        assertEquals(listOf("ya.ru"), parsed.timeHosts)
    }

    @Test
    fun entriesAreTrimmedAndKeptOnce() {
        val parsed = SimpleConfig.parse(config(keywords = """[" БС ", "БС", "LTE"]"""))
        assertEquals(listOf("БС", "LTE"), parsed.wlKeywords)
    }

    @Test
    fun bundledConfigIsValid() {
        // Unit tests run in the module directory; the app refuses to start the modes without a valid bundled copy.
        val bundled = SimpleConfig.parse(File("src/simple/assets/simple/config.json").readText())
        assertTrue(bundled.version >= 1)
        assertTrue(bundled.probesAllowed.isNotEmpty() && bundled.probesBlocked.isNotEmpty())
        assertTrue(bundled.whitelistDns.isNotEmpty() && bundled.wlKeywords.isNotEmpty() && bundled.timeHosts.isNotEmpty())
    }

    @Test
    fun bundledKeywordsMarkObkhodServers() {
        val keywords = SimpleConfig.parse(File("src/simple/assets/simple/config.json").readText()).wlKeywords
        assertTrue(WhitelistKeywords.isWhitelistServer("🇷🇺 Обход 1", keywords))
        assertTrue(WhitelistKeywords.isWhitelistServer("ru обход", keywords))
        assertFalse(WhitelistKeywords.isWhitelistServer("Обходной путь", keywords))
    }

    @Test
    fun damagedJsonIsRefused() {
        assertRefused("")
        assertRefused("not json")
        assertRefused(config().dropLast(10))
        assertRefused("[]")
        assertRefused("\"text\"")
        assertRefused("<html>404</html>")
    }

    @Test
    fun incompleteConfigIsRefused() {
        assertRefused("""{"version": 1}""")
        assertRefused(config().replace("\"wlKeywords\"", "\"keywords\""))
        assertRefused(config().replace("\"timeHosts\"", "\"hosts\""))
        assertRefused(config().replace("\"probes\"", "\"checks\""))
        assertRefused(config(allowed = "[]"))
        assertRefused(config(blocked = "[]"))
        assertRefused(config(dns = "[]"))
        assertRefused(config(keywords = "[]"))
        assertRefused(config(timeHosts = "[]"))
    }

    @Test
    fun wrongTypesAndValuesAreRefused() {
        assertRefused(config(version = "0"))
        assertRefused(config(version = "-3"))
        assertRefused(config(version = "\"2\""))
        assertRefused(config(version = "1.5"))
        assertRefused(config(allowed = "\"https://ya.ru\""))
        assertRefused(config(allowed = """["http://ya.ru"]"""))
        assertRefused(config(allowed = """["https://ya.ru/?id=123"]"""))
        assertRefused(config(allowed = """["https://user@ya.ru"]"""))
        assertRefused(config(allowed = """["https://"]"""))
        assertRefused(config(blocked = """[1, 2]"""))
        assertRefused(config(dns = """["77.88.8.8, 77.88.8.1"]"""))
        assertRefused(config(dns = """["  "]"""))
        assertRefused(config(keywords = """["БС", ""]"""))
        assertRefused(config(keywords = """["БС", null]"""))
        assertRefused(config(timeHosts = """["https://ya.ru"]"""))
        assertRefused(config(timeHosts = """["ya.ru/path"]"""))
        val tooMany = (1..SimpleConfig.MAX_ITEMS + 1).joinToString(",", "[", "]") { "\"k$it\"" }
        assertRefused(config(keywords = tooMany))
        assertRefused(config(keywords = "[\"${"x".repeat(SimpleConfig.MAX_ITEM_LENGTH + 1)}\"]"))
    }

    @Test
    fun dnsServersMustBeUsableByTheRouteDnsSettings() {
        for (ok in listOf(
            "77.88.8.8", "udp://77.88.8.1:53", "tcp://77.88.8.8", "tls://common.dot.dns.yandex.net",
            "quic://dns.example.org:853", "https://common.dot.dns.yandex.net/dns-query", "h3://dns.example.org/q",
            "https://77.88.8.8/dns-query", "[2a02:6b8::feed:0ff]:53", "2a02:6b8::feed:0ff", "dns.example.org",
        )) {
            assertTrue(ok, SimpleConfig.isDnsServer(ok))
        }
        for (bad in listOf(
            "https://", "udp://", "https:///dns-query", "tls://host/path", "local", "localhost", "dhcp://auto",
            "ftp://77.88.8.8", "77.88.8.256", "1.2.3", "77.88.8.8:0", "77.88.8.8:70000", "77.88.8.8:", "[2a02:6b8::1",
            "[2a02:6b8::1]x", "[77.88.8.8]", "-bad-.example.org", "a, b",
        )) {
            assertFalse(bad, SimpleConfig.isDnsServer(bad))
        }
        assertRefused(config(dns = """["https://"]"""))
        assertRefused(config(dns = """["77.88.8.8", "local"]"""))
    }

    @Test
    fun theDeviceDnsParserCanRefuseAnEntry() {
        val text = config(dns = """["77.88.8.8", "dns.example.org"]""")
        assertNotNull(SimpleConfig.parseOrNull(text))
        assertNull(SimpleConfig.parseOrNull(text) { it != "dns.example.org" })
    }

    @Test
    fun aNewerStoredCopyWinsOverTheBundledOne() {
        val bundled = SimpleConfig.parse(config(version = "3"))
        val newer = SimpleConfig.parse(config(version = "4", keywords = """["WL"]"""))
        val older = SimpleConfig.parse(config(version = "2", keywords = """["WL"]"""))
        val same = SimpleConfig.parse(config(version = "3", keywords = """["WL"]"""))
        assertSame(newer, SimpleConfig.choose(bundled, newer))
        assertSame(bundled, SimpleConfig.choose(bundled, older))
        assertSame(bundled, SimpleConfig.choose(bundled, same))
        assertSame(bundled, SimpleConfig.choose(bundled, null))
    }

    @Test
    fun refusedDownloadKeepsThePreviousConfig() {
        // What SimpleConfigRepo.refresh does with a download: a refused text never replaces the copy in effect.
        val previous = SimpleConfig.parse(config(version = "5"))
        val downloaded = SimpleConfig.parseOrNull(config(version = "6").replace("\"wlKeywords\"", "\"x\""))
        assertNull(downloaded)
        assertSame(previous, SimpleConfig.choose(previous, downloaded))
        assertNotNull(SimpleConfig.parseOrNull(config(version = "6")))
    }
}
