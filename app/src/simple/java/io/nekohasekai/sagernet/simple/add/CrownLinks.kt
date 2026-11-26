package io.nekohasekai.sagernet.simple.add

/**
 * Crown's own deep links (repository-governance "Ссылки crown://"): a `crown://` link is rewritten into the link the
 * import flows of upstream already handle, so no flow learns a new scheme. No Android types, so JVM unit tests cover it.
 *
 * - `crown://addsub?url=<URL>&name=<name>` (parameters in any order, percent-encoded or not) — a subscription, made
 *   for a panel that inserts the subscription URL as it is (Remnawave `{{SUBSCRIPTION_LINK}}`); such a raw URL is
 *   taken verbatim with its own `?…&…` and escapes, up to `&name=` or the end;
 * - `crown://addsub/<URL>` and `crown://addsub/<URL>#<name>` — the same with the URL as the path, query included;
 * - `crown://install-config?url=…&name=…` — the clash spelling, the same as the first form;
 * - `crown://addsub/<base64 of "URL#name">`, `crown://add/…`, `crown://route/…`, `crown://remoteroute/…` — the
 *   `throne://` links of the same name.
 *
 * The subscription forms become `clash://install-config?url=…&name=…` (SubscribeFlows.subscribeLink): it carries an
 * empty URL too, so the classic interface says that the link has no subscription URL instead of ignoring it.
 */
object CrownLinks {

    private const val SCHEME = "crown://"
    private val SUBSCRIBE_COMMANDS = setOf("addsub", "install-config")

    /**
     * [text] with its `crown://` links rewritten as described above, each line on its own: in a pasted list every
     * `crown://` line is rewritten (as upstream reads a list of `throne://add/…` lines) and a raw subscription URL
     * never runs into the next line. Text without such a line is returned unchanged (the same object).
     */
    fun toThrone(text: String): String {
        if (!text.contains(SCHEME, ignoreCase = true)) return text
        if (text.none { it == '\n' || it == '\r' }) return link(text)
        var changed = false
        val lines = text.split(LINE_BREAK).map { line -> link(line).also { if (it !== line) changed = true } }
        return if (changed) lines.joinToString("\n") else text
    }

    /** One line: a leading `crown://` link rewritten, anything else returned unchanged. */
    private fun link(text: String): String {
        val content = text.trim()
        if (!content.startsWith(SCHEME, ignoreCase = true)) return text
        val rest = content.substring(SCHEME.length)
        val command = rest.takeWhile { it != '/' && it != '?' && it != '#' }
        val tail = rest.substring(command.length)
        if (command.lowercase() !in SUBSCRIBE_COMMANDS) return "throne://$rest"

        val path = tail.removePrefix("/")
        if (tail.startsWith("/") && isHttpUrl(path)) {
            val hash = path.indexOf('#')
            return if (hash < 0) {
                subscribe(path, "")
            } else {
                subscribe(path.substring(0, hash), decode(path.substring(hash + 1)))
            }
        }
        // A path that is not a URL is the base64 payload of throne://addsub.
        if (tail.startsWith("/") && path.isNotEmpty() && !path.startsWith("?")) return "throne://addsub$tail"

        val query = path.removePrefix("?").substringBefore('#')
        // A raw http(s) URL (a panel inserts it as it is) keeps its own query and escapes: it runs up to `&name=`
        // or the end of the query, and is not decoded. An encoded URL is one parameter, decoded once.
        RAW_URL.find(query)?.let { raw ->
            val start = raw.groups[1]!!.range.first
            val end = NAME_AFTER.find(query, start)?.range?.first ?: query.length
            return subscribe(query.substring(start, end), params(query.removeRange(raw.range.first, end))["name"].orEmpty())
        }
        val params = params(query)
        return subscribe(params["url"].orEmpty(), params["name"].orEmpty())
    }

    private val LINE_BREAK = Regex("\r\n|\r|\n")
    private val RAW_URL =Regex("(?:^|&)url=(https?://)", RegexOption.IGNORE_CASE)
    private val NAME_AFTER = Regex("&name=", RegexOption.IGNORE_CASE)

    /** Query parameters by lower-case name, values percent-decoded. */
    private fun params(query: String): Map<String, String> =
        query.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) it.lowercase() to "" else it.substring(0, eq).lowercase() to decode(it.substring(eq + 1))
        }

    private fun subscribe(url: String, name: String): String =
        "clash://install-config?url=${encode(url.trim())}&name=${encode(name.trim())}"

    private fun isHttpUrl(text: String): Boolean =
        text.startsWith("https://", ignoreCase = true) || text.startsWith("http://", ignoreCase = true)

    /** Percent-decoding as UTF-8; `+` stays a plus (a token may hold it), a malformed escape stays as it is. */
    private fun decode(text: String): String {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%' && i + 2 < text.length) {
                val hi = text[i + 1].digitToIntOrNull(16)
                val lo = text[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            // Up to the next '%' at once, so a surrogate pair (an emoji) is encoded whole.
            val next = text.indexOf('%', i + 1).let { if (it < 0) text.length else it }
            out.write(text.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Everything but the unreserved characters of RFC 3986 percent-encoded as UTF-8 (what android.net.Uri decodes). */
    private fun encode(text: String): String {
        val sb = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c)
            } else {
                sb.append('%').append("0123456789ABCDEF"[b shr 4]).append("0123456789ABCDEF"[b and 15])
            }
        }
        return sb.toString()
    }
}
