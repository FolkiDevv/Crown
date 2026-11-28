package io.nekohasekai.sagernet.simple.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs

/**
 * «Разрешить управление VPN другим приложениям» (restrict-exported-vpn-controls, proxy-connection "External control
 * actions"): off by default; while it is on, [ExternalControlReceiver] lets an automation app (Tasker and the like)
 * connect, disconnect or toggle, optionally with the id of an existing profile. The switch lives in the shared settings
 * and is read on every broadcast in the process that receives it (`:bg`), so it acts at once; it is device-local, so
 * a backup file never turns it on.
 */
object ExternalControl {

    const val KEY_ALLOWED = "simple_external_control"

    fun isAllowed(): Boolean = try {
        DataStore.configurationStore.getBoolean(KEY_ALLOWED) == true
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    fun setAllowed(on: Boolean) = DataStore.configurationStore.putBoolean(KEY_ALLOWED, on)

    fun profileExists(id: Long): Boolean = try {
        ProfileManager.getProfile(id) != null
    } catch (e: Exception) {
        Logs.w(e)
        false
    }

    /**
     * Carries out [command] the way the app's own controls do: a start goes through [SagerNet.startService] (an
     * explicit start: it marks the connection as wanted and starts the restore count over), a stop through
     * [SagerNet.stopService] (clears the mark), a switch selects the profile and reloads.
     */
    fun run(command: ControlRules.Command) {
        when (command) {
            ControlRules.Command.Ignore -> Unit
            is ControlRules.Command.Start -> {
                command.select?.let { DataStore.selectedProxy = it }
                SagerNet.startService()
            }

            ControlRules.Command.Stop -> SagerNet.stopService()
            is ControlRules.Command.Switch -> {
                DataStore.selectedProxy = command.profile
                SagerNet.reloadService()
            }
        }
    }
}

/**
 * The one entry point another app may use to change the connection, `<applicationId>.action.CONNECT|DISCONNECT|TOGGLE`
 * with an optional long extra `profile`. Ignored while [ExternalControl.isAllowed] is off. It never asks for VPN
 * consent (no activity may start from here) and never changes anything else: no settings, no imports, no profile that
 * does not exist.
 */
class ExternalControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val request = ControlRules.request(context.packageName, intent.action) ?: return
            if (!ExternalControl.isAllowed()) {
                Logs.w("external control: ${request.name} ignored, other apps may not control the VPN")
                return
            }
            val profile = ControlRules.profile(
                intent.getLongExtra(ControlRules.EXTRA_PROFILE, -1L), ExternalControl::profileExists
            )
            if (profile is ControlRules.Profile.Missing) {
                Logs.w("external control: ${request.name} ignored, no profile ${profile.id}")
                return
            }
            val command = ControlRules.decide(request, DataStore.serviceState, profile, DataStore.selectedProxy)
            if (command is ControlRules.Command.Start) {
                if (command.select == null && DataStore.selectedProxy <= 0L) {
                    Logs.w("external control: ${request.name} ignored, no profile selected")
                    return
                }
                if (DataStore.serviceMode == Key.MODE_VPN && VpnService.prepare(context) != null) {
                    // The consent dialog needs the user in front: open the app and connect there once.
                    Logs.w("external control: ${request.name} ignored, no VPN consent")
                    return
                }
            }
            Logs.i("external control: ${request.name} -> $command")
            ExternalControl.run(command)
        } catch (e: Exception) {
            // A start Android refuses from the background (ForegroundServiceStartNotAllowedException), a bad extra.
            Logs.w("external control: failed", e)
        }
    }
}
