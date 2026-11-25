package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.simple.modes.TrafficMode

/**
 * The user still runs a configuration made for whitelists in a network without them (traffic-modes «Подсказка вне
 * зоны белых списков»): the mode «Белые списки», or a server for whitelists chosen by hand. «Авто» follows the network
 * by itself, so its managed selectors never count. Pure Kotlin, covered by JVM unit tests; the reads of the stored
 * state are in [WhitelistZoneState].
 */
object WhitelistZone {

    /** What makes the configuration one for whitelists; the hint's text names it. */
    enum class Use {
        /** The traffic mode «Белые списки» (whatever server runs). */
        MODE,

        /** A plain server for whitelists chosen by hand, in any other mode. */
        SERVER,
    }

    /**
     * What of the configuration is for whitelists: [mode] is the stored traffic mode (null = never chosen),
     * [whitelistServer] whether the selected profile is a plain server for whitelists (not an auto-selector).
     */
    fun use(mode: TrafficMode?, whitelistServer: Boolean): Use? = when {
        mode == TrafficMode.WHITELIST -> Use.MODE
        whitelistServer -> Use.SERVER
        else -> null
    }

    /**
     * Whether to warn: [use] is set, the latest check of the physical network the device is in now ([currentNetwork],
     * null = none) found no whitelists, and the user did not ask to keep the configuration in this network
     * ([keptNetwork]). A result of another network, a network not checked yet, or one without internet never warns.
     */
    fun warns(use: Use?, record: RegimeRecord?, currentNetwork: Long?, keptNetwork: Long?): Boolean =
        use != null && currentNetwork != null && record != null && record.network == currentNetwork &&
            record.regime == Regime.NORMAL && keptNetwork != currentNetwork
}
