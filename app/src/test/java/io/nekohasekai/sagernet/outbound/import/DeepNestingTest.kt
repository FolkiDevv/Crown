package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.yaml.snakeyaml.error.YAMLException

/** Imported text nested thousands of levels deep must fail as a parse error, not as a StackOverflowError. */
class DeepNestingTest {

    /**
     * [block] on a thread with the stack of Android's background threads (DefaultDispatcher gets 1036 KB), so the
     * result does not depend on the test runner's stack; a StackOverflowError fails the test.
     */
    private fun <T> onAppStack(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, { result = runCatching(block) }, "deep-nesting", 1024L * 1024)
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    private fun brackets(levels: Int, inner: String = ""): String = "[".repeat(levels) + inner + "]".repeat(levels)

    private fun clashRejected(text: String): YAMLException = onAppStack {
        val started = System.nanoTime()
        try {
            ClashYaml.document(text)
        } catch (e: YAMLException) {
            val millis = (System.nanoTime() - started) / 1_000_000
            println("rejected in $millis ms: ${e.message}")
            assertTrue("took $millis ms", millis < 1000)
            return@onAppStack e
        }
        fail("the document was accepted")
        throw AssertionError()
    }

    private fun assertTooDeep(error: YAMLException) =
        assertTrue(error.message, error.message!!.contains("nested too deeply"))

    // ------------------------------------------------------------------------------------------------ JSON

    @Test
    fun deepJsonIsRefused() = onAppStack {
        assertNull(JsonInput.parseValue(brackets(50_000)))
        assertNull(JsonInput.parseObjectOrNull("{\"a\":".repeat(50_000) + "1" + "}".repeat(50_000)))
        assertTrue(JsonInput.parseObject("{\"a\":".repeat(20_000) + "1" + "}".repeat(20_000)).isEmpty())
    }

    /** The limit is the JSON editor's: 96 nested collections parse, 97 do not, in arrays and objects alike. */
    @Test
    fun jsonDepthLimit() = onAppStack {
        var value = JsonInput.parseValue(brackets(96, "1"))
        var levels = 0
        while (value is JsonArray) {
            levels++
            value = value.firstOrNull()
        }
        assertEquals(96, levels)
        assertNull(JsonInput.parseValue(brackets(97, "1")))
        assertNull(JsonInput.parseValue(brackets(97)))

        assertNotNull(JsonInput.parseObjectOrNull("{\"a\":".repeat(96) + "1" + "}".repeat(96)))
        assertNull(JsonInput.parseObjectOrNull("{\"a\":".repeat(97) + "1" + "}".repeat(97)))
        assertNull(JsonInput.parseObjectOrNull("{\"a\":" + brackets(96) + "}"))
    }

    /** org.json's own lexing decides the depth: a quote inside a comment or a single-quoted string hides nothing. */
    @Test
    fun lenientJsonCannotHideItsDepth() = onAppStack {
        assertNull(JsonInput.parseValue("[ /* \" */ " + brackets(5_000) + " /* \" */ ]"))
        assertNull(JsonInput.parseValue("['\"', " + brackets(5_000) + ", '\"']"))
        // A NUL is what org.json's nextClean returns at the end: it must not let the next bracket through.
        assertNull(JsonInput.parseValue("[".repeat(96) + "\u0000" + brackets(5_000) + "]".repeat(96)))
        assertNull(JsonInput.parseValue("[".repeat(96) + "\u0000[".repeat(5_000) + "]".repeat(5_096)))
    }

    @Test
    fun ordinaryJsonIsUnchanged() {
        val doc = JsonInput.parseObjectOrNull("""{"outbounds":[{"type":"vless","tls":{"utls":{"enabled":true}}}],"n":1.5}""")
        val outbound = (doc!!["outbounds"] as JsonArray)[0] as JsonObject
        assertEquals("vless", outbound["type"])
        assertEquals(true, ((outbound["tls"] as JsonObject)["utls"] as JsonObject)["enabled"])
        assertNull(JsonInput.parseObjectOrNull("[1]"))
        assertNull(JsonInput.parseObjectOrNull("not json"))
        assertEquals("text", JsonInput.parseValue("\"text\""))
    }

    // ------------------------------------------------------------------------------------------------ YAML

    @Test
    fun deepFlowSequenceIsRejected() = assertTooDeep(clashRejected("proxies: " + brackets(50_000)))

    @Test
    fun deepFlowMappingIsRejected() =
        assertTooDeep(clashRejected("proxies: [" + "{a: ".repeat(50_000) + "1" + "}".repeat(50_000) + "]"))

    @Test
    fun deepBlockSequenceIsRejected() = assertTooDeep(clashRejected("proxies:\n" + "- ".repeat(50_000) + "x\n"))

