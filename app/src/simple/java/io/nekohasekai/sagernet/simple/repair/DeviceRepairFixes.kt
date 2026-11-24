package io.nekohasekai.sagernet.simple.repair

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteDnsSettings
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.group.SubscriptionClient
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        log = { Logs.i("simple repair: $it") },
    )
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

    /** A value was put back while the VPN ran: [settle] lets it take them up, once for all. */
    @Volatile
    private var reloadPending = false

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
                result.has(Finding.CURRENT_SERVER_DOWN) -> listOfNotNull(switchServer(result.servers?.chosenId ?: 0L))
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

    /** The VPN started for the checks was stopped again: no reload from here on. */
    fun vpnStopped() {
        vpnStopped = true
    }

    /**
     * After values were put back: a running VPN reloads once to take them up; [wait] until it is connected again. A
     * service that is off reads them at its next start. One still starting or reloading may have read the replaced
     * values already: it reloads once it is connected (a reload that reaches a start in flight would stop it) — here
     * when [wait] and it connects in time, otherwise through the process-wide [RepairReloads] that outlives the run.
     */
    suspend fun settle(wait: Boolean) {
        if (!reloadPending) return
        reloadPending = false
        if (vpnStopped || env.serviceOff()) return
        val connects = env.connects()
        if (!env.vpnConnected() && !(wait && env.awaitConnected(connects, RECONNECT_MS))) {
            if (!vpnStopped && !env.serviceOff()) RepairReloads.pending.arm()
            return
        }
        if (vpnStopped) return
        if (wait) {
            reloader.reload("settings put back")
        } else {
            Logs.i("simple repair: settings put back, reload of the running service")
            SagerNet.reloadService()
        }
    }

    // ------------------------------------------------------------------------------------------------ the fixes

    /** The direct DNS → the DNS servers that work in whitelisted networks (`whitelistDns` of the configuration). */
    private fun directDns(): RepairFix? {
        val whitelisted = config.whitelistDns.joinToString(", ")
        val before = DataStore.directDns
        if (whitelisted.isBlank() || before.trim() == whitelisted) return null
        return setting(FixKind.DIRECT_DNS_WHITELISTED, "direct DNS", before, whitelisted) { DataStore.directDns = it }
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
        return setting(FixKind.VPN_DNS_ALTERNATIVE, "remote DNS", before, alternative) { DataStore.remoteDns = it }
    }

    /** The VPN's MTU → 1280, when it is higher (VPN mode only: the proxy mode has no tunnel). */
    private fun lowerMtu(): RepairFix? {
        if (DataStore.serviceMode != Key.MODE_VPN) return null
        val before = DataStore.vpnMtu
        if (before <= SAFE_MTU) return null
        return setting(FixKind.MTU_LOWERED, "MTU", before, SAFE_MTU) { DataStore.vpnMtu = it }
    }

    /** The current server does not answer → [chosen], which did; checked again by a test of that server alone. */
    private fun switchServer(chosen: Long): RepairFix? {
        val before = DataStore.selectedProxy
        if (chosen <= 0L || chosen == before) return null
        val fix = setting(FixKind.SWITCHED_SERVER, "server", before, chosen) { DataStore.selectedProxy = it }
        return object : RepairFix by fix {
            override suspend fun recheck(): StepResult {
                val delay = testServer(chosen)
                return if (delay != null) StepResult.ok() else StepResult.fail(Finding.CURRENT_SERVER_DOWN)
            }
        }
    }

    /** Updates every subscription now ([SubscriptionUpdateFix]), once per run. */
    private fun subscriptionUpdate(): RepairFix? {
        if (subscriptionsUpdated) return null
        val groups = GroupRepo.all().filter { it.isSubscription && !it.archive && it.url.isNotBlank() }.map { it.id }
        if (groups.isEmpty()) return null
        subscriptionsUpdated = true
        val selected = DataStore.selectedProxy
        val wait = SubscriptionWait(
            states = SubscriptionClient.states,
            request = { gid -> SubscriptionClient.refreshGroup(gid, false) },
            lastUpdate = { gid -> GroupRepo.get(gid)?.subLastUpdate ?: 0L },
            profileExists = { id -> ProfileManager.getProfile(id) != null },
            vpn = vpn,
        )
        return SubscriptionUpdateFix(
            groups = groups,
            wait = wait,
            vpn = vpn,
            reloader = reloader,
            running = { DataStore.currentProfile },
            reloadAllowed = { !vpnStopped },
            restoreSelection = {
                // A selection the update moved goes back while its profile exists (a deleted one is replaced).
                if (DataStore.selectedProxy != selected && ProfileManager.getProfile(selected) != null) {
                    DataStore.selectedProxy = selected
                    reloadPending = true
                }
            },
            log = { Logs.i("simple repair: $it") },
        )
    }

    /** Updates the remote routing profiles now; the running VPN reloads with them. */
    private fun routesUpdate(): RepairFix? {
        val remote = RouteManager.all().any { it.is_remote && it.remote_url.isNotBlank() }
        if (!remote) return null
        return object : RepairFix {
            override val kind = FixKind.ROUTES_UPDATED

            override suspend fun apply() {
                val (updated, failures) = withContext(Dispatchers.IO) { RemoteRouteUpdater.updateAll(onlyAutoUpdate = false) }
                Logs.i("simple repair: fix routes_updated: $updated updated, ${failures.size} failed")
                if (updated == 0) throw IllegalStateException("no routing profile was updated")
                if (!reload("routing profiles updated")) throw IllegalStateException("the VPN did not reconnect")
            }

            // The rules are the provider's current ones; no setting was changed.
            override fun restore() = Unit
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /**
     * A setting changed from [before] to [after] through [write]; a running VPN reloads to take it up, and the fix
     * fails when it does not connect again.
     */
    private fun <T> setting(fixKind: FixKind, name: String, before: T, after: T, write: (T) -> Unit): RepairFix =
        object : RepairFix {
            override val kind = fixKind
            override val change = FixChange(name, before.toString(), after.toString())

            override suspend fun apply() {
                Logs.i("simple repair: fix ${kind.name.lowercase()}: $name ${describe(before)} -> ${describe(after)}")
                withContext(Dispatchers.IO) { write(after) }
                if (env.vpnConnected() && !reload("$name changed")) throw IllegalStateException("the VPN did not reconnect")
            }

            override fun restore() {
                Logs.i("simple repair: rollback ${kind.name.lowercase()}: $name back to ${describe(before)}")
                write(before)
                reloadPending = true
            }
        }

    /** Values for the log: DNS servers and MTU as they are, servers by id (never their address). */
    private fun describe(value: Any?): String = when (value) {
        is String -> "'$value'"
        else -> value.toString()
    }

    /** Reloads the running VPN and waits until it is connected again; true without a reload once it was stopped. */
    private suspend fun reload(why: String): Boolean = vpnStopped || reloader.reload(why)

    private companion object {
        /** connection-repair: «снижение MTU до 1280». */
        const val SAFE_MTU = 1280

        /** Well-known DNS-over-HTTPS servers by address (no lookup needed); the first one differing is tried. */
        val REMOTE_DNS_ALTERNATIVES = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query")

        const val RECONNECT_MS = 60_000L
    }
}
