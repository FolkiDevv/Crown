package io.nekohasekai.sagernet.simple.regime

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore

/**
 * The network check's result in DataStore (`simple_regime`, design.md D6), which both processes read the same way. The
 * physical network is `SagerNet.underlyingNetwork`, which both processes keep from DefaultNetworkListener (a request
 * without the VPN, so never the VPN network itself).
 */
object RegimeStore {

    const val KEY_REGIME = "simple_regime"

    fun read(): RegimeRecord? = RegimeRecord.parse(DataStore.configurationStore.getString(KEY_REGIME))

    fun write(record: RegimeRecord) = DataStore.configurationStore.putString(KEY_REGIME, record.encode())

    /** The handle of the physical network now, null when there is none. */
    fun currentNetwork(): Long? = SagerNet.underlyingNetwork?.networkHandle

    /** Whether «Авто» routes with the whitelist rules now: whitelists were found in this very physical network. */
    fun whitelistsDetected(): Boolean = read()?.whitelistsIn(currentNetwork()) == true

    /**
     * When the running VPN session first saw the physical network it is in (`handle@unix ms`), written by
     * NetworkRegimeWatcher: health older than this (another network, an earlier session, health the auto-selector
     * restored from storage) says nothing about which servers work here.
     */
    const val KEY_NETWORK_SINCE = "simple_network_since"

    /** Stores that the session saw [network] at [at] (unix ms); an older observation never replaces a newer one. */
    fun writeNetworkSince(network: Long, at: Long) {
        val stored = networkSince()
        if (stored != null && stored.second > at) return
        DataStore.configurationStore.putString(KEY_NETWORK_SINCE, "$network@$at")
    }

    /** The network and the time of [KEY_NETWORK_SINCE], null when missing or damaged. */
    fun networkSince(): Pair<Long, Long>? {
        val text = DataStore.configurationStore.getString(KEY_NETWORK_SINCE) ?: return null
        val network = text.substringBefore('@', "").toLongOrNull() ?: return null
        val at = text.substringAfter('@', "").toLongOrNull() ?: return null
        return network to at
    }

    /** Since when the physical network the device is in now has been seen by the running session; null when unknown. */
    fun currentNetworkSince(): Long? {
        val (network, at) = networkSince() ?: return null
        return at.takeIf { network == currentNetwork() }
    }
}
