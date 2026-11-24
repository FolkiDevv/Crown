package io.nekohasekai.sagernet.simple.repair

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteDnsSettings
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.config.SimpleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * The reload a run of «Починка» could not send before it ended ([PendingReload]): one per process, so it outlives the
 * run and the screen. It watches the state `:bg` stores, as the server choice of the traffic modes does.
 */
object RepairReloads {
    /** Work that outlives a run: the owed reload's watcher, binder calls a wait gave up on. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pending = PendingReload(
        phase = {
            val state = DataStore.serviceState
            when {
                state == BaseService.State.Connected -> ServicePhase.CONNECTED
                !state.started && state != BaseService.State.Stopping -> ServicePhase.OFF
                else -> ServicePhase.STARTING
            }
        },
        sendReload = { SagerNet.reloadService() },
        scope = scope,
        log = { Logs.i("simple repair: $it") },
    )

    /**
     * A call to `:bg` within [limitMs] ([detachedCall]): binding to the service has no deadline of its own, so a wait
     * over the limit or a stop gives the binding up and nothing is submitted after it. Throws when it did not happen.
     */
    suspend fun <T> detached(limitMs: Long, block: suspend (claim: () -> Boolean) -> T): T =
        detachedCall(scope, limitMs, block) ?: throw IllegalStateException("the service did not answer in time")
}

/**
 * The automatic fixes of «Починки» on the device (design.md D8, connection-repair "Безопасные автоматические
 * исправления"): only settings of this app, each read before it is changed so it can be put back.
 *
 * - «DNS напрямую» does not answer → the direct DNS becomes the whitelisted DNS servers of the configuration;
 * - «Подписка» ended, used up or empty → the subscriptions are updated (the provider may have renewed them);
 * - «Доступность серверов»: the current server is down → the answering server chosen for the checks through the VPN is
 *   selected; no server answers → the subscriptions are updated, unless that was done already in this run;
 * - «DNS через VPN» does not answer → the remote DNS becomes an alternative one (not when the routing profile in effect
 *   has its own DNS: the global setting does not apply then);
 * - «Сайты через VPN» do not open → the routing profiles are updated, then the VPN's MTU is lowered to 1280.
 *
 * A running VPN takes each change up at once (a reload, waited for): a fix whose VPN does not connect again is no fix
 * and is put back. Updates of subscriptions and routing profiles change no setting: their content is the provider's
 * current one and stays; only the server selection they may have moved is put back.
 */
