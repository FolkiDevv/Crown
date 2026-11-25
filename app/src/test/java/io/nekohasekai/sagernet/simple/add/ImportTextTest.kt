package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.add.ImportText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTextTest {

    @Test
    fun kinds() {
        assertEquals(Kind.EMPTY, ImportText.kind("  \n "))
        assertEquals(Kind.SUBSCRIPTION_URL, ImportText.kind(" https://sub.example.com/api/sub/AbC123 \n"))
        assertEquals(Kind.SUBSCRIPTION_URL, ImportText.kind("HTTP://1.2.3.4:8080/s"))
        assertEquals(Kind.SUBSCRIBE_LINK, ImportText.kind("throne://addsub/aHR0cHM6Ly9leGFtcGxlLmNvbS9z"))
        assertEquals(Kind.SUBSCRIBE_LINK, ImportText.kind("clash://install-config?url=https%3A%2F%2Fa.b%2Fc"))
        assertEquals(Kind.ROUTE_LINK, ImportText.kind("throne://route/abc"))
        assertEquals(Kind.ROUTE_LINK, ImportText.kind("throne://remoteroute/abc"))
        assertEquals(Kind.CONTENT, ImportText.kind("vless://uuid@host:443?security=reality#DE"))
        assertEquals(Kind.CONTENT, ImportText.kind("[Interface]\nPrivateKey = x\n[Peer]\nEndpoint = 1.2.3.4:51820"))
        assertEquals(Kind.CONTENT, ImportText.kind("hello, this is just a note"))
        // Several URLs are a document, not one subscription.
        assertEquals(Kind.CONTENT, ImportText.kind("https://a.example/s\nhttps://b.example/s"))
    }

    @Test
    fun httpUrls() {
        assertTrue(ImportText.isHttpUrl("https://example.com"))
        assertTrue(ImportText.isHttpUrl("https://user:pw@example.com/x"))
        assertFalse(ImportText.isHttpUrl("https://"))
        assertFalse(ImportText.isHttpUrl("https:///path"))
        assertFalse(ImportText.isHttpUrl("https://exa mple.com"))
        assertFalse(ImportText.isHttpUrl("ftp://example.com"))
    }

    @Test
    fun normalizeDropsTheDisplayName() {
        assertEquals(
            ImportText.normalize("vless://u-u-i-d@host.example:443?type=tcp&security=reality"),
            ImportText.normalize("vless://u-u-i-d@host.example:443?type=tcp&security=reality#%F0%9F%87%A9%F0%9F%87%AA%20DE"),
        )
    }

    @Test
    fun normalizeSchemeHostPortAndSlash() {
        assertEquals("https://sub.example.com/api/sub/AbC", ImportText.normalize("HTTPS://Sub.Example.COM:443/api/sub/AbC/"))
        assertEquals("http://example.com/s", ImportText.normalize("http://example.com:80/s"))
        // Another port is another endpoint.
        assertEquals("https://example.com:8443/s", ImportText.normalize("https://example.com:8443/s"))
        assertEquals("https://example.com", ImportText.normalize("https://example.com/"))
    }

    @Test
    fun normalizeKeepsSecretsCase() {
        // Tokens, UUIDs and passwords are case-sensitive.
        assertNotEquals(ImportText.normalize("https://example.com/sub/AbC"), ImportText.normalize("https://example.com/sub/abc"))
        assertNotEquals(ImportText.normalize("trojan://PassWord@h:443"), ImportText.normalize("trojan://password@h:443"))
        assertEquals("trojan://PassWord@host.example:443", ImportText.normalize("TROJAN://PassWord@HOST.example:443"))
    }

    @Test
    fun normalizeOrdersQueryParameters() {
        assertEquals(
            ImportText.normalize("vless://id@h:443?sni=a.com&type=ws&path=%2F"),
            ImportText.normalize("vless://id@h:443?path=%2F&sni=a.com&type=ws"),
        )
        assertNotEquals(
            ImportText.normalize("vless://id@h:443?sni=a.com"),
            ImportText.normalize("vless://id@h:443?sni=b.com"),
        )
    }

    @Test
    fun alreadyAdded() {
        val known = listOf(
            "https://sub.example.com/api/sub/AbC",
            "vless://id@h:443?type=tcp#Germany",
        )
        assertTrue(ImportText.alreadyAdded(listOf("https://SUB.example.com/api/sub/AbC/"), known))
        assertTrue(ImportText.alreadyAdded(listOf("vless://id@h:443?type=tcp#Another name"), known))
        assertFalse(ImportText.alreadyAdded(listOf("https://sub.example.com/api/sub/XyZ"), known))
        // One new server among known ones is worth offering.
        assertFalse(ImportText.alreadyAdded(listOf("vless://id@h:443?type=tcp", "vless://id2@h:443?type=tcp"), known))
        assertFalse(ImportText.alreadyAdded(emptyList(), known))
        assertFalse(ImportText.alreadyAdded(listOf("https://a.example/s"), emptyList()))
    }

    @Test
    fun fingerprintIgnoresSpellingButNotContent() {
        assertEquals(
            ImportText.fingerprint("https://Sub.Example.com/s/AbC/\n"),
            ImportText.fingerprint("  https://sub.example.com/s/AbC"),
        )
        assertNotEquals(ImportText.fingerprint("https://sub.example.com/s/AbC"), ImportText.fingerprint("https://sub.example.com/s/AbD"))
    }

    @Test
    fun scannedPayloadsKeepDocumentsApart() {
        val wireGuard = "[Interface]\nPrivateKey = x\n[Peer]\nEndpoint = 1.2.3.4:51820"
        val vless = "vless://id@h:443?type=tcp#A"
        val trojan = "trojan://pw@h2:443#B"
        val sub = "https://sub.example.com/s"
        assertEquals(
            listOf(wireGuard, "$vless\n$trojan", sub),
            ImportText.importUnits(listOf(wireGuard, vless, " ", sub, trojan)),
        )
        // Links only: one document, as a multi-link QR import always was.
        assertEquals(listOf("$vless\n$trojan"), ImportText.importUnits(listOf(vless, trojan)))
        val json = """{"outbounds":[{"type":"socks","server":"1.2.3.4","server_port":1080}]}"""
        assertEquals(listOf(vless, json), ImportText.importUnits(listOf(vless, json)))
    }
}
