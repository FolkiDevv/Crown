package io.nekohasekai.sagernet.simple.regime

/** What a network check found (traffic-modes spec "Определение белых списков в сети"); [key] is what DataStore keeps. */
enum class Regime(val key: String) {
    /** At least one address outside the whitelists answers. */
    NORMAL("normal"),

    /** Some whitelisted address answers and none of the others does. */
    WHITELIST("whitelist"),

    /** Nothing answers: no internet, which says nothing about whitelists, so the traffic mode stays as it is. */
    NO_INTERNET("no_internet");

    companion object {
        fun of(key: String?): Regime? = entries.firstOrNull { it.key == key }
    }
}

/** One request of a check. Only an answer counts; a timeout is as unreachable as a refused connection. */
enum class ProbeOutcome {
    /** Any HTTP answer, whatever its status: the address can be reached. */
    REACHABLE,
    UNREACHABLE,
    TIMEOUT,

    /**
     * Failed because the network itself went away (unreachable, aborted, not on the network): says nothing about the
     * address, and makes the whole check inconclusive ([RegimeClassifier.inconclusive]).
     */
    NETWORK_GONE;

    val reachable: Boolean get() = this == REACHABLE
}

/** Tells a failure of the network itself from a failure of one address (pure, covered by JVM unit tests). */
object ProbeFailures {

    /** errno names and their texts that mean the network or the interface is gone, as Android's exceptions show them. */
    private val NETWORK_GONE = listOf(
        "ENETUNREACH", "Network is unreachable",
        "ENONET", "Machine is not on the network",
        "ENETDOWN", "Network is down",
        "ECONNABORTED", "Software caused connection abort",
        "EADDRNOTAVAIL", "Cannot assign requested address",
        "Binding socket to network",
    )

    fun isNetworkGone(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 8) {
            val message = current.message.orEmpty()
            if (NETWORK_GONE.any { message.contains(it, ignoreCase = true) }) return true
            current = current.cause?.takeIf { it !== current }
        }
        return false
    }
}

/**
 * The three outcomes of a check over the configured addresses (design.md D6), pure Kotlin for the JVM unit tests:
 * - at least one allowed address reachable and no blocked one → [Regime.WHITELIST];
 * - nothing reachable at all (both sets) → [Regime.NO_INTERNET];
 * - otherwise → [Regime.NORMAL]: a blocked address answered, or only blocked ones did.
 */
object RegimeClassifier {

    fun classify(allowed: List<ProbeOutcome>, blocked: List<ProbeOutcome>): Regime {
        val allowedUp = allowed.count { it.reachable }
        val blockedUp = blocked.count { it.reachable }
        return when {
            allowedUp > 0 && blockedUp == 0 -> Regime.WHITELIST
            allowedUp == 0 && blockedUp == 0 -> Regime.NO_INTERNET
            else -> Regime.NORMAL
        }
    }

    /**
     * A check is no evidence when a request failed because the network went away during it: the remaining answers
     * are partial (two allowed addresses answering before the drop would read as whitelists), so the result is
     * discarded and the network checked again.
     */
    fun inconclusive(allowed: List<ProbeOutcome>, blocked: List<ProbeOutcome>): Boolean =
        (allowed + blocked).any { it == ProbeOutcome.NETWORK_GONE }
}
