package io.nekohasekai.sagernet.simple.regime

import java.io.ByteArrayOutputStream

/**
 * The plain DNS messages of the probe's fallback lookup (NetworkProber): one question, the A or AAAA records of the
 * answer. Pure Kotlin, covered by JVM unit tests.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    /** A recursive query for [host]'s records of [type] with the transaction [id]. */
    fun query(id: Int, host: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.short(id)
        out.short(0x0100) // standard query, recursion desired
        out.short(1) // one question
        out.short(0)
        out.short(0)
        out.short(0)
        for (label in host.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "bad label in $host" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.short(type)
        out.short(CLASS_IN)
        return out.toByteArray()
    }

    /**
     * The addresses (4 bytes for A, 16 for AAAA) in the answer to query [id]; empty for an error, a truncated or
     * malformed message, or another transaction's answer.
     */
    fun addresses(message: ByteArray, length: Int = message.size, id: Int): List<ByteArray> {
        if (length < 12 || length > message.size) return emptyList()
        fun u16(at: Int) = (message[at].toInt() and 0xff shl 8) or (message[at + 1].toInt() and 0xff)
        if (u16(0) != id) return emptyList()
        val flags = u16(2)
        val response = flags and 0x8000 != 0
        val truncated = flags and 0x0200 != 0
        if (!response || truncated || flags and 0x000f != 0) return emptyList()
        val questions = u16(4)
        val answers = u16(6)
        var at = 12
        fun skipName(): Boolean {
            while (at < length) {
                val len = message[at].toInt() and 0xff
                when {
                    len == 0 -> {
                        at += 1
                        return true
                    }

                    len and 0xc0 == 0xc0 -> {
                        at += 2
                        return at <= length
                    }

                    len and 0xc0 != 0 -> return false
                    else -> at += 1 + len
                }
            }
            return false
        }
        repeat(questions) {
            if (!skipName() || at + 4 > length) return emptyList()
            at += 4
        }
        val found = mutableListOf<ByteArray>()
        repeat(answers) {
            if (!skipName() || at + 10 > length) return found
            val type = u16(at)
            val klass = u16(at + 2)
            val size = u16(at + 8)
            at += 10
            if (at + size > length) return found
            val expected = when (type) {
                TYPE_A -> 4
                TYPE_AAAA -> 16
                else -> -1
            }
            if (klass == CLASS_IN && size == expected) found += message.copyOfRange(at, at + size)
            at += size
        }
        return found
    }

    /**
     * A plain DNS server of the configuration (`whitelistDns`) as host and port: an IP address, optionally with
     * `udp://` and a port; null for the other kinds (DoH, DoT, a host name), which the fallback lookup does not speak.
     */
    fun plainServer(entry: String): Pair<String, Int>? {
        var rest = entry.trim()
        if (rest.contains("://")) {
            if (!rest.startsWith("udp://", ignoreCase = true)) return null
            rest = rest.substring(6)
        }
        rest = rest.trimEnd('/')
        val (host, port) = when {
            rest.startsWith("[") -> {
                val end = rest.indexOf(']')
                if (end < 0) return null
                val tail = rest.substring(end + 1)
                val port = if (tail.isEmpty()) 53 else tail.removePrefix(":").toIntOrNull() ?: return null
                rest.substring(1, end) to port
            }

            rest.count { it == ':' } == 1 -> rest.substringBefore(':') to (rest.substringAfter(':').toIntOrNull() ?: return null)
            else -> rest to 53
        }
        if (port !in 1..65535) return null
        val ipv4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(host)
        if (ipv4 != null) return if (ipv4.groupValues.drop(1).all { it.toInt() <= 255 }) host to port else null
        val ipv6 = host.contains(':') && host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        return if (ipv6) host to port else null
    }

    private fun ByteArrayOutputStream.short(value: Int) {
        write(value shr 8 and 0xff)
        write(value and 0xff)
    }
}
