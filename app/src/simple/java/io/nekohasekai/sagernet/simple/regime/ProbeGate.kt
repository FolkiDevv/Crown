package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.modes.TrafficMode

/**
 * Lets only the result of the latest check in the current physical network through (traffic-modes spec "Результат
 * проверки относится только к своей сети", design.md D6). Every check gets a [Ticket]: a generation number, the
 * physical network it runs on ([N], the network handle on the device) and the mode revision (`simple_mode_rev`) at its
 * start. A new check or a lost network makes all earlier tickets stale. Pure Kotlin, covered by JVM unit tests.
 */
class ProbeGate<N : Any> {

    data class Ticket<N>(val generation: Long, val network: N, val modeRevision: Long)

    /** Why a result is (not) applied, in the order the rules are checked. */
    enum class Verdict {
        ACCEPTED,

        /** A newer check started (or the network was lost, or the watcher stopped) after this one. */
        SUPERSEDED,

        /** The physical network is not the one the check ran on any more (the VPN network does not count). */
        OTHER_NETWORK,

        /** The user changed the mode since the check started, or the mode is not «Авто» any more. */
        MODE_CHANGED,
    }

    private var generation = 0L

    /** Starts a check on [network]; every earlier ticket is stale from now on. */
    @Synchronized
    fun begin(network: N, modeRevision: Long): Ticket<N> = Ticket(++generation, network, modeRevision)

    /** Makes every ticket handed out so far stale (the network was lost, the watcher stopped). */
    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isLatest(ticket: Ticket<N>): Boolean = ticket.generation == generation

    /**
     * Whether [ticket]'s result may be applied now: it is the latest check, [currentNetwork] (the physical network
     * now, null = none) is the one it ran on, and [mode] (null = never chosen) is still «Авто» at the revision the
     * check started with.
     */
    @Synchronized
    fun verdict(ticket: Ticket<N>, currentNetwork: N?, mode: TrafficMode?, modeRevision: Long): Verdict = when {
        !isLatest(ticket) -> Verdict.SUPERSEDED
        currentNetwork != ticket.network -> Verdict.OTHER_NETWORK
        mode != TrafficMode.AUTO || modeRevision != ticket.modeRevision -> Verdict.MODE_CHANGED
        else -> Verdict.ACCEPTED
    }
}
