package io.nekohasekai.sagernet.simple.regime

/**
 * The stored result of the network checks (DataStore `simple_regime`, design.md D6): the [regime] found in the
 * physical [network] (its handle), or null while that network has not been checked yet. «Авто» only follows a result
 * of the physical network it runs in now; until there is one it routes like «Умный».
 */
data class RegimeRecord(val regime: Regime?, val network: Long) {

    fun encode(): String = "${regime?.key ?: PENDING}@$network"

    /** Whether «Авто» routes with the whitelist rules in [currentNetwork] (the physical network now, null = none). */
    fun whitelistsIn(currentNetwork: Long?): Boolean =
        regime == Regime.WHITELIST && currentNetwork != null && network == currentNetwork

    companion object {
        private const val PENDING = "pending"

        /** null for a missing or damaged value. */
        fun parse(text: String?): RegimeRecord? {
            val at = text?.lastIndexOf('@') ?: return null
            if (at <= 0) return null
            val network = text.substring(at + 1).toLongOrNull() ?: return null
            val key = text.substring(0, at)
            if (key == PENDING) return RegimeRecord(null, network)
            return RegimeRecord(Regime.of(key) ?: return null, network)
        }
    }
}
