package io.nekohasekai.sagernet.simple.add

/**
 * The pure rules of the universal add (simple-ui "Универсальное добавление серверов", "Предложение импорта из буфера
 * обмена"): what a pasted, scanned or shared text is, and whether it is in the app already. No Android types, so JVM
 * unit tests cover it.
 */
object ImportText {

    /** What a text asks for; the simple UI never asks the user to pick a protocol or an import kind. */
    enum class Kind {
        /** Nothing but whitespace. */
        EMPTY,

        /** One http(s) URL: a subscription (the desktop's "URL detected" choice is not offered). */
        SUBSCRIPTION_URL,

        /** `throne://addsub/…` or `clash://install-config?url=…`: a subscription named by the link. */
        SUBSCRIBE_LINK,

        /** `throne://route…` / `throne://remoteroute…`: a routing profile, left to the classic interface. */
        ROUTE_LINK,

        /** Anything else: share links, sing-box / Clash / WireGuard documents — or text that is none of these. */
        CONTENT,
    }

    private const val ROUTE_LINK_PREFIX = "throne://route"
    private const val REMOTE_ROUTE_LINK_PREFIX = "throne://remoteroute"

    fun kind(text: String): Kind {
        val content = text.trim()
        if (content.isEmpty()) return Kind.EMPTY
        val single = content.none { it == '\n' || it == '\r' }
        if (single && isHttpUrl(content)) return Kind.SUBSCRIPTION_URL
        if (single && (content.startsWith("clash://install-config", ignoreCase = true) ||
                content.startsWith("throne://addsub", ignoreCase = true))
        ) return Kind.SUBSCRIBE_LINK
        if (content.startsWith(ROUTE_LINK_PREFIX, ignoreCase = true) ||
            content.startsWith(REMOTE_ROUTE_LINK_PREFIX, ignoreCase = true)
        ) return Kind.ROUTE_LINK
        return Kind.CONTENT
    }

    /**
     * Several payloads at once (the QR scanner's codes) as the texts to import one by one: share links on one line
     * each go together (a list of links is one document), while subscriptions, routing links and documents of their
     * own (a WireGuard INI, a JSON config) stay apart — joined, a document would swallow what follows it.
     */
    fun importUnits(payloads: List<String>): List<String> {
        val units = ArrayList<String>()
        val links = ArrayList<String>()
        var linksAt = -1
        for (payload in payloads.map { it.trim() }.filter { it.isNotEmpty() }) {
            val shareLink = kind(payload) == Kind.CONTENT && payload.none { it == '\n' || it == '\r' } &&
                "://" in payload && payload.none { it.isWhitespace() }
            if (shareLink) {
                if (linksAt < 0) linksAt = units.size
                links.add(payload)
            } else {
                units.add(payload)
            }
        }
        if (links.isNotEmpty()) units.add(linksAt, links.joinToString("\n"))
        return units
    }

    /** An http(s) URL with a host and no whitespace. */
    fun isHttpUrl(text: String): Boolean {
        val scheme = when {
            text.startsWith("https://", ignoreCase = true) -> 8
            text.startsWith("http://", ignoreCase = true) -> 7
            else -> return false
        }
        if (text.any { it.isWhitespace() }) return false
        val host = text.substring(scheme).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfterLast('@')
        return host.isNotEmpty() && host.first() != ':'
    }

    /**
     * A link in a form where two spellings of the same server or subscription compare equal: no fragment (the
     * display name of share links), scheme and host in lower case, no default http(s) port, no trailing slash of the
     * path, query parameters in a fixed order. User info, path and parameter values keep their case (they hold UUIDs,
     * passwords and subscription tokens).
     */
    fun normalize(link: String): String {
        var rest = link.trim().substringBefore('#')
        val schemeEnd = rest.indexOf("://")
        if (schemeEnd <= 0) return rest
        val scheme = rest.substring(0, schemeEnd).lowercase()
        rest = rest.substring(schemeEnd + 3)
        val query = rest.substringAfter('?', "")
        rest = rest.substringBefore('?')
        val authority = rest.takeWhile { it != '/' }
        var path = rest.substring(authority.length)
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
        var host = authority.substring(at + 1).lowercase()
        if (scheme == "https" && host.endsWith(":443") || scheme == "http" && host.endsWith(":80")) {
            host = host.substringBeforeLast(':')
        }
        path = path.trimEnd('/')
        val params = query.split('&').filter { it.isNotEmpty() }.sorted()
        val tail = if (params.isEmpty()) "" else params.joinToString("&", prefix = "?")
        return "$scheme://$userInfo$host$path$tail"
    }

    /**
     * Whether everything [candidates] names is in the app already: every candidate link is among [known] links (both
     * sides are normalized here). Nothing to compare (no candidate) is never "added".
     */
    fun alreadyAdded(candidates: Collection<String>, known: Collection<String>): Boolean {
        val links = candidates.map(::normalize).filter { it.isNotEmpty() }
        if (links.isEmpty()) return false
        val present = known.mapTo(HashSet(), ::normalize)
        return links.all { it in present }
    }

    /** A stable key of a text, to remember an offer the user turned down without keeping the text itself. */
    fun fingerprint(text: String): String {
        val lines = text.trim().lines().map { normalize(it) }.filter { it.isNotEmpty() }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/** The country of a server by the flag in its name; the full CountryFromName (ISO codes, names) is batch 5. */
object CountryFlags {

    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF

    /** The ISO 3166 code of the first flag emoji in [name], null when there is none. */
    fun countryOf(name: String): String? {
        var i = 0
        var previous = -1
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (cp in REGIONAL_A..REGIONAL_Z) {
                if (previous >= 0) {
                    return String(charArrayOf('A' + (previous - REGIONAL_A), 'A' + (cp - REGIONAL_A)))
                }
                previous = cp
            } else {
                previous = -1
            }
            i += Character.charCount(cp)
        }
        return null
    }
}
