package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.modes.TrafficMode

/**
 * Lets only the result of the latest check in the current physical network through (traffic-modes spec "Результат
 * проверки относится только к своей сети", design.md D6). Every check gets a [Ticket]: a generation number, the
 * physical network it runs on ([N], the network handle on the device), the mode revision (`simple_mode_rev`) and the
 * mode at its start: «Авто», or the mode the user runs a configuration for whitelists in (traffic-modes «Подсказка вне
 * зоны белых списков»). A new check or a lost network makes all earlier tickets stale. Pure Kotlin, covered by JVM
 * unit tests.
 */
class ProbeGate<N : Any> {

    data class Ticket<N>(val generation: Long, val network: N, val modeRevision: Long, val mode: TrafficMode?)

    /** Why a result is (not) applied, in the order the rules are checked. */
    enum class Verdict {
        ACCEPTED,

        /** A newer check started (or the network was lost, or the watcher stopped) after this one. */
        SUPERSEDED,

        /** The physical network is not the one the check ran on any more (the VPN network does not count). */
        OTHER_NETWORK,

        /** The user changed the mode since the check started, or the mode is not the one it started in any more. */
        MODE_CHANGED,
    }

    private var generation = 0L

    /** Starts a check on [network] in [mode]; every earlier ticket is stale from now on. */
    @Synchronized
    fun begin(network: N, modeRevision: Long, mode: TrafficMode? = TrafficMode.AUTO): Ticket<N> =
        Ticket(++generation, network, modeRevision, mode)

    /** Makes every ticket handed out so far stale (the network was lost, the watcher stopped). */
    @Synchronized
    fun invalidate() {
        generation++
    }

    @Synchronized
    fun isLatest(ticket: Ticket<N>): Boolean = ticket.generation == generation

    /**
     * Whether [ticket]'s result may be applied now: it is the latest check, [currentNetwork] (the physical network
     * now, null = none) is the one it ran on, and [mode] (null = never chosen) is still the mode and the revision the
     * check started with. Only a result in «Авто» changes the routing (TrafficModeApplier.applyRegime); in another mode
     * it is only stored, for the hint.
     */
    @Synchronized
    fun verdict(ticket: Ticket<N>, currentNetwork: N?, mode: TrafficMode?, modeRevision: Long): Verdict = when {
        !isLatest(ticket) -> Verdict.SUPERSEDED
        currentNetwork != ticket.network -> Verdict.OTHER_NETWORK
        mode != ticket.mode || modeRevision != ticket.modeRevision -> Verdict.MODE_CHANGED
        else -> Verdict.ACCEPTED
    }
}
