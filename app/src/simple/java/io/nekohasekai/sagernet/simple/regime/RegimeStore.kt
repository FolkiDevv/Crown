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
}
