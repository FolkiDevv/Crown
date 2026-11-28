package io.nekohasekai.sagernet.simple.control

import io.nekohasekai.sagernet.bg.BaseService

/**
 * The plain rules of a connection change asked from outside the app's screens (restrict-exported-vpn-controls): a
 * launcher shortcut, the toggle widget, an automation app's broadcast. Apart for JVM tests.
 */
object ControlRules {

    /** The extra with a profile id: launcher shortcuts of one server and the automation broadcasts. */
    const val EXTRA_PROFILE = "profile"

    enum class Request { CONNECT, DISCONNECT, TOGGLE }

    /** What a profile id from an intent comes to. */
    sealed interface Profile {
        /** No id given (a negative value, as the shortcuts write -1 for "the selected one"). */
        data object None : Profile

        data class Existing(val id: Long) : Profile

        /** An id with no profile behind it: a deleted server's shortcut, or a made-up id. Nothing changes. */
        data class Missing(val id: Long) : Profile
    }

    sealed interface Command {
        data object Ignore : Command

        /** Start the service, after selecting [select] when it is not null. */
        data class Start(val select: Long?) : Command

        data object Stop : Command

        /** Select [profile] and reload the running service. */
        data class Switch(val profile: Long) : Command
    }

    fun profile(raw: Long, exists: (Long) -> Boolean): Profile = when {
        raw < 0L -> Profile.None
        exists(raw) -> Profile.Existing(raw)
        else -> Profile.Missing(raw)
    }

    /**
     * The command for [request] while the service is in [state] with [selected] chosen. [profile] is the id already
     * checked by [profile]: a [Profile.Missing] one never changes anything. TOGGLE is what the upstream toggle
     * shortcut does: stop when running the asked (or no) profile, switch to another one, start when stopped.
     */
    fun decide(request: Request, state: BaseService.State, profile: Profile, selected: Long): Command {
        if (profile is Profile.Missing) return Command.Ignore
        val asked = (profile as? Profile.Existing)?.id
        val stopped = state == BaseService.State.Idle || state == BaseService.State.Stopped
        return when (request) {
            Request.DISCONNECT -> if (state.canStop) Command.Stop else Command.Ignore
            Request.CONNECT -> when {
                stopped -> Command.Start(asked)
                state.canStop && asked != null && asked != selected -> Command.Switch(asked)
                else -> Command.Ignore
            }

            Request.TOGGLE -> when {
                stopped -> Command.Start(asked)
                state.canStop -> if (asked == null || asked == selected) Command.Stop else Command.Switch(asked)
                else -> Command.Ignore
            }
        }
    }

    /** The automation action for an intent action, null for any other: "<applicationId>.action.CONNECT" and so on. */
    fun request(packageName: String, action: String?): Request? = when (action) {
        "$packageName$ACTION_CONNECT" -> Request.CONNECT
        "$packageName$ACTION_DISCONNECT" -> Request.DISCONNECT
        "$packageName$ACTION_TOGGLE" -> Request.TOGGLE
        else -> null
    }

    /**
     * Whether the exported BootReceiver got a boot broadcast, which only the system may send. Any other action is an
     * explicit broadcast from another app and must not count as a boot (it used to start the VPN).
     */
    fun isBootAction(action: String?): Boolean = action == BOOT_COMPLETED || action == LOCKED_BOOT_COMPLETED

    // Intent.ACTION_BOOT_COMPLETED / ACTION_LOCKED_BOOT_COMPLETED, spelled out for the JVM tests.
    private const val BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    private const val LOCKED_BOOT_COMPLETED = "android.intent.action.LOCKED_BOOT_COMPLETED"

    const val ACTION_CONNECT = ".action.CONNECT"
    const val ACTION_DISCONNECT = ".action.DISCONNECT"
    const val ACTION_TOGGLE = ".action.TOGGLE"
}
