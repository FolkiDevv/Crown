package io.nekohasekai.sagernet.simple.subs

/**
 * A «заглушка» (simple-ui "Сообщения подписки"): an entry a subscription panel sends instead of a server to say
 * something in its name («Подписка истекла», «App not supported»). Its address goes nowhere: an unspecified or loopback
 * address, or the port 0 or 1. No Android types, so JVM unit tests cover it.
 */
object Placeholders {

    /**
     * [host] and [port] as the outbound stores them; [portOptional]: the type may leave the port 0 (a port range or
     * list stands in for it), so a 0 there says nothing. An entry without a host (a custom configuration) is none.
     */
    fun isPlaceholder(host: String, port: Int, portOptional: Boolean): Boolean {
        val address = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        if (address.isEmpty()) return false
        if (address == "localhost" || address.endsWith(".localhost")) return true
        if (isUnspecifiedOrLoopback(address)) return true
        return !portOptional && port in 0..1
    }

    private fun isUnspecifiedOrLoopback(address: String): Boolean {
        val v4 = address.split('.')
        if (v4.size == 4 && v4.all { part -> part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } }) {
            val bytes = v4.map { it.toInt() }
            if (bytes.any { it > 255 }) return false
            return bytes.all { it == 0 } || bytes[0] == 127
        }
        if (':' !in address) return false
        // IPv6: "::", "::1" and their long forms; anything else is a real address.
        val groups = expandV6(address) ?: return false
        return groups.dropLast(1).all { it == 0 } && groups.last() in 0..1
    }

    /** The eight groups of an IPv6 address, null when it is none. */
    private fun expandV6(address: String): List<Int>? {
        val parts = address.split("::")
        if (parts.size > 2) return null
        fun groups(text: String): List<Int>? =
            if (text.isEmpty()) emptyList() else text.split(':').map { g -> g.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
        val head = groups(parts[0]) ?: return null
        if (parts.size == 1) return head.takeIf { it.size == 8 }
        val tail = groups(parts[1]) ?: return null
        val missing = 8 - head.size - tail.size
        if (missing < 1) return null
        return head + List(missing) { 0 } + tail
    }
}
