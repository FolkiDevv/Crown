package io.nekohasekai.sagernet.simple.modes

import android.content.Context
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleHooks
import io.nekohasekai.sagernet.simple.servers.CountryChoice
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
            } else if (countrySelectorChanged(ensured) && DataStore.serviceState.canStop) {
                Logs.i("simple servers: the chosen country's servers changed, reload of the running service")
                SagerNet.reloadService()
            }
            ensured
        }
    }

    /**
     * The ready settings of the first subscription (simple-ui "Готовые настройки после импорта подписки"): the
     * managed «Авто» selector becomes the server and «Авто» the traffic mode. True when both were set. Independent of
     * any screen, so the add flow runs it together with the download it follows.
     */
    suspend fun setUpAuto(context: Context): Boolean = withContext(Dispatchers.IO) {
        val auto = ensure(context).ids.auto
        if (auto <= 0L) return@withContext false
        val before = DataStore.selectedProxy
        DataStore.selectedProxy = auto
        val result = select(context, TrafficMode.AUTO)
        // The applier compares with the server already switched above; a running service follows here.
        if (before != auto && result.change == ServiceChange.NONE && DataStore.serviceState.canStop) {
            SagerNet.reloadService()
        }
        true
    }

    /**
     * The user chose «Авто» or a single server in «Серверы» (simple-ui "Выбор сервера в списке"): «Авто» runs the
     * managed auto-selector of the current routing, any other choice is kept as made; a running service follows at
     * once. Returns the id now selected.
     */
    suspend fun selectServer(id: Long): Long = withContext(Dispatchers.IO) {
        lock.withLock {
            val managed = TrafficModes.managedIds()
            val target = TrafficModePlan.server(id, managed.auto, managed.autoWhitelist, routingOf(TrafficModes.mode()))
            TrafficModes.setCountryChoice(null)
            switchTo(target, rebuilt = false)
        }
    }

    /**
     * The user chose a country: the managed country selector runs the best of [members] (the names of its servers);
     * 0 when the selector could not be made.
     */
    suspend fun selectCountry(context: Context, choice: CountryChoice, members: List<String>): Long =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val (id, changed) = TrafficModes.ensureCountrySelector(context, choice, members)
                if (id <= 0L) return@withLock 0L
                TrafficModes.setCountryChoice(choice)
                switchTo(id, rebuilt = changed)
            }
        }

    /** Selects [target]; a running service reloads when it changed or its selector was [rebuilt]. */
    private fun switchTo(target: Long, rebuilt: Boolean): Long {
        val before = DataStore.selectedProxy
        if (target != before) DataStore.selectedProxy = target
        if ((target != before || rebuilt) && DataStore.serviceState.canStop) {
            Logs.i("simple servers: server $target chosen, reload of the running service")
            SagerNet.reloadService()
        }
        return target
    }

    private fun countrySelectorChanged(ensured: TrafficModes.Ensured): Boolean = ensured.countryGone ||
        ensured.countryChanged && ensured.ids.country > 0L && DataStore.selectedProxy == ensured.ids.country

    /** The routing [mode] runs now; batch 6 adds the network check's result for «Авто». */
    private fun routingOf(mode: TrafficMode): Routing = TrafficModes.routingNow(mode)

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
        val routing = routingOf(mode)
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
        } else if (countrySelectorChanged(ensured) && DataStore.serviceState.canStop) {
            Logs.i("simple servers: the chosen country's servers changed, reload of the running service")
            SagerNet.reloadService()
        }
        return Result(mode, change, fellBack)
    }
}
