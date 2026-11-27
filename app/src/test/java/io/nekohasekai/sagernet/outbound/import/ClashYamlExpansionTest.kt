package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException

/** Aliases in a Clash document must not expand into more values than the text can honestly describe. */
class ClashYamlExpansionTest {

    /** The finding's 18 lines with 48 aliases: 3^16 copies of a ten-item list once every alias is copied out. */
    private fun bomb(): String = buildString {
        append("a0: &a0 [0,1,2,3,4,5,6,7,8,9]\n")
        for (i in 1..16) append("a$i: &a$i [*a${i - 1},*a${i - 1},*a${i - 1}]\n")
        append("proxies: [*a16]\n")
    }

    /**
     * [levels] levels of three aliases of a 300-item list, 3 * levels + 1 aliases in all: 3^levels copies of the list
     * once every alias is copied out. Nine levels take 28 aliases and make 3^9 * 301, some 5.9 million values.
     */
    private fun smallBomb(levels: Int): String = buildString {
        append("a0: &a0 [${(0 until 300).joinToString(",")}]\n")
        for (i in 1..levels) append("a$i: &a$i {x: *a${i - 1}, y: *a${i - 1}, z: *a${i - 1}}\n")
        append("proxies: [*a$levels]\n")
    }

    private fun rejected(text: String): YAMLException {
        try {
            ClashYaml.document(text)
        } catch (e: YAMLException) {
            return e
        }
        fail("the document was accepted")
        throw AssertionError()
    }

    private fun timed(name: String, text: String): YAMLException {
        ClashYaml.document("proxies:\n  - {name: warm, type: ss}\n")
        val started = System.nanoTime()
        val error = rejected(text)
        val millis = (System.nanoTime() - started) / 1_000_000
        println("$name rejected in $millis ms: ${error.message}")
        assertTrue("took $millis ms", millis < 1000)
        return error
    }

    @Test
    fun aliasBombIsRejectedQuickly() {
        val error = timed("alias bomb", bomb())
        assertTrue(error.message, error.message!!.contains("aliases"))
    }

    @Test
    fun bombWithinTheAliasLimitIsStoppedByTheCounter() {
        val error = timed("bomb within the alias limit", smallBomb(9))
        assertTrue(error.message, error.message!!.contains("expands too much"))
        // 3^6 copies of the list are some 220 thousand values: an honest, if odd, document.
        assertEquals(1, ClashYaml.document(smallBomb(6)).proxies!!.size)
    }

    @Test
    fun paddingDoesNotRaiseTheLimitPastTheCeiling() {
        // Some 8.6 MB of comments: 100 values per character would allow 860 million.
        val padding = buildString { repeat(200_000) { append("# padding padding padding padding padding\n") } }
        val error = rejected(padding + smallBomb(9))
        assertTrue(error.message, error.message!!.contains("expands too much"))
    }

    @Test
    fun collectionAliasesAreLimited() {
        fun doc(aliases: Int) = buildString {
            append("x-ws: &ws {path: /p}\nproxies:\n")
            for (i in 0 until aliases) append("  - {name: p$i, type: vmess, server: s, port: 1, uuid: u, ws-opts: *ws}\n")
        }
        assertEquals(30, ClashYaml.document(doc(30)).proxies!!.size)
        val error = rejected(doc(31))
        assertTrue(error.message, error.message!!.contains("max=30"))
    }

    /** 3000 proxies sharing scalar anchors, merged defaults and shared ws-opts import as Yaml(SafeConstructor()) loads them. */
    @Test
    fun honestSubscriptionWithAnchorsIsUnchanged() {
        val text = buildString {
            append("x-defaults: &defaults {udp: true, skip-cert-verify: false, tfo: false}\n")
            append("x-ws: &ws {path: /ray, headers: {Host: example.com}, max-early-data: 2048}\n")
            append("x-cipher: &cipher chacha20-ietf-poly1305\n")
            append("proxies:\n")
            for (i in 0 until 3000) {
                when {
                    i < 10 -> append("  - {<<: *defaults, name: m$i, type: ss, server: s$i.example.com, port: ${1000 + i}, cipher: *cipher, password: p}\n")
                    i < 30 -> append("  - {name: w$i, type: vmess, server: s$i.example.com, port: 443, uuid: u, alterId: 0, cipher: auto, network: ws, ws-opts: *ws}\n")
                    else -> append("  - {name: n$i, type: ss, server: s$i.example.com, port: ${1000 + i}, cipher: *cipher, password: p, udp: true}\n")
                }
            }
            append("proxy-groups:\n  - {name: auto, type: select, proxies: [m0, w10]}\n")
        }
        val expected = (Yaml(SafeConstructor()).load<Map<*, *>>(text)["proxies"] as List<*>).map { reference(it) as JsonObject }
        val started = System.nanoTime()
        val actual = ClashYaml.document(text).proxies!!
        println("3000 proxies with anchors read in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertEquals(3000, actual.size)
        assertEquals(expected, actual)
        assertEquals("example.com", actual[10].obj("ws-opts").obj("headers").string("Host"))
        assertTrue(actual[0].bool("udp"))
    }

    /** A large subscription without aliases is read on the streaming path and never reaches the limit. */
    @Test
    fun largePlainSubscriptionPasses() {
        val text = buildString {
            append("proxies:\n")
            for (i in 0 until 20_000) append("  - {name: n$i, type: ss, server: s$i.example.com, port: 443, cipher: aes-128-gcm, password: p, plugin-opts: {mode: websocket, host: h, path: /, tls: true}}\n")
        }
        assertEquals(20_000, ClashYaml.document(text).proxies!!.size)
    }

    /** The conversion before the limit: what an entry was before this change. */
    private fun reference(value: Any?): Any = when (value) {
        null -> JsonNull
        is Map<*, *> -> JsonObject().also { obj -> for ((k, v) in value) obj[k.toString()] = reference(v) }
        is List<*> -> JsonArray().also { arr -> for (v in value) arr.add(reference(v)) }
        else -> value
    }
}
