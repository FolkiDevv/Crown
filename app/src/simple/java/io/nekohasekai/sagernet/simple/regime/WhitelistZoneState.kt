package io.nekohasekai.sagernet.simple.regime

import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.simple.modes.TrafficModeApplier
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.simple.modes.WhitelistKeywords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The stored state behind [WhitelistZone], read the same way in both processes: `:bg` (NetworkRegimeWatcher) posts
 * and repeats the notification, the UI process shows the home card and runs the two actions. All reads block.
 */
object WhitelistZoneState {

    /** The physical network (its handle) where the user chose to keep the configuration for whitelists. */
    const val KEY_KEPT_NETWORK = "simple_wl_zone_kept"

    /** `:bg` stored a check's result: the home screen reads the warning again. Only this app's processes get it. */
    const val ACTION_CHANGED = "io.nekohasekai.sagernet.simple.WHITELIST_ZONE_CHANGED"

    private val store get() = DataStore.configurationStore

    /** What of the configuration in effect now is for whitelists, null when nothing. */
    fun use(): WhitelistZone.Use? {
        val mode = if (TrafficModes.isModeStored()) TrafficModes.mode() else null
        return WhitelistZone.use(mode, selectedWhitelistServer())
    }

    /** The configuration to warn about in [network] (the physical network now, null = none); null = no warning. */
    fun warning(network: Long?): WhitelistZone.Use? {
        val use = use() ?: return null
        return use.takeIf { WhitelistZone.warns(it, RegimeStore.read(), network, keptNetwork()) }
    }

    fun keptNetwork(): Long? = store.getLong(KEY_KEPT_NETWORK)

    /** The selected profile is a plain server for whitelists (an auto-selector never is: «Авто» follows the network). */
    private fun selectedWhitelistServer(): Boolean {
        val profile = ProfileManager.getProfile(DataStore.selectedProxy) ?: return false
        if (profile.type == AutoSelectorProfiles.TYPE) return false
        return WhitelistKeywords.isWhitelistServer(profile.outbound.name.trim(), SimpleConfigRepo.current().wlKeywords)
    }

    /**
     * «Не напоминать»: the configuration stays, and neither the card nor the notification comes back in the network
     * the latest check ran in (the warning was about that one). Another network warns again.
     */
    suspend fun keep(context: Context) = withContext(Dispatchers.IO) {
        try {
            val network = RegimeStore.read()?.network
            if (network != null) store.putLong(KEY_KEPT_NETWORK, network)
            Logs.i("simple wl zone: the user keeps the configuration for whitelists in network $network")
        } catch (e: Exception) {
            Logs.w(e)
        }
        WhitelistZoneNotifications.cancel(context)
        NetworkRegimeWatcher.warningOff(context)
        changed(context)
    }

    /**
     * «Переключить на «Авто»»: the mode «Белые списки» becomes «Авто», and a server for whitelists chosen by hand
     * gives way to the managed «Авто» selector; both follow the network from then on. False when a server for
     * whitelists is still selected afterwards (no «Авто» selector yet): the caller opens the server list.
     */
    suspend fun leave(context: Context): Boolean = withContext(Dispatchers.IO) {
        val done = try {
            if (TrafficModes.isModeStored() && TrafficModes.mode() == TrafficMode.WHITELIST) {
                TrafficModeApplier.select(context, TrafficMode.AUTO)
            }
            if (selectedWhitelistServer()) {
                // The selector may have been deleted in the classic interface and not made again yet.
                val auto = TrafficModes.managedIds().auto
                if (ProfileManager.getProfile(auto) != null) TrafficModeApplier.selectServer(auto)
            }
            use() == null
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
        Logs.i("simple wl zone: switched to «Авто» (configuration for whitelists left: $done)")
        if (done) WhitelistZoneNotifications.cancel(context)
        changed(context)
        done
    }

    /**
     * After the user chose a mode or a server in the UI process: a configuration for whitelists asks a running watcher
     * to check the network (only then are such checks made outside «Авто»); any other one takes the notification away
     * at once (`:bg` stops repeating it at its next turn). [check]: a check is asked for anyway (the user chose «Авто»).
     */
    fun afterChoice(context: Context, check: Boolean = false) {
        val use = try {
            use()
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
        if (use != null || check) NetworkRegimeWatcher.requestCheck(context)
        if (use == null) {
            WhitelistZoneNotifications.cancel(context)
            NetworkRegimeWatcher.warningOff(context)
        }
    }

    /** [ACTION_CHANGED] from `:bg`: the physical network the watcher is in, [NO_NETWORK] for none. */
    const val EXTRA_NETWORK = "network"
    const val NO_NETWORK = 0L

    /**
     * Tells the home screen to read the warning again. `:bg` passes its physical network ([fromWatcher]): the UI
     * process learns of a new network on its own, maybe only after this arrives.
     */
    fun changed(context: Context, network: Long? = null, fromWatcher: Boolean = false) {
        val intent = Intent(ACTION_CHANGED).setPackage(context.packageName)
        if (fromWatcher) intent.putExtra(EXTRA_NETWORK, network ?: NO_NETWORK)
        context.sendBroadcast(intent, "${context.packageName}.SERVICE")
    }
}
