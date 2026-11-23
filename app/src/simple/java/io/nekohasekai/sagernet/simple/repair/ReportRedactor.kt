package io.nekohasekai.sagernet.simple.repair

import moe.matsuri.nb4a.utils.LogRedactor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The cleaning of the «Починка» report (design.md D8, connection-repair "Отчёт без секретов"): every line goes through
 * the log export's [LogRedactor] with the destinations hidden, then through a pass of its own that the log export does
 * not have: the addresses and domains of the user's servers ([servers], anywhere in the text), the host of any URL
 * (LogRedactor keeps it) and the subscription links ([subscriptions], replaced whole before anything else) become
 * labels: `<server-1>`, `<host-1>`, `<subscription-1>`, `<subscription-1-host>`. A name gets the same label everywhere
 * in one report, so the specialist still sees which lines speak of the same server.
 *
 * One instance per report: the labels of the hosts not known in advance are given out as they are met.
 */
class ReportRedactor(servers: Collection<String>, subscriptions: Collection<String>) {

    private val log = LogRedactor(hideDestinations = true)

    /** Normalized name → label, the servers first, then the hosts of subscription links, then hosts met in URLs. */
    private val labels = LinkedHashMap<String, String>()
    private var hosts = 0

    /** The subscription links, longest first so that a link that is the start of another is not cut out of it. */
    private val links: List<Pair<String, String>>

    /** The known names in free text, the longest first. */
    private val names: Regex?

    init {
        servers.mapNotNull(::normalize).distinct().forEachIndexed { i, name -> labels[name] = "<server-${i + 1}>" }
        val distinctLinks = subscriptions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        links = distinctLinks.mapIndexed { i, link -> link to "<subscription-${i + 1}>" }.sortedByDescending { it.first.length }
        distinctLinks.forEachIndexed { i, link ->
            hostOf(link)?.let(::normalize)?.let { labels.putIfAbsent(it, "<subscription-${i + 1}-host>") }
        }
        // A subdomain of a server's domain names the server too: "cdn.vpn.example.com" becomes the label of
        // "vpn.example.com" (group 1 is dropped).
        names = labels.keys.takeIf { it.isNotEmpty() }?.sortedByDescending { it.length }?.let { known ->
            Regex(
                "(?<![\\w.:\\-])((?:[a-z0-9_\\-]+\\.)*)(" + known.joinToString("|") { Regex.escape(it) } +
                    ")(?![\\w\\-]|\\.[\\w\\-])",
                RegexOption.IGNORE_CASE,
            )
        }
    }

    fun redact(line: String): String {
        // org.json writes "/" as "\/" on Android: URLs in JSON must look like URLs to the patterns.
        var s = line.replace("\\/", "/")
        for ((link, label) in links) s = s.replace(link, label)
        s = log.redact(s)
        s = SECRET.replace(s) { m ->
            val (_, quote, key, separator, value) = m.groupValues
            "$quote$key$quote$separator" + if (value.startsWith("[")) "[\"<redacted>\"]" else "\"<redacted>\""
        }
        val cleaned = s
        s = URL.replace(cleaned) { m ->
            val host = m.groups[HOST]!!
            cleaned.substring(m.range.first, host.range.first) + labelFor(host.value)
        }
        names?.let { pattern -> s = pattern.replace(s) { m -> labels[normalize(m.groupValues[2])] ?: m.value } }
        return s
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

    private fun labelFor(host: String): String {
        val name = normalize(host) ?: return host
        if (isLoopback(name)) return host
        return labels.getOrPut(name) { "<host-${++hosts}>" }
    }

    companion object {
        private const val HOST = 3

        /**
         * Secrets under the keys LogRedactor does not know: Xray writes them in camelCase (`shortId`, `serverName`, the
         * gRPC `serviceName`), and the mKCP `seed` and the ML-DSA-65 verify key have no "key" / "password" in their names.
         */
        private val SECRET = Regex(
            """(?i)("?)(short[_\-]?ids?|server[_\-]?names?|service[_\-]?names?|seed|mldsa65[_\-]?verify)\1(\s*[:=]\s*)("(?:[^"\\]|\\.)*"|\[(?:[^\[\]"]|"(?:[^"\\]|\\.)*")*\]|[^\s,;&}\]]+)"""
        )

        /**
         * A URL's host (group 3): LogRedactor leaves `scheme://<redacted>@host/<redacted>`, so the credentials may hold
         * the label's angle brackets. A host is a bracketed IPv6 address or a name / IPv4 address up to the port.
         */
        private val URL = Regex("""\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/"']*@)?(\[[0-9A-Fa-f:.%]+\]|[^\s/?#"'<>:@\[\]\\]+)""")

        /** The keys whose values the line patterns clean (LogRedactor's SECRET, SERVER and Wi-Fi keys, and [SECRET]). */
        private val SECRET_KEY = Regex(
            """(?i)[\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|psk|short_id)[\w\-]*|server|server_name|sni|host|address|path|service[_\-]?names?|(?:wifi_)?b?ssid|short[_\-]?ids?|server[_\-]?names?|seed|mldsa65[_\-]?verify"""
        )

        fun isSecretKey(key: String): Boolean = SECRET_KEY.matches(key)

        /** The keys of a profile whose values name a server: its address, the TLS name, the transport's host. */
        private val SERVER_KEYS = setOf(
            "server", "server_name", "servername", "sni", "host", "hostname", "address", "peer", "endpoint",
            "server_address", "serveraddress", "remote", "remote_address", "ip",
        )

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
         * under a [SERVER_KEYS] key at any depth (also "host:port" lists and arrays), plus [server] itself.
         */
        fun serverNames(server: String, outboundJson: String): Set<String> {
            val out = LinkedHashSet<String>()
            normalize(server)?.let(out::add)
            val root = runCatching { JSONObject(outboundJson) }.getOrNull() ?: return out
            fun addValue(value: Any?) {
                when (value) {
                    is String -> value.split(',', ' ', '\n').forEach { part -> normalize(part)?.let(out::add) }
                    is JSONArray -> for (i in 0 until value.length()) addValue(value.opt(i))
                }
            }
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { key ->
                        val value = node.opt(key)
                        if (key.lowercase() in SERVER_KEYS) addValue(value)
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
