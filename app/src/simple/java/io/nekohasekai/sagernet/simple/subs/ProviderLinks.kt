package io.nekohasekai.sagernet.simple.subs

/**
 * The provider's pages a subscription names (`Profile-Web-Page-Url`, `Support-Url`) as the simple UI opens them: only
 * web pages with a host, and Telegram for support, as the classic card does (SubscriptionInfoCard.linkUri). Anything
 * else (`market://`, another app's deep link) is no page, so a panel cannot make a button open what it likes. No
 * Android types, so JVM unit tests cover it.
 */
object ProviderLinks {

    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")

    /** [raw] when it may be opened, "" otherwise. */
    fun safe(raw: String, allowTg: Boolean): String {
        val text = raw.trim()
        val scheme = SCHEME.find(text)?.groupValues?.get(1)?.lowercase() ?: return ""
        return when (scheme) {
            "http", "https" -> text.takeIf { hasHost(text.substring(scheme.length + 1)) }.orEmpty()
            "tg" -> text.takeIf { allowTg }.orEmpty()
            else -> ""
        }
    }

    /** `//host…` with a host before the path, query or fragment (and after any user info). */
    private fun hasHost(rest: String): Boolean {
        if (!rest.startsWith("//")) return false
        val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
        val host = authority.substringAfterLast('@').substringBefore(':')
        return host.isNotBlank() && host.none { it.isWhitespace() }
    }
}
