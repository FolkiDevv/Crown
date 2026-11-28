package io.nekohasekai.sagernet.simple.modes

import android.content.Context
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.simple.ConnectionClock
import io.nekohasekai.sagernet.simple.ServiceStateSync
import io.nekohasekai.sagernet.simple.SimpleHooks
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.regime.NetworkRegimeWatcher
import io.nekohasekai.sagernet.simple.regime.ProbeGate
import io.nekohasekai.sagernet.simple.regime.Regime
import io.nekohasekai.sagernet.simple.regime.RegimeRecord
import io.nekohasekai.sagernet.simple.regime.RegimeStore
import io.nekohasekai.sagernet.simple.regime.WhitelistZoneState
import io.nekohasekai.sagernet.simple.servers.CountryChoice
import io.nekohasekai.sagernet.simple.servers.ServerCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable

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

    /**
     * The user chose [mode]: it is stored (a new mode revision, design.md D6, so a network check that started before
     * is not applied) and applied. Choosing «Авто» asks a running network watcher for a check of the current network;
     * with the VPN off the check waits for the connection.
     */
    suspend fun select(context: Context, mode: TrafficMode, stagedScopeMayRun: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val result = lock.withLock {
                SagerDatabase.instance.runInTransaction {
                    TrafficModes.setFallbackNotice(false)
                    TrafficModes.setMode(mode)
                    TrafficModes.bumpModeRevision()
                }
                applyLocked(context, stagedScopeMayRun)
            }
            // «Авто», and a configuration for whitelists (traffic-modes «Подсказка вне зоны белых списков»), check the network.
            WhitelistZoneState.afterChoice(context, check = result.mode == TrafficMode.AUTO)
            result
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
            if (DataStore.currentRouteId in ensured.changedRoutes) {
                reloadWhenConnected("simple modes: the routing profile in use was updated, reload of the running service")
            } else if (countrySelectorChanged(ensured)) {
                reloadWhenConnected("simple servers: the chosen country's servers changed, reload of the running service")
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
        if (before != auto && result.change == ServiceChange.NONE) {
            reloadWhenConnected("simple modes: «Авто» set up, reload of the running service", verifiable = true)
        }
        true
    }

    /**
     * The user chose «Авто» or a single server in «Серверы» (simple-ui "Выбор сервера в списке"): «Авто» runs the
     * managed auto-selector of the current routing, any other choice is kept as made; a running service follows at
     * once. Returns the id now selected.
     */
    suspend fun selectServer(id: Long): Long = withContext(Dispatchers.IO) {
        lock.withLock { selectLocked(id) }.also { WhitelistZoneState.afterChoice(app) }
    }

    private fun selectLocked(id: Long): Long {
        val managed = TrafficModes.managedIds()
        val target = TrafficModePlan.server(id, managed.auto, managed.autoWhitelist, routingOf(TrafficModes.mode()))
        TrafficModes.setCountryChoice(null)
        return switchTo(target, rebuilt = false)
    }

    /**
     * The servers the running service may use changed in «Серверы» (a subscription or own server deleted, one restored):
     * a running «Авто» or country, which draws on whole groups, is rebuilt with the servers there now. A server chosen by
     * hand is not touched (deleting it clears the choice itself). Nothing while the service is off.
     */
    suspend fun serversChanged(): Unit = withContext(Dispatchers.IO) {
        lock.withLock { rebuildLocked() }
    }

    /**
     * A server or subscription was edited in a classic editor that asks for a restart: the running service, whatever it
     * runs, is rebuilt with the stored settings (the classic interface offers the same reload). Nothing while it is off.
     */
    suspend fun settingsChanged(): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val selected = DataStore.selectedProxy
            if (selected > 0L) switchTo(selected, rebuilt = true)
        }
    }

    private fun rebuildLocked() {
        val selected = DataStore.selectedProxy
        if (TrafficModes.isManagedSelector(selected)) switchTo(selected, rebuilt = true)
    }

    /**
     * An own server came back (Undo of its deletion): chosen again when it was the choice and nothing has been chosen
     * since (the deletion cleared the choice), otherwise taken in by a running «Авто» or country. Decided under the lock
     * every choice is applied under, so a choice made meanwhile is never overwritten.
     */
    suspend fun serverRestored(id: Long, wasSelected: Boolean): Unit = withContext(Dispatchers.IO) {
        val chosen = lock.withLock {
            if (wasSelected && DataStore.selectedProxy <= 0L) {
                selectLocked(id)
                true
            } else {
                rebuildLocked()
                false
            }
        }
        if (chosen) WhitelistZoneState.afterChoice(app)
    }

    /**
     * The user chose a country: the managed country selector runs the best of its servers (their names, read here);
     * 0 when no server of it can be told apart by name or the selector could not be made.
     */
    suspend fun selectCountry(context: Context, choice: CountryChoice): Long =
        withContext(Dispatchers.IO) {
            lock.withLock {
                // The members are read under the lock, so a synchronisation that ran meanwhile cannot be overwritten
                // with an older list.
                val members = ServerCatalog.countryMembers(choice, SimpleConfigRepo.current().wlKeywords)
                if (members.isEmpty()) return@withLock 0L
                val (id, changed) = TrafficModes.ensureCountrySelector(context, choice, members)
                if (id <= 0L) return@withLock 0L
                TrafficModes.setCountryChoice(choice)
                switchTo(id, rebuilt = changed)
            }.also { WhitelistZoneState.afterChoice(context) }
        }

    /** Selects [target]; a running service reloads when it changed or its selector was [rebuilt]. */
    private fun switchTo(target: Long, rebuilt: Boolean): Long {
        val before = DataStore.selectedProxy
        if (target != before) DataStore.selectedProxy = target
        if (target == before && !rebuilt) return target
        val state = serviceState()
        // A service stopping for a restart is watched too: it may have read the old choice already.
        if (isRunning(state)) {
            // `:bg` drops a reload that reaches it while it is stopping, and a reload that reaches a start still
            // connecting stops the service altogether (a cancelled start counts as a stop there). So a reload is sent at
            // once only to a service that is connected and not restarting; otherwise [settle] sends it once it is.
            val quiet = state == BaseService.State.Connected && settleJob?.isActive != true &&
                SystemClock.elapsedRealtime() - lastReloadAt >= RESTART_WAIT_MS
            if (quiet) {
                reloadNow("simple servers: server $target chosen, reload of the running service")
                settle(target)
            } else {
                Logs.i("simple servers: server $target chosen, applied once the service is connected")
                if (rebuilt) pendingRebuild = target
                settle(target)
                // This process may be gone before the service is connected: `:bg` checks the choice too.
                NetworkRegimeWatcher.requestVerify(app)
            }
        }
        return target
    }

    /** Watches that the running service ends up on [target]; one per choice, a newer one replaces it. */
    @Volatile
    private var settleJob: Job? = null
    private val settleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** `SystemClock.elapsedRealtime()` of the last reload sent from here. */
    @Volatile
    private var lastReloadAt = 0L

    /**
     * A selector rebuilt under the same id while the service could not reload yet (0 = none): the running core still
     * has its old members, so it needs one reload even though it runs that id. Kept here rather than in one watch, so a
     * watch that replaces it still honours it; any reload sent from here rebuilds the whole config and clears it.
     */
    @Volatile
    private var pendingRebuild = 0L

    /**
     * A traffic mode (routing profile, per-app scope or managed selector) was applied while the service could not
     * reload yet: the core that comes up may have read the settings before the change, so it reloads once when it is
     * connected. Cleared by any reload sent from here, which rebuilds the whole config.
     */
    @Volatile
    private var pendingApply = false

    /** Every reload of the running service from here, so the guard of [switchTo] and [settle] knows about it. */
    private fun reloadNow(message: String) {
        Logs.i(message)
        lastReloadAt = SystemClock.elapsedRealtime()
        pendingRebuild = 0L
        pendingApply = false
        SagerNet.reloadService()
    }

    /**
     * The reload a mode application asks for, by the rule of [switchTo]: sent at once only to a service that is
     * connected and not restarting (a reload that reaches a start still connecting stops the service altogether);
     * otherwise [settle] sends it once the service is connected. Nothing while the service is off.
     *
     * [verifiable]: the change is only the routing profile and the server, which [settle] can compare with what the
     * connected core runs; a change it cannot see (the per-app scope, a profile rewritten under the same id) is marked
     * pending and reloaded once.
     */
    private fun reloadWhenConnected(message: String, verifiable: Boolean = false) {
        val state = serviceState()
        if (!isRunning(state)) return
        val quiet = state == BaseService.State.Connected && settleJob?.isActive != true &&
            SystemClock.elapsedRealtime() - lastReloadAt >= RESTART_WAIT_MS
        if (quiet) {
            reloadNow(message)
        } else {
            Logs.i("$message — once the service is connected")
            if (!verifiable) pendingApply = true
        }
        settle(DataStore.selectedProxy)
        // This process may be gone before the service is connected: `:bg` checks the server and routing too.
        if (!quiet) NetworkRegimeWatcher.requestVerify(app)
    }

    /**
     * The service state to decide by (proxy-connection «UI process binding»). `DataStore.serviceState` follows the
     * service in `:bg` and while a binding of this process does (a screen); a process without one (started for a
     * notification's action, say) binds here and asks the service, and keeps that binding for [HOLD_MS] so the watch
     * of [settle] reads the state the service reports. Idle when the service gave no answer in [BIND_WAIT_MS]: nothing
     * is sent then that could reach a start still connecting.
     */
    private fun serviceState(): BaseService.State {
        if (app.isBgProcess) return DataStore.serviceState
        if (ServiceStateSync.isLive() && !holding()) return DataStore.serviceState
        hold()
        val deadline = SystemClock.elapsedRealtime() + BIND_WAIT_MS
        while (!ServiceStateSync.isLive() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(BIND_POLL_MS)
        if (ServiceStateSync.isLive()) return DataStore.serviceState
        Logs.w("simple modes: the service gave no state in $BIND_WAIT_MS ms, it is not reloaded from here")
        return BaseService.State.Idle
    }

    private val holdLock = Any()

    /** The binding [serviceState] made; null while there is none. */
    private var holder: SagerConnection? = null

    @Volatile
    private var holdUntil = 0L

    private fun holding(): Boolean = synchronized(holdLock) { holder != null }

    /** Binds now or keeps the binding [HOLD_MS] longer. */
    private fun hold() = synchronized(holdLock) {
        holdUntil = SystemClock.elapsedRealtime() + HOLD_MS
        if (holder != null) return@synchronized
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
        holder = connection
        runOnMainDispatcher { connection.connect(app, null) }
        settleScope.launch {
            while (true) {
                delay((holdUntil - SystemClock.elapsedRealtime()).coerceAtLeast(BIND_POLL_MS))
                val done = synchronized(holdLock) {
                    (SystemClock.elapsedRealtime() >= holdUntil).also { if (it) holder = null }
                }
                if (done) break
            }
            runOnMainDispatcher { connection.disconnect(app) }
        }
    }

    /**
     * Whether the service runs or is between two cores: a restart in place passes Stopped (and the new core reads the
     * settings right then), which the start time SimpleHooks keeps until a stop for good tells from a stop.
     */
    private fun isRunning(state: BaseService.State): Boolean =
        state.canStop || state == BaseService.State.Stopping || restartPending()

    private fun restartPending(): Boolean {
        val since = DataStore.configurationStore.getLong(SimpleHooks.KEY_CONNECTED_SINCE) ?: 0L
        return ConnectionClock.isValid(since, SystemClock.elapsedRealtime())
    }

    /**
     * Makes the running service end up on [target]: whenever it is connected (and not just asked to reload — a reload
     * sent moments ago may not have reached `:bg` yet), the profile it started with is compared with the choice and it
     * reloads if they differ, or once anyway while [pendingRebuild] names it (rebuilt under the same id).
     * Twice at most. A newer choice, a stopped service or the time limit ends the watch; at the time limit a service
     * still running is looked at once more: connected, it reloads if it missed the choice, otherwise the difference is
     * logged and `:bg` checks it once the service is connected.
     */
    private fun settle(target: Long) {
        settleJob?.cancel()
        settleJob = settleScope.launch {
            var reloads = 0
            var restartSeen = false
            val deadline = SystemClock.elapsedRealtime() + SETTLE_MS
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    delay(SETTLE_STEP_MS)
                    if (DataStore.selectedProxy != target) return@launch
                    val state = serviceState()
                    if (!isRunning(state)) {
                        // Stopped for good: the next start reads the settings as they are now.
                        pendingApply = false
                        return@launch
                    }
                    if (state != BaseService.State.Connected) {
                        restartSeen = true
                        continue
                    }
                    if (!restartSeen && SystemClock.elapsedRealtime() - lastReloadAt < RESTART_WAIT_MS) continue
                    val reason = missed(target) ?: return@launch
                    if (reloads >= 2) return@launch Logs.w("$reason — two reloads did not bring it, left as it is")
                    reloads++
                    restartSeen = false
                    reloadNow(reason)
                }
                if (!isActive || DataStore.selectedProxy != target) return@launch
                // The time is up with the service not seen connected (or just reloaded): never given up unsaid.
                val state = serviceState()
                if (!isRunning(state)) {
                    pendingApply = false
                    return@launch
                }
                val reason = if (state == BaseService.State.Connected) missed(target) else null
                val settled = SystemClock.elapsedRealtime() - lastReloadAt >= RESTART_WAIT_MS
                when {
                    state == BaseService.State.Connected && reason == null -> Unit
                    reason != null && settled && reloads < 2 -> reloadNow("$reason (the watch ran out)")
                    else -> {
                        Logs.w(
                            "simple servers: the watch for $target ran out with the service ${state.name.lowercase()}" +
                                (reason?.let { ", $it not sent" } ?: "") + "; `:bg` checks the running core"
                        )
                        NetworkRegimeWatcher.requestVerify(app)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logs.w(e)
            }
        }
    }

    /** Why the connected service has to reload for [target] (the chosen one); null when it runs it. */
    private fun missed(target: Long): String? {
        val mismatch = RunningCore.mismatch()
        return when {
            mismatch == RunningCore.Mismatch.SERVER ->
                "simple servers: the service runs ${DataStore.currentProfile}, not the chosen $target; reload"

            pendingApply || mismatch == RunningCore.Mismatch.ROUTE ->
                "simple modes: the connected service missed the applied mode; reload"

            pendingRebuild == target -> "simple servers: selector $target was rebuilt; reload"
            else -> null
        }
    }

    private const val SETTLE_MS = 30_000L
    private const val SETTLE_STEP_MS = 1_000L

    /** How long a reload sent from here may take to reach `:bg` and show as a state change. */
    private const val RESTART_WAIT_MS = 5_000L

    /** How long [serviceState] waits for a binding it made to hear from the service. */
    private const val BIND_WAIT_MS = 3_000L
    private const val BIND_POLL_MS = 50L

    /** How long a binding [serviceState] made stays after its last use: the whole watch of [settle]. */
    private const val HOLD_MS = SETTLE_MS + 5_000L

    private fun countrySelectorChanged(ensured: TrafficModes.Ensured): Boolean = ensured.countryGone ||
        ensured.countryChanged && ensured.ids.country > 0L && DataStore.selectedProxy == ensured.ids.country

    /** The routing [mode] runs now (TrafficModes.routingNow, the one place of the rule). */
    private fun routingOf(mode: TrafficMode): Routing = TrafficModes.routingNow(mode)

    /**
     * `:bg`, NetworkRegimeWatcher: a network check finished, or the physical network changed ([record] then has no
     * regime yet). Decided and written in one database write transaction, which also excludes a mode change the user
     * makes in the UI process meanwhile (design.md D6): [accept] judges from the stored mode (null = never chosen) and
     * its revision whether [record] still counts. If so it is stored, and while «Авто» runs on one of the managed
     * routing profiles, the routing and the managed auto-selector follow it. A routing profile chosen in the classic
     * interface and a server chosen by hand stay as they are. Returns what the running service has to do; the
     * watcher reloads it, and only once it is connected (a reload while it connects can stop it).
     */
    suspend fun applyRegime(
        context: Context,
        record: RegimeRecord,
        /** Route «Авто» by the whitelist rules; false keeps [record] but routes like «Умный» (the VPN stopped). */
        whitelist: Boolean = record.regime == Regime.WHITELIST,
        accept: (TrafficMode?, Long) -> ProbeGate.Verdict,
    ): Pair<ProbeGate.Verdict, ServiceChange> = withContext(Dispatchers.IO) {
        lock.withLock {
            val managed = TrafficModes.managedIds()
            var verdict = ProbeGate.Verdict.SUPERSEDED
            var change = ServiceChange.NONE
            SagerDatabase.instance.runInTransaction {
                val mode = if (TrafficModes.isModeStored()) TrafficModes.mode() else null
                verdict = accept(mode, TrafficModes.modeRevision())
                if (verdict != ProbeGate.Verdict.ACCEPTED) return@runInTransaction
                RegimeStore.write(record)
                val route = DataStore.currentRouteId
                val managedRoute = route > 0L && (route == managed.smartRoute || route == managed.whitelistRoute)
                if (mode != TrafficMode.AUTO || !managedRoute) return@runInTransaction
                // The rule of TrafficModes.routingNow, for the result being stored (routingNow reads it from DataStore
                // once written; here it is decided inside the same transaction).
                val routing = TrafficModePlan.routing(TrafficMode.AUTO, whitelist)
                val scope = PerAppScope(DataStore.proxyApps, DataStore.bypass, TrafficModePlan.parseList(DataStore.individual))
                val before = TrafficModePlan.Applied(route, scope, DataStore.selectedProxy)
                val after = TrafficModePlan.Applied(
                    routeId = when (routing) {
                        Routing.SMART -> managed.smartRoute
                        Routing.WHITELIST -> managed.whitelistRoute
                    }.takeIf { it > 0L } ?: route,
                    scope = scope,
                    server = TrafficModePlan.server(before.server, managed.auto, managed.autoWhitelist, routing),
                )
                if (after.routeId != before.routeId) DataStore.currentRouteId = after.routeId
                if (after.server != before.server) DataStore.selectedProxy = after.server
                change = TrafficModePlan.change(before, after)
            }
            verdict to change
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
        // Everything the plan is made of (the mode, the network check's result, the route and server in effect) is
        // read in the same write transaction that stores the plan: `:bg` stores a network result (applyRegime) in a
        // transaction of its own, so it lands either wholly before this one — and the plan follows it — or after it.
        val plan = SagerDatabase.instance.runInTransaction(Callable {
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
            if (after.routeId != before.routeId) DataStore.currentRouteId = after.routeId
            DataStore.proxyApps = scope.enabled
            DataStore.bypass = scope.bypass
            DataStore.individual = scope.individual
            if (after.server != before.server) DataStore.selectedProxy = after.server
            Plan(mode, fellBack, before, after)
        })
        val mode = plan.mode
        val fellBack = plan.fellBack
        val before = plan.before
        val after = plan.after
        val change = TrafficModePlan.change(before, after, ensured.changedRoutes, stagedScopeMayRun)
        if (change != ServiceChange.NONE) {
            reloadWhenConnected(
                "simple modes: «${mode.key}» applied, ${change.name.lowercase()} of the running service",
                verifiable = change == ServiceChange.RELOAD && after.routeId !in ensured.changedRoutes,
            )
        } else if (countrySelectorChanged(ensured)) {
            reloadWhenConnected("simple servers: the chosen country's servers changed, reload of the running service")
        }
        return Result(mode, change, fellBack)
    }

    /** What [applyLocked] decided and wrote in its transaction. */
    private class Plan(
        val mode: TrafficMode,
        val fellBack: Boolean,
        val before: TrafficModePlan.Applied,
        val after: TrafficModePlan.Applied,
    )
}