class DeviceRepairFixes(
    private val env: RepairEnvironment,
    private val config: SimpleConfig,
    /** A light check of one server: the delay, or null when it does not answer. */
    private val testServer: suspend (Long) -> Int?,
) {

    /** The reload owed since a value was put back while the service ran ([RepairReloads]); [settle] waits for it. */
    @Volatile
    private var owed: PendingReload.Owed? = null

    /** «Починка» stopped the VPN it had started: nothing may reload (a reload would start it again). */
    @Volatile
    private var vpnStopped = false

    /** The subscriptions were updated in this run already. */
    private var subscriptionsUpdated = false

    private val vpn = object : RepairVpn {
        override fun connected() = env.vpnConnected()
        override fun off() = env.serviceOff()
        override fun connects() = env.connects()
        override suspend fun awaitConnected(after: Int, timeoutMs: Long) = env.awaitConnected(after, timeoutMs)
        override fun sendReload() = SagerNet.reloadService()
    }

    private val reloader = VpnReloader(vpn, log = { Logs.i("simple repair: $it") }, reconnectMs = RECONNECT_MS)

    /** Reads the settings and the database: call it off the main thread. */
    fun fixesFor(step: RepairStep, result: StepResult): List<RepairFix> {
        if (result.status != StepStatus.FAIL && !(step == RepairStep.SERVERS && result.has(Finding.CURRENT_SERVER_DOWN))) {
            return emptyList()
        }
        return when (step) {
            RepairStep.DNS_DIRECT -> listOfNotNull(directDns().takeIf { result.has(Finding.DNS_NO_ANSWER) })
            RepairStep.SUBSCRIPTION -> listOfNotNull(subscriptionUpdate())
            RepairStep.SERVERS -> when {
                result.has(Finding.CURRENT_SERVER_DOWN) -> listOfNotNull(result.servers?.let(::switchServer))
                result.has(Finding.NO_SERVER_ANSWERS) -> listOfNotNull(subscriptionUpdate())
                else -> emptyList()
            }

            RepairStep.DNS_VPN -> listOfNotNull(remoteDns().takeIf { result.has(Finding.VPN_DNS_NO_ANSWER) })
            RepairStep.SITES_VPN -> if (result.has(Finding.VPN_SITES_DOWN) && env.vpnConnected()) {
                listOfNotNull(routesUpdate(), lowerMtu())
            } else {
                emptyList()
            }

            else -> emptyList()
        }
    }

    /**
     * The VPN started for the checks is being stopped: no reload from here on, and none owed (a reload reaching the
     * stopping service would start it again; its next start reads the stored values anyway).
     */
    fun vpnStopped() {
        vpnStopped = true
        RepairReloads.pending.disarm()
        owed = null
    }

    /**
     * A value was just put back: the running service owes a reload to take it up — armed here, before anything waits,
     * in the process-wide [RepairReloads], which sends it once the service is connected (never into a start) whatever
     * becomes of this run. A service that is off reads the value at its next start.
     */
    private fun owe() {
        owePending = true
    }

    /**
     * A value was put back inside the restore's write transaction; the reload is armed only after it committed
     * ([settle] runs after every restore, the stop's too), so the service never reloads with the old value.
     */
    @Volatile
    private var owePending = false

    private fun armOwed() {
        if (!owePending) return
        owePending = false
        if (vpnStopped || env.serviceOff()) return
        owed = RepairReloads.pending.arm { env.connects() }
    }

    /**
     * After values were put back ([owe]): with [wait], until the owed reload was sent and the service is connected
     * again — the run goes on and checks through it; a service still starting keeps the reload owed when the wait
     * ends. Without [wait] (the stop) nothing is waited for: the reload stays owed. Cancelling this never loses it.
     */
    suspend fun settle(wait: Boolean) {
        armOwed()
        val o = owed ?: return
        if (!wait) return
        val outcome = o.await(RECONNECT_MS) ?: return
        if (owed === o) owed = null
        if (outcome == ReloadOutcome.SENT && !env.awaitConnected(o.connectsAtSend, RECONNECT_MS)) {
            Logs.w("simple repair: the service did not reconnect after the owed reload")
        }
    }

    // ------------------------------------------------------------------------------------------------ the fixes

    /** The direct DNS → the DNS servers that work in whitelisted networks (`whitelistDns` of the configuration). */
    private fun directDns(): RepairFix? {
        val whitelisted = config.whitelistDns.joinToString(", ")
        val before = DataStore.directDns
        if (whitelisted.isBlank() || before.trim() == whitelisted) return null
        return setting(FixKind.DIRECT_DNS_WHITELISTED, "direct DNS", before, whitelisted, { DataStore.directDns }) { DataStore.directDns = it }
    }

    /**
     * The remote DNS → another well-known one. Not when the routing profile in effect brings its own DNS: the global
     * setting does not reach the VPN then.
     */
    private fun remoteDns(): RepairFix? {
        if (!env.vpnConnected()) return null
        if (RouteDnsSettings.current().second) {
            Logs.i("simple repair: the routing profile in effect has its own DNS; the remote DNS is left alone")
            return null
        }
        val before = DataStore.remoteDns
        val alternative = REMOTE_DNS_ALTERNATIVES.firstOrNull { it != before.trim() } ?: return null
        return setting(FixKind.VPN_DNS_ALTERNATIVE, "remote DNS", before, alternative, { DataStore.remoteDns }) { DataStore.remoteDns = it }
    }

    /** The VPN's MTU → 1280, when it is higher (VPN mode only: the proxy mode has no tunnel). */
    private fun lowerMtu(): RepairFix? {
        if (DataStore.serviceMode != Key.MODE_VPN) return null
        val before = DataStore.vpnMtu
        if (before <= SAFE_MTU) return null
        return setting(FixKind.MTU_LOWERED, "MTU", before, SAFE_MTU, { DataStore.vpnMtu }) { DataStore.vpnMtu = it }
    }

    /** The current server does not answer → [chosen], which did; checked again by a test of that server alone. */
    private fun switchServer(found: ServersSummary): RepairFix? {
        val chosen = found.chosenId
        val before = DataStore.selectedProxy
        if (chosen <= 0L || chosen == before) return null
        val fix = setting(FixKind.SWITCHED_SERVER, "server", before, chosen, { DataStore.selectedProxy }) { DataStore.selectedProxy = it }
        return object : RepairFix by fix {
            // The counts of the URL test stay; the newly selected server is the current one, and it answers.
            override suspend fun recheck(): StepResult {
                val delay = testServer(chosen)
                return if (delay != null) {
                    StepResult(StepStatus.OK, servers = found.copy(currentAnswers = true, chosenId = chosen))
                } else {
                    StepResult.fail(Finding.CURRENT_SERVER_DOWN)
                }
            }
        }
    }

    /** Updates every subscription now ([SubscriptionUpdateFix]), once per run. */
    private fun subscriptionUpdate(): RepairFix? {
        if (subscriptionsUpdated) return null
        val groups = GroupRepo.all().filter { it.isSubscription && !it.archive && it.url.isNotBlank() }.map { it.id }
        if (groups.isEmpty()) return null
        subscriptionsUpdated = true
        val wait = SubscriptionWait(
            // Returns once `:bg`'s queue holds the update (SubscriptionQueue.refreshGroup enqueues synchronously).
            // Bound to a bind of SUBMIT_MS. The claim and a stop/limit are one compare-and-set: a late bind after them
            // submits nothing; a submission claimed before them is part of the run (the update is not rolled back).
            submit = { gid ->
                RepairReloads.detached(SUBMIT_MS) { claim -> CoreServiceClient.call { if (claim()) it.refreshGroup(gid, false) } }
            },
            // A no-op queued behind them in the same FIFO queue: returns once every update before it has run.
            barrier = {
                RepairReloads.detached(BARRIER_MS) { CoreServiceClient.call { it.resolveProfiles(LongArray(0)) } }
                // The lists of this process learn about the writes of `:bg`.
                for (gid in groups) {
                    GroupRepo.postUpdate(gid)
                    GroupRepo.postReload(gid)
                }
            },
            profileExists = { id -> ProfileManager.getProfile(id) != null },
            runningNow = { DataStore.currentProfile },
            vpn = vpn,
            log = { Logs.i("simple repair: $it") },
        )
        return SubscriptionUpdateFix(
            groups = groups,
            wait = wait,
            vpn = vpn,
            reloader = reloader,
            running = { DataStore.currentProfile },
            reloadAllowed = { !vpnStopped },
            log = { Logs.i("simple repair: $it") },
        )
    }

    /** Updates the remote routing profiles now; the running VPN reloads with them. */
    private fun routesUpdate(): RepairFix? {
        val remote = RouteManager.all().any { it.is_remote && it.remote_url.isNotBlank() }
        if (!remote) return null
        return object : RepairFix {
            override val kind = FixKind.ROUTES_UPDATED

            // The rules are the provider's current ones and stay after a rollback: a stop does not undo them.
            override val reversible: Boolean get() = false

            override suspend fun apply() {
                val (updated, failures) = withContext(Dispatchers.IO) { RemoteRouteUpdater.updateAll(onlyAutoUpdate = false) }
                Logs.i("simple repair: fix routes_updated: $updated updated, ${failures.size} failed")
                if (updated == 0) throw IllegalStateException("no routing profile was updated")
                if (!takeUp("routing profiles updated")) throw IllegalStateException("the VPN did not reconnect")
            }

            override fun restore() = Unit
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * A setting changed from [before] to [after] through [write]; a running VPN reloads to take it up, and the fix
     * fails when it does not connect again. Its rollback is compare and set: [before] goes back only while [read]
     * still gives [after] (a later run or the user may have set it since).
     */
    private fun <T> setting(
        fixKind: FixKind,
        name: String,
        before: T,
        after: T,
        read: () -> T,
        write: (T) -> Unit,
    ): RepairFix = object : RepairFix {
        override val kind = fixKind
        override val change = FixChange(name, before.toString(), after.toString())

        override suspend fun apply() {
            Logs.i("simple repair: fix ${kind.name.lowercase()}: $name ${describe(before)} -> ${describe(after)}")
            withContext(Dispatchers.IO) { write(after) }
            if (!takeUp("$name changed")) throw IllegalStateException("the VPN did not reconnect")
        }

        override fun stillApplied(): Boolean = read() == after

        override fun restore() {
            Logs.i("simple repair: rollback ${kind.name.lowercase()}: $name back to ${describe(before)}")
            write(before)
            owe()
        }
    }

    /** Values for the log: DNS servers and MTU as they are, servers by id (never their address). */
    private fun describe(value: Any?): String = when (value) {
        is String -> "'$value'"
        else -> value.toString()
    }

    /**
     * Lets the service take a change up and waits until it is connected again: a connected one reloads at once; one
     * still starting or reloading is reloaded once it is connected (a reload reaching a start in flight would stop
     * it); one that is off reads the change at its next start, and one «Починка» stopped is not touched. False when
     * it does not connect (again).
     */
    private suspend fun takeUp(why: String): Boolean = when {
        vpnStopped || env.serviceOff() -> true
        env.vpnConnected() -> reloader.reload(why)
        else -> reloader.reloadWhenConnected(why)
    }

    private companion object {
        /** connection-repair: «снижение MTU до 1280». */
        const val SAFE_MTU = 1280

        /** Well-known DNS-over-HTTPS servers by address (no lookup needed); the first one differing is tried. */
        val REMOTE_DNS_ALTERNATIVES = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

        const val RECONNECT_MS = 60_000L

        /** Binding to `:bg` and handing it one subscription update. */
        const val SUBMIT_MS = 15_000L

        /** The queue barrier; the subscription wait bounds the whole of it again (SubscriptionWait.limitMs). */
        const val BARRIER_MS = 90_000L
    }
}
