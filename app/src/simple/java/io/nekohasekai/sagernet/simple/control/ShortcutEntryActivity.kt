package io.nekohasekai.sagernet.simple.control

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs

/**
 * The launcher's intents to the toggle shortcut (restrict-exported-vpn-controls). The executors
 * ([QuickToggleShortcut], QuickEnableShortcut, QuickDisableShortcut) are not exported: on Android 7.1+ a dynamic
 * shortcut, and on Android 8+ a pinned one, is started by the system on the app's behalf, which may open an unexported
 * activity. A pinned shortcut on Android 7.0–7.1 is the old INSTALL_SHORTCUT kind, which the launcher starts itself:
 * that one opens [ShortcutEntryActivity], which asks first. A secret in the intent would not do: whoever asks for a
 * shortcut (ACTION_CREATE_SHORTCUT) reads the intent it gets.
 */
object ShortcutIntents {

    /** The legacy shortcut's action: confirm, then toggle. */
    const val ACTION_CONFIRM_TOGGLE = "io.nekohasekai.sagernet.simple.CONFIRM_TOGGLE"

    /** The pinned shortcuts are started by the system as the app from Android 8 (requestPinShortcut, ShortcutManager). */
    val pinnedRunAsApp: Boolean get() = Build.VERSION.SDK_INT >= 26

    /** The intent of a pinned toggle shortcut, of [profile] or (-1) of the selected profile. */
    fun pinnedToggle(context: Context, profile: Long = -1L): Intent {
        val target = if (pinnedRunAsApp) QuickToggleShortcut::class.java else ShortcutEntryActivity::class.java
        return Intent(context, target)
            .setAction(if (pinnedRunAsApp) Intent.ACTION_MAIN else ACTION_CONFIRM_TOGGLE)
            .apply { if (profile >= 0L) putExtra(ControlRules.EXTRA_PROFILE, profile) }
    }
}

/**
 * The only exported way to the launcher shortcuts. ACTION_CREATE_SHORTCUT (the launcher's shortcut picker) gets the
 * toggle shortcut and changes nothing. [ShortcutIntents.ACTION_CONFIRM_TOGGLE] (a legacy shortcut, Android 7.0–7.1)
 * asks «Disconnect?» / «Connect?» / «Switch to …?» and toggles only when the user agrees; on Android 8+ no shortcut of
 * the app comes this way, so it does nothing there.
 */
class ShortcutEntryActivity : Activity(), SagerConnection.Callback {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    private var profile: ControlRules.Profile = ControlRules.Profile.None
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            Intent.ACTION_CREATE_SHORTCUT -> {
                setResult(
                    RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(
                        this, ShortcutInfoCompat.Builder(this, "toggle")
                            .setIntent(ShortcutIntents.pinnedToggle(this))
                            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_qu_shadowsocks_launcher))
                            .setShortLabel(getString(R.string.quick_toggle))
                            .build()
                    )
                )
                finish()
            }

            ShortcutIntents.ACTION_CONFIRM_TOGGLE -> {
                if (ShortcutIntents.pinnedRunAsApp) {
                    Logs.w("shortcut: a legacy toggle intent on Android 8+, ignored")
                    finish()
                    return
                }
                profile = ExternalControl.profileOf(intent) ?: run {
                    finish()
                    return
                }
                if (profile is ControlRules.Profile.Missing) {
                    Toast.makeText(this, R.string.simple_shortcut_server_missing, Toast.LENGTH_SHORT).show()
                    finish()
                    return
                }
                connection.connect(this, this)
            }

            else -> finish()
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        if (isFinishing || dialog != null) return
        val state = BaseService.State.entries[service.state]
        val command = ControlRules.decide(ControlRules.Request.TOGGLE, state, profile, DataStore.selectedProxy)
        val message = when (command) {
            ControlRules.Command.Ignore -> {
                finish()
                return
            }

            is ControlRules.Command.Start -> command.select?.let { getString(R.string.simple_shortcut_confirm_connect_to, name(it)) }
                ?: getString(R.string.simple_shortcut_confirm_connect)

            ControlRules.Command.Stop -> getString(R.string.simple_shortcut_confirm_disconnect)
            is ControlRules.Command.Switch -> getString(R.string.simple_shortcut_confirm_switch, name(command.profile))
        }
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.app_name)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // From the app itself the unexported toggle is allowed; it checks the state again.
                startActivity(
                    Intent(this, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN).apply {
                        (profile as? ControlRules.Profile.Existing)?.let { putExtra(ControlRules.EXTRA_PROFILE, it.id) }
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { finish() }
            .show()
            .also {
                // Another app's window over the dialog must not tap «OK» for the user.
                it.getButton(DialogInterface.BUTTON_POSITIVE)?.filterTouchesWhenObscured = true
            }
    }

    private fun name(id: Long) = ProfileManager.getProfile(id)?.displayName() ?: id.toString()

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        connection.disconnect(this)
        super.onDestroy()
    }
}
