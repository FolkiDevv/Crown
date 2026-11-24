package io.nekohasekai.sagernet.simple.repair

import moe.matsuri.nb4a.utils.LogRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/**
 * The cleaning of the «Починка» report (design.md D8, connection-repair "Отчёт без секретов"): every line goes through
 * the log export's [LogRedactor] with the destinations hidden, then through a pass of its own that the log export does
 * not have: the addresses and domains of the user's servers ([servers], anywhere in the text), the host of any URL
 * (LogRedactor keeps it) and the subscription links ([subscriptions], replaced whole before anything else) become
 * labels: `<server-1>`, `<host-1>`, `<subscription-1>`, `<subscription-1-host>`. A name gets the same label everywhere
 * in one report, so the specialist still sees which lines speak of the same server.
 *
 * The secret values of the user's profiles ([secrets]: passwords, UUIDs, keys, gRPC service names… — [secretValues])
 * become `<redacted>` wherever they are left after the patterns, whatever the text around them: the cores also print
 * them in their own words (Xray: ``service name: `…` ``).
 *
 * One instance per report: the labels of the hosts not known in advance are given out as they are met.
 */
class ReportRedactor(
    servers: Collection<String>,
    subscriptions: Collection<String>,
    secrets: Collection<String> = emptyList(),
) {

    private val log = LogRedactor(hideDestinations = true)

    /** Normalized name → label, the servers first, then the hosts of subscription links, then hosts met in URLs. */
    private val labels = LinkedHashMap<String, String>()
    private var hosts = 0

    /** The subscription links, longest first so that a link that is the start of another is not cut out of it. */
    private val links: List<Pair<String, String>>

    /**
     * The known names and addresses anywhere in free text, as whole words, the longest first: dotted names, addresses
     * and single-label names ("mybox7", even "vpn": a server called so turns that word of the log into its label too —
     * the spec allows no address of the user's servers in the report).
     */
    private val names: Regex?

    /** The known secret values, anywhere, the longest first. */
    private val secretPattern: Regex? = secrets.map { it.trim() }.filter { it.length >= MIN_SECRET }.distinct()
        .sortedByDescending { it.length }.takeIf { it.isNotEmpty() }
        ?.let { known -> Regex("(?iu)" + known.joinToString("|") { Regex.escape(it) }) }

    init {
        // Every spelling of a server gets its label: IPv6 compressed / full / zero-padded, IDN as Unicode and punycode.
        servers.mapNotNull { normalize(it) ?: singleLabel(it) }.distinct().forEachIndexed { i, name ->
            for (form in forms(name)) labels.putIfAbsent(form, "<server-${i + 1}>")
        }
        val distinctLinks = subscriptions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        links = distinctLinks.mapIndexed { i, link -> link to "<subscription-${i + 1}>" }.sortedByDescending { it.first.length }
        distinctLinks.forEachIndexed { i, link ->
            hostOf(link)?.let(::normalize)?.let { host ->
                for (form in forms(host)) labels.putIfAbsent(form, "<subscription-${i + 1}-host>")
            }
        }
        // A subdomain of a server's domain names the server too: "cdn.vpn.example.com" becomes the label of
        // "vpn.example.com" (group 1 is dropped). Word edges are Unicode letters and digits (IDN, Cyrillic names).
        names = labels.keys.takeIf { it.isNotEmpty() }?.sortedByDescending { it.length }?.let { known ->
            // A colon may come before a name ("dialing to tcp:host:443"): only a letter, digit, dot or dash may not.
            Regex(
                "(?iu)(?<![\\p{L}\\p{N}_.\\-])((?:[a-z0-9_\\-]+\\.)*)(" + known.joinToString("|") { Regex.escape(it) } +
                    ")(?![\\p{L}\\p{N}_\\-]|\\.[\\p{L}\\p{N}_\\-])",
            )
        }
    }

    fun redact(line: String): String {
        // org.json writes "/" as "\/" on Android: URLs in JSON must look like URLs to the patterns.
        var s = line.replace("\\/", "/")
        for ((link, label) in links) s = s.replace(link, label)
        // Known secrets first, before LogRedactor rewrites anything inside them (an IP-like part becomes "<ip>"). The
        // mask has no angle brackets: LogRedactor still sees credentials of a URL as credentials.
        secretPattern?.let { pattern -> s = pattern.replace(s, MASK) }
        s = log.redact(s)
        s = SECRET.replace(s) { m ->
            val (_, quote, key, separator, value) = m.groupValues
            "$quote$key$quote$separator" + if (value.startsWith("[")) "[\"<redacted>\"]" else "\"<redacted>\""
        }
        // The URL up to its host is rebuilt, never copied: a password with an "@" (`user:pa@ssword@host`) is cut by
        // LogRedactor at its first "@", and the rest of it would otherwise stay before the host.
        s = URL.replace(s) { m ->
            val credentials = if (m.groups[CREDENTIALS] != null) "<redacted>@" else ""
            m.groupValues[1] + "://" + credentials + labelFor(m.groups[HOST]!!.value)
        }
        names?.let { pattern ->
            s = pattern.replace(s) { m ->
                val name = m.groupValues[2]
                labels[normalize(name) ?: name.lowercase()] ?: m.value
            }
        }
        return s.replace(MASK, "<redacted>")
    }

    /** Every line of [text]. */
    fun redactText(text: String): String = text.lineSequence().joinToString("\n") { redact(it) }

    /**
     * A JSON document (the final configuration, report.json), always returned as valid pretty JSON: in the parsed tree
     * the values under every key the line patterns would clean become the string "<redacted>", then every other string
     * — values and keys — goes through [redact] on its own, and the tree is written out. The patterns never see the
     * JSON syntax around a string, so they cannot take a closing quote or cut an array. Not JSON: [redactText].
     */
    fun redactJson(text: String): String {
        val tree = runCatching { JSONObject(text) }.getOrNull() ?: return redactText(text)
        redactTree(tree)
        return tree.toString(2).replace("\\/", "/")
    }

    private fun redactTree(node: JSONObject) {
        for (key in node.keys().asSequence().toList()) {
            val value = node.opt(key)
            val cleaned = if (isSecretKey(key)) "<redacted>" else redactValue(value)
            // A key may name a host (DNS "hosts", a rule keyed by domain): it is cleaned like a value; two keys that
            // clean to the same label keep both values.
            val newKey = redact(key)
            if (newKey == key) {
                node.put(key, cleaned)
            } else {
                node.remove(key)
                var unique = newKey
                var n = 2
                while (node.has(unique)) unique = "$newKey#${n++}"
                node.put(unique, cleaned)
            }
        }
    }

    private fun redactValue(value: Any?): Any? = when (value) {
        is String -> redact(value)
        is JSONObject -> value.also(::redactTree)
        is JSONArray -> value.also { array -> for (i in 0 until array.length()) array.put(i, redactValue(array.opt(i))) }
        else -> value
    }

    /**
     * The label of a URL's host. Every host but loopback gets one, also a name free text would not take for a host
     * (a single label such as "gateway" in `http://gateway:8080/`): inside a URL it is a host for sure.
     */
    private fun labelFor(host: String): String {
        val name = normalize(host) ?: host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (name.isEmpty() || isLoopback(name)) return host
        // Another spelling of a known address or name (IPv6 written otherwise, punycode) has its label already.
        labels[name]?.let { return it }
        forms(name).firstNotNullOfOrNull { labels[it] }?.let { return it }
        return labels.getOrPut(name) { "<host-${++hosts}>" }
    }

    companion object {
        private const val CREDENTIALS = 2
        private const val HOST = 3

        /** What a known secret becomes until the end of [redact]: private-use characters, no angle brackets. */
        private const val MASK = "\uE000redacted\uE000"

        private val LABEL_WORD = Regex("""(?:server|host|subscription)-\d+(?:-host)?""")

        private val SINGLE_LABEL = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]*[\\p{L}\\p{N}])?")

        /** Words a profile writes where an address would be, which are no host. */
        private val NOT_HOSTS = setOf(
            "localhost", "none", "auto", "direct", "block", "local", "any", "true", "false", "null", "default",
            // The words of the report's own labels: a server called so would eat them.
            "redacted", "uuid", "key", "ip", "domain",
        )

        /** The keys whose single-label value ("gateway") is a server; a transport's "host" or an SNI is not that sure. */
        private val PRIMARY_SERVER_KEYS = setOf(
            "server", "address", "endpoint", "peer", "remote", "remote_address", "server_address", "serveraddress",
            "hostname",
        )


        /**
         * A single-label host name ("gateway", "vpn"; also "gateway:8080"), lowercase, or null: not a number, not one of
         * [NOT_HOSTS].
         */
        fun singleLabel(raw: String): String? {
            var s = raw.trim()
            if (s.contains("://")) s = hostOf(s) ?: return null
            s = s.substringBefore('/')
            if (s.count { it == ':' } == 1) s = s.substringBefore(':')
            s = s.trimEnd('.').lowercase()
            if (s.length !in 2..63 || s in NOT_HOSTS || s.all(Char::isDigit) || LABEL_WORD.matches(s)) return null
            return s.takeIf { SINGLE_LABEL.matches(it) }
        }

        /** The spellings of one name: IPv6 compressed (RFC 5952), full and zero-padded; IDN as punycode and Unicode. */
        fun forms(name: String): Set<String> {
            val out = linkedSetOf(name)
            if (name.contains(':')) {
                out += ipv6Forms(name)
            } else {
                runCatching { IDN.toASCII(name, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let(out::add)
                runCatching { IDN.toUnicode(name, IDN.ALLOW_UNASSIGNED).lowercase() }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out
        }

        private fun ipv6Forms(s: String): Set<String> {
            if (s.count { it == ':' } < 2 || s.any { !(it.isDigit() || it in 'a'..'f' || it == ':') }) return emptySet()
            val halves = s.split("::")
            if (halves.size > 2) return emptySet()
            val head = halves[0].split(':').filter { it.isNotEmpty() }
            val tail = if (halves.size == 2) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
            val missing = 8 - head.size - tail.size
            if (if (halves.size == 1) missing != 0 else missing < 1) return emptySet()
            val groups = (head + List(missing) { "0" } + tail).map { group ->
                group.takeIf { it.length <= 4 }?.toIntOrNull(16) ?: return emptySet()
            }
            fun hex(list: List<Int>) = list.joinToString(":") { it.toString(16) }
            var bestStart = -1
            var bestLength = 1
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) {
                    i++
                    continue
                }
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLength) {
                    bestStart = i
                    bestLength = j - i
                }
                i = j
            }
            val compressed = if (bestStart < 0) hex(groups) else {
                hex(groups.subList(0, bestStart)) + "::" + hex(groups.subList(bestStart + bestLength, 8))
            }
            return setOf(hex(groups), groups.joinToString(":") { it.toString(16).padStart(4, '0') }, compressed)
        }

        /** Shorter values ("/ws", "none") are words of the config, not secrets: replacing them would blur the report. */
        private const val MIN_SECRET = 6

        /**
         * Secrets under the keys LogRedactor does not know: Xray writes them in camelCase (`shortId`, `serverName`, the
         * gRPC `serviceName`, `verifyPeerCertByName`), and the mKCP `seed` and the ML-DSA-65 verify key have no "key" /
         * "password" in their names.
         */
        private val SECRET = Regex(
            """(?i)("?)(short[_\-]?ids?|server[_\-]?names?|service[_\-]?names?|verify[_\-]?peer[_\-]?cert[_\-]?by[_\-]?names?|seed|mldsa65[_\-]?verify)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|\[(?:[^\[\]"]|"(?:[^"\\]|\\.)*")*\]|[^\s,;&}\]]+)"""
        )

        /**
         * A URL's credentials (group 2, up to the last "@" before the host) and host (group 3): LogRedactor leaves
         * `scheme://<redacted>@host/<redacted>`, so the credentials may hold the label's angle brackets, and the rest of a
         * password with an "@" in it. A host is a bracketed IPv6 address or a name / IPv4 address up to the port.
         */
        private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/?#"']*@)?(\[[0-9A-Fa-f:.%]+\]|[^\s/?#"'<>:@\[\]\\]+)""")

        /** The keys whose values the line patterns clean (LogRedactor's SECRET, SERVER and Wi-Fi keys, and [SECRET]). */
        private val SECRET_KEY = Regex(
            """(?i)[\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|psk|short_id)[\w\-]*|server|server_name|sni|host|address|path|service[_\-]?names?|(?:wifi_)?b?ssid|short[_\-]?ids?|server[_\-]?names?|verify[_\-]?peer[_\-]?cert[_\-]?by[_\-]?names?|seed|mldsa65[_\-]?verify"""
        )

        fun isSecretKey(key: String): Boolean = SECRET_KEY.matches(key)

        /** The keys of a profile whose values name a server: its address, the TLS name, the transport's host. */
        private val SERVER_KEYS = setOf(
            "server", "server_name", "servername", "sni", "host", "hostname", "address", "peer", "endpoint",
            "server_address", "serveraddress", "remote", "remote_address", "ip", "verifypeercertbyname",
            "verify_peer_cert_by_name",
        )

        /**
         * The secret values of one profile ([outboundJson]): every string (also in arrays) at least [MIN_SECRET] long
         * under a key the patterns treat as secret, or the Xray user `id`, except the server names — those get labels
         * from [serverNames] instead.
         */
        fun secretValues(outboundJson: String): Set<String> {
            val out = LinkedHashSet<String>()
            val root = runCatching { JSONObject(outboundJson) }.getOrNull() ?: return out
            fun addValue(value: Any?) {
                when (value) {
                    is String -> value.trim().takeIf { it.length >= MIN_SECRET }?.let(out::add)
                    is JSONArray -> for (i in 0 until value.length()) addValue(value.opt(i))
                }
            }
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { key ->
                        val value = node.opt(key)
                        val lower = key.lowercase()
                        if (lower !in SERVER_KEYS && (lower == "id" || isSecretKey(key))) addValue(value)
                        walk(value)
                    }

                    is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
            walk(root)
            return out
        }

        /** "Host", "host:port", "[v6]:port", "scheme://host:port/path", "1.2.3.4/32" → the host, lowercase; null if none. */
        fun normalize(raw: String): String? {
            var s = raw.trim()
            if (s.contains("://")) s = hostOf(s) ?: return null
            s = s.substringBefore('/')
            s = when {
                s.startsWith("[") -> s.substring(1).substringBefore(']')
                s.count { it == ':' } == 1 -> s.substringBefore(':')
                else -> s
            }
            s = s.trimEnd('.').lowercase()
            if (s.isEmpty() || s.length < 3) return null
            // A host: a name with a dot, an IPv4 address or an IPv6 address; not a word such as "auto" or "none".
            val ipv6 = s.contains(':') && s.all { it.isLetterOrDigit() || it == ':' || it == '.' }
            val name = s.contains('.') && s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
            if (!ipv6 && !name) return null
            if (s == "0.0.0.0" || s == "::") return null
            return s
        }

        private fun isLoopback(name: String) = name == "localhost" || name == "::1" || name.startsWith("127.")

        /** The host of a URL, null when it has none. */
        fun hostOf(url: String): String? = URL.find(url)?.groups?.get(HOST)?.value

        /**
         * The server names of one profile as the app stores it ([outboundJson], the desktop's profile JSON): every string
         * under a [SERVER_KEYS] key at any depth (also "host:port" lists and arrays), plus [server] itself. A single-label
         * name ("gateway") counts as the server's address itself or under [PRIMARY_SERVER_KEYS].
         */
        fun serverNames(server: String, outboundJson: String): Set<String> {
            val out = LinkedHashSet<String>()
            (normalize(server) ?: singleLabel(server))?.let(out::add)
            val root = runCatching { JSONObject(outboundJson) }.getOrNull() ?: return out
            fun addValue(value: Any?, primary: Boolean) {
                when (value) {
                    is String -> value.split(',', ' ', '\n').forEach { part ->
                        (normalize(part) ?: if (primary) singleLabel(part) else null)?.let(out::add)
                    }

                    is JSONArray -> for (i in 0 until value.length()) addValue(value.opt(i), primary)
                }
            }
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { key ->
                        val value = node.opt(key)
                        val lower = key.lowercase()
                        if (lower in SERVER_KEYS) addValue(value, lower in PRIMARY_SERVER_KEYS)
                        walk(value)
                    }

                    is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
                }
            }
            walk(root)
            return out
        }
    }
}
