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

    /**
     * The known secret values, longest first. Every length is masked — a password "12345" or "7" too: [MIN_ANYWHERE]
     * characters and more anywhere, shorter ones as a whole token (not inside a word or a number: "17", "7ms", "v7",
     * "1.7", "10.0.0.7" stay). A user with a one-character password sees that character standing alone in the log
     * masked as well: privacy first.
     */
    private val secretSources: List<String> = secrets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }.let { known ->
            fun alternatives(range: IntRange) = known.filter { it.length in range }.joinToString("|") { Regex.escape(it) }
            listOfNotNull(
                alternatives(MIN_ANYWHERE..Int.MAX_VALUE).takeIf { it.isNotEmpty() },
                alternatives(1 until MIN_ANYWHERE).takeIf { it.isNotEmpty() }?.let {
                    "(?<![\\p{L}\\p{N}_]|\\p{N}\\.)(?:$it)(?![\\p{L}\\p{N}_]|\\.\\p{N})"
                },
            )
        }

    /** The secrets before LogRedactor (see [redact]). */
    private val secretPatterns: List<Regex> = secretSources.map { Regex("(?iu)$it") }

    /** The secrets once more at the end, the labels in the text (group 1) skipped whole. */
    private val leftoverSecretPatterns: List<Regex> = secretSources.map { Regex("(?iu)($LABEL)|(?:$it)") }

    init {
        // Every spelling of a server gets its label: IPv6 compressed / full / zero-padded, IDN as Unicode and punycode.
        servers.mapNotNull { normalize(it) ?: singleLabel(it) }.distinct().forEachIndexed { i, name ->
            for (form in forms(name)) labels.putIfAbsent(form, "<server-${i + 1}>")
        }
        val distinctLinks = subscriptions.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        links = distinctLinks.mapIndexed { i, link -> link to "<subscription-${i + 1}>" }.sortedByDescending { it.first.length }
        distinctLinks.forEachIndexed { i, link ->
            hostOf(link)?.let { normalize(it) ?: singleLabel(it) }?.let { host ->
                for (form in forms(host)) labels.putIfAbsent(form, "<subscription-${i + 1}-host>")
            }
        }
        // Group 1: a label already in the text (LogRedactor's or this pass's) is skipped whole, so a server called
        // "server-1", "redacted" or "x" never eats into one. Otherwise a known name as a whole word; a subdomain of a
        // server's domain names the server too: "cdn.vpn.example.com" becomes the label of "vpn.example.com" (group 2 is
        // dropped). Word edges are Unicode letters and digits (IDN, Cyrillic names); a colon may come before a name
        // ("dialing to tcp:host:443").
        names = labels.keys.takeIf { it.isNotEmpty() }?.sortedByDescending { it.length }?.let { known ->
            Regex(
                "(?iu)($LABEL)|(?<![\\p{L}\\p{N}_.\\-])((?:[a-z0-9_\\-]+\\.)*)(" +
                    known.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}_\\-]|\\.[\\p{L}\\p{N}_\\-])",
            )
        }
    }

    fun redact(line: String): String {
        // org.json writes "/" as "\/" on Android: URLs in JSON must look like URLs to the patterns.
        var s = line.replace("\\/", "/")
        for ((link, label) in links) s = s.replace(link, label)
        // Known secrets first, before LogRedactor rewrites anything inside them (an IP-like part becomes "<ip>"). The
        // mask has no angle brackets: LogRedactor still sees credentials of a URL as credentials.
        // A secret that is also a scheme or network word right before ":" ("https://…", "tcp:host") is left for the end:
        // masked here it would hide the URL / address from the passes below, which then could not clean it.
        for (pattern in secretPatterns) {
            val text = s
            s = pattern.replace(text) { m ->
                val beforeColon = m.range.last + 1 < text.length && text[m.range.last + 1] == ':'
                if (beforeColon && SCHEME_WORD.matches(m.value)) m.value else MASK
            }
        }
        s = log.redact(s)
        // From here on a masked secret is a label like the others (protected from the name pass).
        s = s.replace(MASK, "<redacted>")
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
                if (m.groups[1] != null) return@replace m.value
                val name = m.groupValues[3]
                labels[normalize(name) ?: name.lowercase()] ?: m.value
            }
        }
        // A configured IPv6 server in a spelling not listed above ("fd00:0::1" for "fd00::1"): compared by value.
        // A network prefix may come before it ("dialing to tcp:fd00:0::1"); "fdab:beef::1" is tried whole first. After
        // such a prefix LogRedactor's IPv6 rule does not see an address at all, so a public one is masked here.
        s = IPV6_TOKEN.replace(s) { m ->
            // A token that is an address as a whole has no prefix ("fdab:beef::1", "fd00:1234::5").
            val whole = canonicalIpv6(m.value.lowercase())
            val prefix = if (whole != null) null else m.groups[1]?.value
            val canonical = whole ?: canonicalIpv6(m.groupValues[2].lowercase()) ?: return@replace m.value
            val label = labels[canonical] ?: if (prefix != null && !isPrivateIpv6(canonical)) "<ip>" else return@replace m.value
            if (prefix != null) "$prefix:$label" else label
        }
        // The destinations Xray names in its own words, which LogRedactor's destination lines do not cover
        // ("TCP Connect request to tcp:site:443", "taking detour [proxy] for [tcp:site:443]").
        if (XRAY_DESTINATION_LINE.containsMatchIn(s)) {
            s = DOMAIN.replace(s) { m -> "<domain>." + m.groupValues[1] }
        }
        // What the first secret pass left (a secret spelled like a scheme), now that URLs and addresses are labelled.
        for (pattern in leftoverSecretPatterns) {
            s = pattern.replace(s) { m -> if (m.groups[1] != null) m.value else "<redacted>" }
        }
        return s
    }

    /** As LogRedactor counts them: unspecified, loopback, link-local, unique local and multicast are not public. */
    private fun isPrivateIpv6(canonical: String): Boolean = canonical == "::" || canonical == "::1" ||
        canonical.startsWith("fe8") || canonical.startsWith("fe9") || canonical.startsWith("fea") ||
        canonical.startsWith("feb") || canonical.startsWith("fc") || canonical.startsWith("fd") || canonical.startsWith("ff")

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
        if (name.isEmpty()) return host
        // A configured server keeps its label even on loopback; another spelling of a known address or name (IPv6
        // written otherwise, punycode) has its label already.
        labels[name]?.let { return it }
        forms(name).firstNotNullOfOrNull { labels[it] }?.let { return it }
        if (isLoopback(name)) return host
        return labels.getOrPut(name) { "<host-${++hosts}>" }
    }

    companion object {
        private const val CREDENTIALS = 2
        private const val HOST = 3

        /** What a known secret becomes until the end of [redact]: private-use characters, no angle brackets. */
        private const val MASK = "\uE000redacted\uE000"

        /** The labels LogRedactor and this pass put in the text; the name pass skips them whole. */
        private const val LABEL = """<(?:redacted|uuid|ip|key|domain|(?:server|host|subscription)-\d+(?:-host)?)>"""

        /**
         * A word that can be a URL scheme or a network prefix before ":" ("https", "tcp", "vless+ws"). No dot: a secret
         * with an IP-like part ("pw-1.2.3.4") must be masked before LogRedactor turns that part into "<ip>".
         */
        private val SCHEME_WORD = Regex("""[a-zA-Z][a-zA-Z0-9+\-]*""")

        private val SINGLE_LABEL = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}_\\-]*[\\p{L}\\p{N}])?")

        /**
         * Values a profile writes under an address key where there is no address. Only these, and bare numbers (ports),
         * are skipped there; the server's own address is kept whatever it is called.
         */
        private val PLACEHOLDERS = setOf(
            "none", "auto", "direct", "block", "any", "true", "false", "null", "default",
        )

        private fun isPlaceholder(name: String) = name in PLACEHOLDERS || name.all(Char::isDigit)


        /**
         * A single-label host name ("gateway", "vpn", "x", "server-1"; also "gateway:8080"), lowercase, or null when
         * [raw] is no such name.
         */
        fun singleLabel(raw: String): String? {
            var s = raw.trim()
            if (s.contains("://")) s = hostOf(s) ?: return null
            s = s.substringBefore('/')
            if (s.count { it == ':' } == 1) s = s.substringBefore(':')
            s = s.trimEnd('.').lowercase()
            if (s.length !in 1..63) return null
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

        /** The eight groups of an IPv6 address written in lowercase hex (no embedded IPv4), null if [s] is none. */
        private fun ipv6Groups(s: String): List<Int>? {
            if (s.count { it == ':' } < 2 || s.any { !(it in '0'..'9' || it in 'a'..'f' || it == ':') }) return null
            val halves = s.split("::")
            if (halves.size > 2) return null
            val head = halves[0].split(':').filter { it.isNotEmpty() }
            val tail = if (halves.size == 2) halves[1].split(':').filter { it.isNotEmpty() } else emptyList()
            val missing = 8 - head.size - tail.size
            if (if (halves.size == 1) missing != 0 else missing < 1) return null
            if (halves.size == 1 && (s.startsWith(':') || s.endsWith(':'))) return null
            return (head + List(missing) { "0" } + tail).map { group ->
                group.takeIf { it.length <= 4 }?.toIntOrNull(16) ?: return null
            }
        }

        /** The RFC 5952 spelling of an IPv6 address (lowercase hex), null if [s] is none. */
        fun canonicalIpv6(s: String): String? = ipv6Groups(s)?.let { ipv6Spellings(it).last() }

        private fun ipv6Forms(s: String): Set<String> = ipv6Groups(s)?.let(::ipv6Spellings)?.toSet() ?: emptySet()

        /** Full, zero-padded and compressed (RFC 5952, last) spellings of [groups]. */
        private fun ipv6Spellings(groups: List<Int>): List<String> {
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
            return listOf(hex(groups), groups.joinToString(":") { it.toString(16).padStart(4, '0') }, compressed)
        }

        /** An IPv6-looking token of free text (the colon-hex run between non-word characters). */
        private val IPV6_TOKEN = Regex(
            """(?<![\p{L}\p{N}_:.])(?:([a-zA-Z][a-zA-Z0-9+\-]{0,10}):)?([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![\p{L}\p{N}_:])"""
        )

        /** Xray's lines that name where a connection goes (its own wording, beyond LogRedactor's destination lines). */
        private val XRAY_DESTINATION_LINE = Regex(
            """(?i)connect request to|taking detour|dialing|creating connection to|tunnel(?:l)?ing request|received request for|sniffed domain|accepted (?:tcp|udp):"""
        )

        /** A domain name outside a label: the name is hidden, the top-level domain stays (as in LogRedactor). */
        private val DOMAIN = Regex("""(?<![\w.\-<])(?:[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+([a-z]{2,63})\.?(?![\w\-])""")

        /** Secret values this long are replaced anywhere, even inside a longer word; shorter ones as a whole token. */
        private const val MIN_ANYWHERE = 6

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
         * The secret values of one profile ([outboundJson]): every nonempty string or number (also in arrays) under a key
         * the patterns treat as secret, or the Xray user `id`, whatever its length — a password "12345" or "7" too. Not
         * credentials by their field: the server names (they get labels from [serverNames]) and paths (a transport path,
         * a key file), kept only from [MIN_ANYWHERE] characters ("/ws" is a word of the config).
         */
        fun secretValues(outboundJson: String): Set<String> {
            val out = LinkedHashSet<String>()
            val root = runCatching { JSONObject(outboundJson) }.getOrNull() ?: return out
            fun addValue(value: Any?, path: Boolean) {
                when (value) {
                    is String -> value.trim().takeIf { it.isNotEmpty() && (!path || it.length >= MIN_ANYWHERE) }?.let(out::add)
                    is Number -> if (!path) out += value.toString()
                    is JSONArray -> for (i in 0 until value.length()) addValue(value.opt(i), path)
                }
            }
            fun walk(node: Any?) {
                when (node) {
                    is JSONObject -> node.keys().forEach { key ->
                        val value = node.opt(key)
                        val lower = key.lowercase()
                        if (lower !in SERVER_KEYS && (lower == "id" || isSecretKey(key))) addValue(value, lower.endsWith("path"))
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
         * under a [SERVER_KEYS] key at any depth (also "host:port" lists and arrays), plus [server] itself, whatever it is
         * called. Under the keys only a [PLACEHOLDERS] word or a bare number is skipped: it stands for no address.
         */
        fun serverNames(server: String, outboundJson: String): Set<String> {
            val out = LinkedHashSet<String>()
            (normalize(server) ?: singleLabel(server))?.let(out::add)
            val root = runCatching { JSONObject(outboundJson) }.getOrNull() ?: return out
            fun addValue(value: Any?) {
                when (value) {
                    is String -> value.split(',', ' ', '\n').forEach { part ->
                        (normalize(part) ?: singleLabel(part)?.takeUnless(::isPlaceholder))?.let(out::add)
                    }

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
