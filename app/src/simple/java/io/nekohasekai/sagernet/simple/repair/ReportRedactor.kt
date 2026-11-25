package io.nekohasekai.sagernet.simple.repair

import moe.matsuri.nb4a.utils.LogRedactor
import org.json.JSONArray
import org.json.JSONObject
import java.net.IDN

/**
 * The cleaning of the «Починка» report (design.md D8, connection-repair "Отчёт без секретов"): every line goes through a
 * pass of its own that the log export does not have and then through the log export's [LogRedactor] with the
 * destinations hidden (see [redact]). The own pass turns the addresses and domains of the user's servers ([servers],
 * anywhere in the text), the host of any URL and the subscription links ([subscriptions]) into labels: `<server-1>`,
 * `<host-1>`, `<subscription-1>`, `<subscription-1-host>`. A name gets the same label everywhere in one report, so the
 * specialist still sees which lines speak of the same server.
 *
 * The secret values of the user's profiles ([secrets]: passwords, UUIDs, keys, gRPC service names… — [secretValues])
 * become `<redacted>` wherever they are, whatever the text around them: the cores also print them in their own words
 * (Xray: ``service name: `…` ``).
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
    private fun secretSourcesOf(values: List<String>): List<String> {
        fun alternatives(range: IntRange) = values.filter { it.length in range }.joinToString("|") { Regex.escape(it) }
        return listOfNotNull(
            alternatives(MIN_ANYWHERE..Int.MAX_VALUE).takeIf { it.isNotEmpty() },
            alternatives(1 until MIN_ANYWHERE).takeIf { it.isNotEmpty() }?.let {
                "(?<![\\p{L}\\p{N}_]|\\p{N}\\.)(?:$it)(?![\\p{L}\\p{N}_]|\\.\\p{N})"
            },
        )
    }

    private fun secretPatternsOf(values: List<String>): List<Regex> = secretSourcesOf(values).map { Regex("(?iu)$it") }

    private val knownSecrets: List<String> = secrets.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        .sortedByDescending { it.length }

    /** All known secrets, for the span pass of [redact]. */
    private val secretPatterns: List<Regex> = secretPatternsOf(knownSecrets)

    /** The secrets in the text a replacement keeps (a loopback host, a port, a top-level domain), labels skipped. */
    private val keptSecretPatterns: List<Regex> =
        secretSourcesOf(knownSecrets).map { Regex("(?iu)(${LABEL_PATTERN.pattern})|(?:$it)") }

    /**
     * A replacement with the known secrets in the text it keeps masked: a URL keeps its scheme, a loopback host and its
     * port, a destination its top-level domain, an IPv6 address its network prefix — none of them may be a secret.
     */
    private fun maskKept(text: String): String = keptSecretPatterns.fold(text) { s, pattern ->
        pattern.replace(s) { m -> if (m.groups[1] != null) m.value else "<redacted>" }
    }

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
        // A known name as a whole word; a subdomain of a server's domain names the server too: "cdn.vpn.example.com"
        // becomes the label of "vpn.example.com". Word edges are Unicode letters and digits (IDN, Cyrillic names); a
        // colon may come before a name ("dialing to tcp:host:443").
        names = labels.keys.takeIf { it.isNotEmpty() }?.sortedByDescending { it.length }?.let { known ->
            Regex(
                "(?iu)(?<![\\p{L}\\p{N}_.\\-])((?:[a-z0-9_\\-]+\\.)*)(" +
                    known.joinToString("|") { Regex.escape(it) } + ")(?![\\p{L}\\p{N}_\\-]|\\.[\\p{L}\\p{N}_\\-])",
            )
        }
    }

    /**
     * One line. First this pass, on the ORIGINAL text: every matcher — the subscription links, the labels already there,
     * whole URLs (scheme, credentials, host, port, the rest), IPv6 addresses (also after a network prefix), the known
     * names, the destinations Xray names in its own words, the known secrets — collects spans of the same text, the
     * overlaps are resolved ([resolve]) and each span becomes an opaque placeholder ([placeholder]). Then LogRedactor on
     * what is left, which cannot alter a placeholder; then the placeholders become their labels. No matcher sees text
     * another one rewrote, so a secret that is part of a server name, spelled like a URL scheme or shaped like an IP or
     * a UUID cannot hide a name, a URL or itself.
     */
    fun redact(line: String): String {
        // org.json writes "/" as "\/" on Android: URLs in JSON must look like URLs to the patterns.
        val original = line.replace("\\/", "/")
        val replacements = ArrayList<String>()
        var s = resolve(original, spans(original)) { text -> placeholder(replacements.size).also { replacements += text } }
        s = log.redact(s)
        s = SECRET.replace(s) { m ->
            val (_, quote, key, separator, value) = m.groupValues
            "$quote$key$quote$separator" + if (value.startsWith("[")) "[\"<redacted>\"]" else "\"<redacted>\""
        }
        if (replacements.isEmpty()) return s
        return PLACEHOLDER.replace(s) { m -> replacements.getOrNull(indexOf(m.groupValues[1])) ?: "<redacted>" }
    }

    private enum class Kind { LABEL, IP, HOST, SERVER, SECRET }

    /** A stretch of the line and what replaces it; [kind] orders the sensitivity (a secret is the most sensitive). */
    private class Span(val start: Int, val end: Int, val kind: Kind, val text: String)

    private fun isSecret(text: String) = secretPatterns.any { it.containsMatchIn(text) }

    private fun kindOf(label: String) = if (label.startsWith("<host-")) Kind.HOST else Kind.SERVER

    private fun spans(s: String): List<Span> {
        val out = ArrayList<Span>()
        // Only a generated label already in the line ([LABEL_PATTERN]) is kept as it is; every other replacement — also
        // one that rebuilds its text unchanged, as a pathless loopback URL — has the secrets in its kept text masked.
        fun add(range: IntRange, kind: Kind, text: String, generated: Boolean = false) {
            out += Span(range.first, range.last + 1, kind, if (generated) text else maskKept(text))
        }
        // A subscription link whole (it is a secret: its path holds the token).
        for ((link, label) in links) {
            var at = s.indexOf(link)
            while (at >= 0) {
                add(at until at + link.length, Kind.SECRET, label)
                at = s.indexOf(link, at + link.length)
            }
        }
        // The labels already in the line stay as they are; nothing inside them is matched (a server "x", a secret
        // "redacted").
        for (m in LABEL_PATTERN.findAll(s)) add(m.range, Kind.LABEL, m.value, generated = true)
        // A URL is rebuilt whole, never copied, as LogRedactor would write it: the credentials (up to the last "@"
        // before the host — a password may hold one) become "<redacted>@", the host its label, the port stays, anything
        // after it "/<redacted>". A scheme that is a secret ("https", "foo.bar", "pw-1.2.3.4") is masked with it.
        for (m in URL.findAll(s)) {
            val scheme = m.groupValues[1].let { if (isSecret(it)) "<redacted>" else it }
            val credentials = if (m.groups[CREDENTIALS] != null) "<redacted>@" else ""
            val host = m.groups[HOST]!!.value
            val label = labelFor(host)
            val port = m.groups[PORT]?.value.orEmpty()
            val rest = if (m.groupValues[REST].isNotEmpty()) "/<redacted>" else ""
            add(m.range, if (label == host) Kind.LABEL else kindOf(label), "$scheme://$credentials$label$port$rest")
        }
        // An IPv6 address in any spelling, also after a network prefix ("tcp:fd00:0::1"); "fdab:beef::1" is an address
        // as a whole. After such a prefix LogRedactor's IPv6 rule does not see an address at all, so a public one is
        // masked here; a prefix that is a secret ("tcp" as a password) is masked with it.
        for (m in IPV6_TOKEN.findAll(s)) {
            val whole = canonicalIpv6(m.value.lowercase())
            val prefix = if (whole != null) null else m.groups[1]?.value
            val canonical = whole ?: canonicalIpv6(m.groupValues[2].lowercase()) ?: continue
            val label = labels[canonical]
            val replacement = label ?: if (prefix != null && !isPrivateIpv6(canonical)) "<ip>" else continue
            val kind = if (label != null) kindOf(label) else Kind.IP
            val shownPrefix = prefix?.let { if (isSecret(it)) "<redacted>" else it }
            add(m.range, kind, if (shownPrefix != null) "$shownPrefix:$replacement" else replacement)
        }
        names?.findAll(s)?.forEach { m ->
            val name = m.groupValues[2]
            labels[normalize(name) ?: name.lowercase()]?.let { add(m.range, kindOf(it), it) }
        }
        // The domains of a destination line, decided on the ORIGINAL line: LogRedactor's own destination words
        // ("lookup", "connection to", "dns"…) and the ones Xray uses ("TCP Connect request to tcp:site:443", "taking
        // detour [proxy] for [tcp:site:443]"). Once a destination word is itself a secret and becomes a placeholder,
        // LogRedactor no longer sees the line as one — so the domains are hidden here, before.
        if (DESTINATION_LINE.containsMatchIn(s)) {
            for (m in DOMAIN.findAll(s)) add(m.range, Kind.HOST, "<domain>." + m.groupValues[1])
        }
        for (pattern in secretPatterns) for (m in pattern.findAll(s)) add(m.range, Kind.SECRET, "<redacted>")
        return out
    }

    /**
     * The spans replaced once, right to left. Of overlapping spans the one that contains the other wins (a URL over
     * the secret that is its scheme, a server name over the secret that is part of it, a label over a name inside it);
     * spans that overlap in part become one, replaced as the more sensitive of the two (secret > server > host > IP).
     */
    private fun resolve(s: String, found: List<Span>, wrap: (String) -> String): String {
        if (found.isEmpty()) return s
        val sorted = found.sortedWith(
            compareBy<Span> { it.start }.thenByDescending { it.end - it.start }.thenByDescending { it.kind.ordinal },
        )
        val chosen = ArrayList<Span>()
        for (span in sorted) {
            val last = chosen.lastOrNull()
            when {
                last == null || span.start >= last.end -> chosen += span
                span.end <= last.end -> Unit
                else -> {
                    val top = if (span.kind.ordinal > last.kind.ordinal) span else last
                    val text = if (top.kind == Kind.SECRET || top.kind == Kind.LABEL) "<redacted>" else top.text
                    chosen[chosen.size - 1] = Span(last.start, span.end, top.kind, text)
                }
            }
        }
        // Left to right, so the placeholders are numbered in reading order; built in one pass.
        val out = StringBuilder(s.length)
        var last = 0
        for (span in chosen) {
            out.append(s, last, span.start).append(wrap(span.text))
            last = span.end
        }
        return out.append(s, last, s.length).toString()
    }

    /**
     * The placeholder of replacement [index] while LogRedactor runs: private-use characters around letters g–z only —
     * no digit, no hex letter, nothing shaped like an IP, a UUID, a key or a domain that a LogRedactor rule could take.
     */
    private fun placeholder(index: Int): String {
        val letters = StringBuilder()
        var n = index
        do {
            letters.append('g' + n % PLACEHOLDER_BASE)
            n /= PLACEHOLDER_BASE
        } while (n > 0)
        return "\uE000" + letters.reverse() + "\uE001"
    }

    private fun indexOf(letters: String): Int = letters.fold(0) { n, c -> n * PLACEHOLDER_BASE + (c - 'g') }

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
        // A scoped IPv6 address ("fe80::1%25wlan0") is that address for the lookups; the whole host gets the label.
        val address = name.substringBefore('%')
        // A configured server keeps its label even on loopback; another spelling of a known address or name (IPv6
        // written otherwise, punycode) has its label already.
        labels[address]?.let { return it }
        forms(address).firstNotNullOfOrNull { labels[it] }?.let { return it }
        if (isLoopback(address)) return host
        return labels.getOrPut(name) { "<host-${++hosts}>" }
    }

    companion object {
        private const val CREDENTIALS = 2
        private const val HOST = 3
        private const val PORT = 4
        private const val REST = 5

        /** An opaque placeholder of [redact] (see [placeholder]); group 1: the letters of its index. */
        private val PLACEHOLDER = Regex("\uE000([g-z]+)\uE001")

        /** Letters g–z: the placeholder index in base 20. */
        private const val PLACEHOLDER_BASE = 20

        /** The labels LogRedactor and this pass put in the text; kept whole, nothing inside them is matched. */
        private val LABEL_PATTERN =
            Regex("""<(?:redacted|uuid|ip|key|domain|(?:server|host|subscription)-\d+(?:-host)?)>""")

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
            // The prefix is a network word ("tcp") or a label a secret became ("<redacted>:fd00:0::1").
            """(?<![\p{L}\p{N}_:.])(?:([a-zA-Z][a-zA-Z0-9+\-]{0,10}|<[a-z0-9\-]{1,30}>):)?([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?![\p{L}\p{N}_:])"""
        )

        /**
         * The lines that name where a connection goes: LogRedactor's destination words (the same list as its own, which
         * is private) and Xray's own wording beyond them.
         */
        private val DESTINATION_LINE = Regex(
            """(?i)connection (?:to|from)|domain|dns|exchang|lookup|resolv|sniff|connect request to|taking detour|dialing|creating connection to|tunnel(?:l)?ing request|received request for|accepted (?:tcp|udp):"""
        )

        /**
         * A domain name outside a label, in any case ("WWW.EXAMPLE.COM"): the name is hidden, the top-level domain stays
         * (as in LogRedactor).
         */
        private val DOMAIN = Regex("""(?<![\w.\-<])(?:[a-zA-Z0-9](?:[a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+([a-zA-Z]{2,63})\.?(?![\w\-])""")

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
         * A URL: scheme (group 1, RFC 3986), credentials (group 2, up to the last "@" before the host — a password may
         * hold one), host (group 3: a bracketed IPv6 address or a name / IPv4 address), port (group 4) and the rest
         * (group 5: path, query, fragment — what LogRedactor writes as "/<redacted>").
         */
        private val URL = Regex(
            // A bracketed IPv6 host may carry a zone id ("[fe80::1%25wlan0]"), part of the host.
            """\b([a-zA-Z][a-zA-Z0-9+.\-]*)://([^\s/?#"']*@)?(\[[0-9A-Fa-f:.]+(?:%[^\s\]/?#"'<>]*)?\]|[^\s/?#"'<>:@\[\]\\]+)(:\d{1,5})?([^\s"'<>]*)"""
        )

        /** The keys whose values the line patterns clean (LogRedactor's SECRET, SERVER and Wi-Fi keys, and [SECRET]). */
        private val SECRET_KEY = Regex(
            """(?i)[\w\-]*(?:password|passwd|pass|secret|token|key|uuid|auth|psk|short_id)[\w\-]*|server|server_name|sni|host|address|path|service[_\-]?names?|(?:wifi_)?b?ssid|short[_\-]?ids?|server[_\-]?names?|verify[_\-]?peer[_\-]?cert[_\-]?by[_\-]?names?|seed|mldsa65[_\-]?verify"""
        )

        fun isSecretKey(key: String): Boolean = SECRET_KEY.matches(key)

        /**
         * The keys of a profile whose values name a server: its address, the TLS name, the transport's host; the DNS
         * tunnels (Dnstt, MasterDns) keep their server under "domain".
         */
        private val SERVER_KEYS = setOf(
            "server", "server_name", "servername", "sni", "host", "hostname", "address", "peer", "endpoint",
            "server_address", "serveraddress", "remote", "remote_address", "ip", "verifypeercertbyname",
            "verify_peer_cert_by_name", "domain", "domains",
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

        /** localhost, ::1 or an IPv4 address in 127/8 — not a host name that merely starts with "127." (review). */
        private fun isLoopback(name: String) = name == "localhost" || name == "::1" ||
            LOOPBACK_V4.matchEntire(name)?.groupValues?.drop(1)?.all { it.toInt() <= 255 } == true

        private val LOOPBACK_V4 = Regex("""127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""")

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
