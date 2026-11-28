package io.nekohasekai.sagernet.simple.modes

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteManager

/**
 * Whether the core a connected service runs is the one a start would build now from the stored choice: the server
 * (`selectedProxy`) and the routing profile that server is built with. TrafficModeApplier's watch and `:bg`'s check
 * (NetworkRegimeWatcher) decide by the same rule, so one never reloads what the other finds right.
 */
object RunningCore {

    enum class Mismatch { SERVER, ROUTE }

    /** Reads the stored choice and what the running core was started with (call only while it is connected). */
    fun mismatch(): Mismatch? {
        val target = target()
        return mismatch(DataStore.currentProfile, DataStore.runningRouteId, target.selected, target.route)
    }

    /**
     * [runningRoute] 0: a custom full config, which has no routing profile of the app; [expectedRoute] 0: the selected
     * server is gone. Neither counts as a difference: a reload could not bring it (without the server it would stop
     * the service).
     */
    fun mismatch(runningProfile: Long, runningRoute: Long, selected: Long, expectedRoute: Long): Mismatch? = when {
        selected <= 0L || expectedRoute <= 0L -> null
        runningProfile != selected -> Mismatch.SERVER
        runningRoute > 0L && runningRoute != expectedRoute -> Mismatch.ROUTE
        else -> null
    }

    /** What a check reloaded the core for: the choice it was asked to bring, so the same one is not reloaded twice. */
    data class Target(val selected: Long, val route: Long)

    /**
     * `:bg`'s check reloads a core once per [Target]: when the core still differs after a reload made for the same
     * choice, the reload cannot bring it (the start reads something else), and reloading again would only loop.
     */
    class ReloadGuard {
        private var last: Target? = null

        @Synchronized
        fun admit(target: Target): Boolean {
            if (last == target) return false
            last = target
            return true
        }

        /** The core runs the choice: a later difference for the same choice is a new one. */
        @Synchronized
        fun settled() {
            last = null
        }
    }

    /** The stored choice: the server and the routing profile a start builds it with (BoxInstance.buildConfig). */
    fun target(): Target {
        val selected = DataStore.selectedProxy
        val route = ProfileManager.getProfile(selected)?.let { RouteManager.routeIdFor(it.groupId) } ?: 0L
        return Target(selected, route)
    }
}
