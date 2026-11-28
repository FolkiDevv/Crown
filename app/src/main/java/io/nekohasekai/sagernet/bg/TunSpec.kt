package io.nekohasekai.sagernet.bg

/**
 * Everything [VpnService.openTun] gives the VPN builder, apart from the underlying network (updated in place). A
 * restart keeps the interface it has only for an equal spec: Android resolves the app lists to uids at establish(),
 * so [allowed] and [denied] carry each package with its uid ([NOT_INSTALLED] while it is not installed) — the builder
 * accepts a package that is not installed without complaint, and one installed later only gets in with a new one.
 */
internal data class TunSpec(
    val mtu: Any?,
    val autoRoute: Any?,
    val dnsMode: Any?,
    val dns: List<String>,
    /** Addresses, routes, excluded routes and pre-split ranges, IPv4 and IPv6. */
    val routes: List<List<Pair<String, Int>>>,
    val allowed: List<Pair<String, Int>>,
    val denied: List<Pair<String, Int>>,
    /** Server, port and bypass domains; null without the HTTP proxy. */
    val httpProxy: List<Any?>?,
    val bypass: Boolean,
    val metered: Boolean,
) {

    /** The names of the fields that differ from [other], for the log; "none" when equal, "no-interface" without one. */
    fun diff(other: TunSpec?): String {
        if (other == null) return "no-interface"
        val fields = listOf(
            "mtu" to (mtu == other.mtu), "autoRoute" to (autoRoute == other.autoRoute),
            "dnsMode" to (dnsMode == other.dnsMode), "dns" to (dns == other.dns), "routes" to (routes == other.routes),
            "allowed" to (allowed == other.allowed), "denied" to (denied == other.denied),
            "httpProxy" to (httpProxy == other.httpProxy), "bypass" to (bypass == other.bypass),
            "metered" to (metered == other.metered),
        )
        return fields.filterNot { it.second }.joinToString(",") { it.first }.ifEmpty { "none" }
    }

    /**
     * Whether an interface built from this spec cannot be handed [next]'s network settings in place: Android sets a
     * network's metered flag ([meteredApplies]: API 29+, where the builder has it) and its uids only when it creates
     * the network (Vpn.agentConnect), and hands the network over whenever the package names are the same
     * (Vpn.updateLinkPropertiesInPlaceIfPossible). Then the old interface has to go first, as a plain restart, so the
     * next establish() makes a new network. Other package names make Android create one by itself.
     */
    fun needsNewNetwork(next: TunSpec, meteredApplies: Boolean): Boolean =
        (meteredApplies && metered != next.metered) ||
            (names(allowed) == names(next.allowed) && names(denied) == names(next.denied) &&
                (allowed != next.allowed || denied != next.denied))

    /** This spec with the uids the packages have now and [metered]: what the same configuration would give now. */
    fun now(metered: Boolean, uidOf: (String) -> Int?): TunSpec =
        copy(allowed = apps(names(allowed), uidOf), denied = apps(names(denied), uidOf), metered = metered)

    /** Package and installed counts of both lists, for the log: "allowed=2/1 denied=0/0". */
    fun appCounts(): String =
        "allowed=${allowed.size}/${allowed.count { it.second != NOT_INSTALLED }} " +
            "denied=${denied.size}/${denied.count { it.second != NOT_INSTALLED }}"

    companion object {
        const val NOT_INSTALLED = -1

        /** [packages] with the uid [uidOf] finds for each, [NOT_INSTALLED] where it finds none; one lookup a name. */
        fun apps(packages: List<String>, uidOf: (String) -> Int?): List<Pair<String, Int>> {
            val found = HashMap<String, Int>()
            return packages.map { name -> name to found.getOrPut(name) { uidOf(name) ?: NOT_INSTALLED } }
        }

        private fun names(apps: List<Pair<String, Int>>) = apps.map { it.first }
    }
}
