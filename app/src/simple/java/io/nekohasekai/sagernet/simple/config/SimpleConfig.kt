package io.nekohasekai.sagernet.simple.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The configuration of the network checks and the server keywords (`simple/config.json`, design.md D7): bundled with
 * the app and updated next to the route profiles by [SimpleConfigRepo]. Pure Kotlin over org.json, so JVM unit tests
 * cover the parser and the validator (design.md D10).
 */
data class SimpleConfig(
    /** A newer version wins over an older one; the bundled copy and a downloaded copy compare by it. */
    val version: Int,
    /** HTTPS addresses that answer in a whitelisted network. */
    val probesAllowed: List<String>,
    /** HTTPS addresses that answer in an ordinary network but not in a whitelisted one. */
    val probesBlocked: List<String>,
    /** DNS servers that work in a whitelisted network, for the server addresses before the VPN is up. */
    val whitelistDns: List<String>,
    /** A server whose name holds one of these words is a server for whitelists (WhitelistKeywords). */
    val wlKeywords: List<String>,
    /** Trusted hosts whose HTTPS `Date` header tells whether the clock is right (Repair). */
    val timeHosts: List<String>,
) {

    /** Why a downloaded or bundled file was refused. */
    class InvalidConfigException(message: String) : Exception(message)

    companion object {
        /** Lists longer than this are not a plausible configuration. */
        const val MAX_ITEMS = 64

        /** Longer entries are not a plausible configuration either. */
        const val MAX_ITEM_LENGTH = 256

        /**
         * Parses and validates [text]; throws [InvalidConfigException] when it is damaged or incomplete. Each
         * `whitelistDns` entry must pass the syntax check [isDnsServer] and [dnsAccepted] — on the device the route DNS
         * parser itself (DnsServers.parseDnsAddress), which needs Android resources for its messages.
         */
        fun parse(text: String, dnsAccepted: (String) -> Boolean = { true }): SimpleConfig {
            val root = try {
                JSONTokener(text).nextValue()
            } catch (e: JSONException) {
                throw InvalidConfigException("not JSON: ${e.message}")
            } as? JSONObject ?: throw InvalidConfigException("not a JSON object")
            val version = root.opt("version")
            if (version !is Int || version < 1) throw InvalidConfigException("version must be a positive integer")
            val probes = root.opt("probes") as? JSONObject ?: throw InvalidConfigException("probes is missing")
            return SimpleConfig(
                version = version,
                probesAllowed = list(probes, "probes.allowed", "allowed", ::isProbeUrl),
                probesBlocked = list(probes, "probes.blocked", "blocked", ::isProbeUrl),
                whitelistDns = list(root, "whitelistDns", "whitelistDns") { isDnsServer(it) && dnsAccepted(it) },
                wlKeywords = list(root, "wlKeywords", "wlKeywords") { it.isNotBlank() },
                timeHosts = list(root, "timeHosts", "timeHosts", ::isHostName),
            )
        }

        /** [parse], or null when the text is refused. */
        fun parseOrNull(text: String, dnsAccepted: (String) -> Boolean = { true }): SimpleConfig? = try {
            parse(text, dnsAccepted)
        } catch (_: InvalidConfigException) {
            null
        }

        /**
         * The configuration in effect: a stored (downloaded) copy only when it is newer than the bundled one, so an
         * app update with newer bundled lists is not shadowed by an older download.
         */
        fun choose(bundled: SimpleConfig, stored: SimpleConfig?): SimpleConfig =
            if (stored != null && stored.version > bundled.version) stored else bundled

        private fun list(obj: JSONObject, label: String, key: String, valid: (String) -> Boolean): List<String> {
            val array = obj.opt(key) as? JSONArray ?: throw InvalidConfigException("$label must be an array")
            if (array.length() == 0) throw InvalidConfigException("$label is empty")
            if (array.length() > MAX_ITEMS) throw InvalidConfigException("$label has more than $MAX_ITEMS entries")
            val out = ArrayList<String>(array.length())
            for (i in 0 until array.length()) {
                val value = (array.opt(i) as? String)?.trim()
                    ?: throw InvalidConfigException("$label[$i] must be a string")
                if (value.isEmpty() || value.length > MAX_ITEM_LENGTH || !valid(value)) {
                    throw InvalidConfigException("$label[$i] is not valid: $value")
                }
                if (value !in out) out.add(value)
            }
            return out
        }

        private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?)+$")

        /** `https://host[/path]`: the checks send nothing but a fixed address (traffic-modes "Приватность проверок"). */
        internal fun isProbeUrl(value: String): Boolean {
            if (!value.startsWith("https://")) return false
            val rest = value.removePrefix("https://")
            val host = rest.substringBefore('/')
            if (rest.contains('?') || rest.contains('#') || rest.contains('@') || rest.any { it.isWhitespace() }) return false
            return isHostName(host)
        }

        internal fun isHostName(value: String): Boolean = HOST.matches(value)

        private val PLAIN_DNS_SCHEMES = setOf("udp", "tcp", "tls", "quic")
        private val PATH_DNS_SCHEMES = setOf("https", "h3")
        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")
        private val PORT = Regex("^[1-9]\\d{0,4}$")

        /**
         * One network DNS server in the syntax of the route DNS settings (DnsServers.parseDnsAddress): an IP address or
         * host name, or `udp|tcp|tls|quic://host[:port]`, `https|h3://host[:port][/path]`. `local` and `dhcp://` are
         * no whitelisted server, and a list separator would make one entry several servers.
         */
        internal fun isDnsServer(value: String): Boolean {
            if (value.any { it.isWhitespace() || it == ',' || it == ';' }) return false
            val schemeEnd = value.indexOf("://")
            val scheme = if (schemeEnd < 0) "udp" else value.substring(0, schemeEnd).lowercase()
            var rest = if (schemeEnd < 0) value else value.substring(schemeEnd + 3)
            if (scheme !in PLAIN_DNS_SCHEMES && scheme !in PATH_DNS_SCHEMES) return false
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                if (scheme !in PATH_DNS_SCHEMES) return false
                rest = rest.substring(0, slash)
            }
            var host = rest
            var port: String? = null
            when {
                rest.startsWith('[') -> {
                    val close = rest.indexOf(']')
                    if (close < 0) return false
                    host = rest.substring(1, close)
                    val tail = rest.substring(close + 1)
                    if (tail.isNotEmpty()) {
                        if (!tail.startsWith(':')) return false
                        port = tail.substring(1)
                    }
                    if (!host.contains(':') || !IPV6.matches(host)) return false
                }

                rest.count { it == ':' } > 1 -> return IPV6.matches(rest)
                else -> {
                    val colon = rest.indexOf(':')
                    if (colon >= 0) {
                        host = rest.substring(0, colon)
                        port = rest.substring(colon + 1)
                    }
                    val ipLike = host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
                    if (if (ipLike) !IPV4.matches(host) else !isHostName(host)) return false
                }
            }
            return port == null || (PORT.matches(port) && port.toInt() in 1..65535)
        }
    }
}
