package io.nekohasekai.sagernet.simple.modes

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Puts the chosen traffic mode into effect (design.md D5): `currentRouteId` (the managed smart or whitelist route),
 * the per-app scope from the two lists the simple UI keeps, and — only when the user runs one of the managed
 * auto-selectors — `selectedProxy`. A manually chosen server is never changed.
 *
 * A running service follows at once: a reload rebuilds the core in place. A reload also closes the TUN interface and
 * establishes it again (VpnService.killProcesses / openTun), so a changed per-app scope takes effect by the same path —
 * that is the full restart D5 asks for, without the service and its notification going away.
 */
object TrafficModeApplier {

    /** One application at a time in this process; the DataStore writes of one also go in one transaction. */
    private val lock = Mutex()

    /** What an application did, for the screens. */
    data class Result(
        val mode: TrafficMode,
        val change: ServiceChange,
        /** «Только выбранные приложения» had no app left and «Авто» took over. */
        val fellBack: Boolean,
    )

    /** The user chose [mode]: it is stored (a new mode revision, design.md D6) and applied. */
    suspend fun select(context: Context, mode: TrafficMode, stagedScopeMayRun: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            lock.withLock {
                TrafficModes.setFallbackNotice(false)
                TrafficModes.setMode(mode)
                TrafficModes.bumpModeRevision()
                applyLocked(context, stagedScopeMayRun)
            }
        }

    /**
     * Applies the stored mode again (after a list changed, or on the first start of the simple UI, which takes the
     * classic per-app list over first, see [TrafficModes.seedFromClassic]).
     */
    suspend fun apply(context: Context, stagedScopeMayRun: Boolean = false): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            TrafficModes.seedFromClassic()
            applyLocked(context, stagedScopeMayRun)
        }
    }

    /**
     * Brings the managed entities up to date without applying a mode; a running service reloads when the routing
     * profile it runs was rewritten (a newer configuration, a classic edit put back).
     */
    suspend fun ensure(context: Context): TrafficModes.Ensured = withContext(Dispatchers.IO) {
        lock.withLock {
            val ensured = TrafficModes.ensureManaged(context)
            if (DataStore.currentRouteId in ensured.changedRoutes && DataStore.serviceState.canStop) {
                Logs.i("simple modes: the routing profile in use was updated, reload of the running service")
                SagerNet.reloadService()
            }
            ensured
        }
    }

    /** A counter of service starts (SimpleHooks), to tell whether the service started during an app list edit. */
    fun serviceStarts(): Long = DataStore.configurationStore.getLong(SimpleHooks.KEY_SERVICE_STARTS) ?: 0L

    /**
     * Whether the per-app keys hold an include list without apps, which Android would turn into "every app through
     * the VPN" on the next start (left behind by an app list edit that never came back, for instance).
     */
    fun hasEmptyIncludeList(): Boolean =
        DataStore.proxyApps && !DataStore.bypass && TrafficModePlan.parseList(DataStore.individual).isEmpty()

    private suspend fun applyLocked(context: Context, stagedScopeMayRun: Boolean = false): Result {
        val ensured = TrafficModes.ensureManaged(context)
        val managed = ensured.ids
        var mode = TrafficModes.mode()
        val selected = TrafficModes.selectedApps()
        val applicable = TrafficModePlan.applicable(mode, selected)
        val fellBack = applicable != mode
        if (fellBack) {
            Logs.i("simple modes: no app selected, «${mode.key}» falls back to «${applicable.key}»")
            TrafficModes.setMode(applicable)
            TrafficModes.setFallbackNotice(true)
            mode = applicable
        }
        val routing = TrafficModePlan.routing(mode, whitelistsDetected = false)
        val routeId = when (routing) {
            Routing.SMART -> managed.smartRoute
            Routing.WHITELIST -> managed.whitelistRoute
        }.takeIf { it > 0L } ?: DataStore.currentRouteId
        val scope = TrafficModePlan.scope(mode, selected, TrafficModes.bypassApps(), context.packageName)
        val before = TrafficModePlan.Applied(
            routeId = DataStore.currentRouteId,
            scope = PerAppScope(DataStore.proxyApps, DataStore.bypass, TrafficModePlan.parseList(DataStore.individual)),
            server = DataStore.selectedProxy,
        )
        val after = TrafficModePlan.Applied(
            routeId = routeId,
            scope = scope,
            server = TrafficModePlan.server(before.server, managed.auto, managed.autoWhitelist, routing),
        )
        SagerDatabase.instance.runInTransaction {
            if (after.routeId != before.routeId) DataStore.currentRouteId = after.routeId
            DataStore.proxyApps = scope.enabled
            DataStore.bypass = scope.bypass
            DataStore.individual = scope.individual
            if (after.server != before.server) DataStore.selectedProxy = after.server
        }
        val change = TrafficModePlan.change(before, after, ensured.changedRoutes, stagedScopeMayRun)
        if (change != ServiceChange.NONE && DataStore.serviceState.canStop) {
            Logs.i("simple modes: «${mode.key}» applied, ${change.name.lowercase()} of the running service")
            SagerNet.reloadService()
        }
        return Result(mode, change, fellBack)
    }
}
