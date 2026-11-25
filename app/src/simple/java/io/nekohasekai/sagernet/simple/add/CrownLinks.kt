package io.nekohasekai.sagernet.simple.add

/**
 * Crown's own deep links (repository-governance "Ссылки crown://"): a `crown://` link is rewritten into the link the
 * import flows of upstream already handle, so no flow learns a new scheme. No Android types, so JVM unit tests cover it.
 *
 * - `crown://addsub?url=<URL>&name=<name>` (parameters in any order, percent-encoded or not) — a subscription, made
 *   for a panel that inserts the subscription URL as it is (Remnawave `{{SUBSCRIPTION_LINK}}`);
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

    /** [text] with a leading `crown://` link rewritten as described above; any other text is returned unchanged. */
    fun toThrone(text: String): String {
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
        val params = query.split('&').filter { it.isNotEmpty() }.associate {
            val eq = it.indexOf('=')
            if (eq < 0) it.lowercase() to "" else it.substring(0, eq).lowercase() to decode(it.substring(eq + 1))
        }
        return subscribe(params["url"].orEmpty(), params["name"].orEmpty())
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
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val byte = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (byte != null) {
                    out.write(byte)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
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