    @Test
    fun deepValueInsideAnEntryIsRejected() =
        assertTooDeep(clashRejected("proxies:\n  - {name: a, type: ss, x: ${brackets(50_000)}}\n"))

    /** An anchor sends the document to the whole load, which composes it with snakeyaml's own Composer. */
    @Test
    fun deepDocumentWithAnAnchorIsRejected() =
        assertTooDeep(clashRejected("x: &x 1\nproxies: " + brackets(50_000) + "\n"))

    /** Each anchor is 95 levels deep in the text, the 48 aliases chain them into one value thousands of levels deep. */
    @Test
    fun aliasesCannotNestPastTheLimit() {
        val text = buildString {
            append("a1: &a1 ${brackets(95, "1")}\n")
            for (i in 2..48) append("a$i: &a$i ${brackets(95, "*a${i - 1}")}\n")
            append("proxies: [{name: n, type: ss, x: *a48}]\n")
        }
        assertTooDeep(clashRejected(text))
    }

    /**
     * Anchors defined inside the entry are constructed once and reused, so the constructor stays shallow while the
     * value it builds nests thousands of levels deep: the conversion into entries counts its own depth.
     */
    @Test
    fun aliasesInsideAnEntryCannotNestPastTheLimit() {
        val text = buildString {
            append("proxies:\n  - name: n\n    type: ss\n    a1: &a1 ${brackets(90, "1")}\n")
            for (i in 2..48) append("    a$i: &a$i ${brackets(90, "*a${i - 1}")}\n")
        }
        assertTooDeep(clashRejected(text))
    }

    /**
     * snakeyaml follows merge keys by a recursion of its own: 48 anchors, each 90 merges deep around the one before,
     * make a chain of 4320 merges that none of the nesting counts sees.
     */
    @Test
    fun mergeChainsCannotNestPastTheLimit() {
        val text = buildString {
            append("a1: &a1 {x: 1}\n")
            for (i in 2..48) append("a$i: &a$i ${"{<<: ".repeat(90)}*a${i - 1}${"}".repeat(90)}\n")
            append("proxies: [{name: n, type: ss}]\n<<: *a48\n")
        }
        assertTooDeep(clashRejected(text))
    }

    /** A mapping that merges itself, directly or through another, is refused like an alias inside its own anchor. */
    @Test
    fun mergeCyclesAreRejected() {
        for (text in listOf("a: &a {<<: *a, name: x}\nproxies: [*a]\n", "&r {<<: *r, proxies: [{name: y}]}\n")) {
            val error = clashRejected(text)
            assertTrue(error.message, error.message!!.contains("contains itself"))
        }
    }

    @Test
    fun ordinaryMergesAreUnchanged() = onAppStack {
        val text = "base: &base {type: ss, port: 1}\nmid: &mid {<<: *base, cipher: x}\n" +
            "proxies:\n  - {<<: *mid, name: a}\n  - {<<: [*base, {udp: true}], name: b}\n"
        val proxies = ClashYaml.document(text).proxies!!
        assertEquals(listOf("a", "b"), proxies.map { it["name"] })
        assertEquals("ss", proxies[0]["type"])
        assertEquals("x", proxies[0]["cipher"])
        assertEquals(true, proxies[1]["udp"])
    }

    /** Root, `proxies`, the entry and 93 lists make 96 levels; one more fails, with or without anchors. */
    @Test
    fun clashDepthLimit() = onAppStack {
        for (prefix in listOf("", "x: &x 1\n")) {
            val proxies = ClashYaml.document("${prefix}proxies:\n  - {name: n, type: ss, x: ${brackets(93, "1")}}\n").proxies!!
            var value = proxies.single()["x"]
            var levels = 0
            while (value is JsonArray) {
                levels++
                value = value.firstOrNull()
            }
            assertEquals(93, levels)
            try {
                ClashYaml.document("${prefix}proxies:\n  - {name: n, type: ss, x: ${brackets(94, "1")}}\n")
                fail("97 levels were accepted ($prefix)")
            } catch (e: YAMLException) {
                assertTooDeep(e)
            }
        }
    }

    @Test
    fun ordinaryClashIsUnchanged() = onAppStack {
        val text = """
            opts: &opts {path: /ws, headers: {Host: example.com}}
            proxies:
              - name: a
                type: vmess
                ws-opts: *opts
              - {name: b, type: ss, plugin-opts: {mode: websocket, mux: [1, [2, [3]]]}}
        """.trimIndent()
        val proxies = ClashYaml.document(text).proxies!!
        assertEquals(2, proxies.size)
        assertEquals("example.com", ((proxies[0]["ws-opts"] as JsonObject)["headers"] as JsonObject)["Host"])
        assertEquals(2, ClashYaml.document(text.replace("&opts ", "").replace("*opts", "{path: /ws}")).proxies!!.size)
    }
}
