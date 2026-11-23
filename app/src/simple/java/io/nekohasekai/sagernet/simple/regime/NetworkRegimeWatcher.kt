package io.nekohasekai.sagernet.simple.regime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.ServiceChange
import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.simple.modes.TrafficModeApplier
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds out whether the physical network has whitelists while the VPN is on (design.md D6, traffic-modes "Моменты
 * проверки белых списков"). Lives in `:bg`: SimpleHooks starts it with the service and stops it with the service, so
 * with the VPN off nothing is checked. A check runs when the VPN connects, when the physical network changes, when the
 * user chooses «Авто» ([requestCheck]) and every 15 minutes in a mobile network.
 *
 * The physical network comes from DefaultNetworkListener under a key of its own (the source BaseService fills
 * `SagerNet.underlyingNetwork` from). Checks run one at a time through [ProbeGate]: a new one cancels the one in
 * flight, and a result is stored (`simple_regime`) and applied only when it is the latest check, its network is still
 * the physical network and the user did not change the mode meanwhile (TrafficModeApplier.applyRegime decides that in
 * one database transaction with the mode's own writes). No notification is shown; the home screen's mode card tells.
 */
object NetworkRegimeWatcher {

    /** The simple UI asks for a check (the user chose «Авто»); only a running watcher hears it. */
    private const val ACTION_CHECK = "io.nekohasekai.sagernet.simple.CHECK_REGIME"

    /** The check in a mobile network repeats this often. */
    private const val MOBILE_INTERVAL_MS = 15 * 60_000L

    /** The wait before the second look at an inconclusive or partial result. */
    private const val RECHECK_DELAY_MS = 3_000L

    /** How long a routing changed while the service connects waits for it to be connected. */
    private const val CONNECT_WAIT_MS = 60_000L
    private const val POLL_MS = 500L

    /** How long the stop of the service waits for «Авто» to go back to the smart rules. */
    private const val REVERT_WAIT_MS = 2_000L

    private val gate = ProbeGate<Long>()
    private val lock = Any()

    /** One per run of the service; null while the VPN is off. */
    @Volatile
    private var session: Session? = null

    private class Session(val scope: CoroutineScope) {
        /** The listener's key: a stop of an earlier run never removes the listener of this one. */
        val key = Any()
        var listener: Job? = null
        var receiver: BroadcastReceiver? = null

        /** The physical network as last reported, null while there is none. */
        @Volatile
        var network: Network? = null
        var check: Job? = null

        /** Whether a physical network was reported in this run yet: the first one is the check of the connection. */
        var sawNetwork = false

        /** A result changed the routing while the service was not connected: waits for Connected, then reloads. */
        var pendingReload: Job? = null
    }

    /** SimpleHooks: a core is about to report Connected; the first one of a connection starts the watcher. */
    fun serviceStarted() = synchronized(lock) {
        if (session != null) return@synchronized
        val started = Session(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        session = started
        Logs.i("simple regime: watcher started with the VPN")
        started.receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onCheckRequested(started)
        }.also { register(it) }
        // Not in the session's scope: a stop must still find out whether the start reached the listener.
        started.listener = GlobalScope.launch(Dispatchers.Default) {
            DefaultNetworkListener.start(started.key) { onNetwork(started, it) }
        }
        started.scope.launch {
            while (isActive) {
                delay(MOBILE_INTERVAL_MS)
                onTimer(started)
            }
        }
    }

    /** SimpleHooks: the service stopped for good (not a restart in place); no more checks until the next start. */
    fun serviceStopped() {
        if (stopWatching()) routeSmartWhileOff()
    }

    /** Ends the running session; false when none ran. */
    private fun stopWatching(): Boolean = synchronized(lock) {
        val stopped = session ?: return@synchronized false
        session = null
        gate.invalidate()
        if (stopped.check?.isActive == true) Logs.i("simple regime: the VPN stopped, the check in flight is cancelled")
        stopped.scope.cancel()
        stopped.receiver?.let { runCatching { app.unregisterReceiver(it) }.onFailure { Logs.w(it) } }
        val listener = stopped.listener
        GlobalScope.launch(Dispatchers.Default) {
            listener?.join()
            DefaultNetworkListener.stop(stopped.key)
        }
        Logs.i("simple regime: watcher stopped with the VPN, no checks until it connects again")
        true
    }

    /**
     * With the VPN off the device may change networks unnoticed, and the next connection must not start with the
     * whitelist rules of a network it is no longer in (traffic-modes "Моменты проверки белых списков": until the
     * current network has a result, «Авто» routes like «Умный»). So «Авто» goes back to the smart rules now; the stored
     * result is kept, and the check on the next connection applies it again if it is still that network. A watcher
     * started meanwhile (a quick reconnect) wins: its results are newer.
     *
     * It runs before the service reports Stopped (SimpleHooks), so the screens that read the state on Stopped already
     * find the smart rules; a slow database gets [REVERT_WAIT_MS], after which it finishes in the background.
     */
    private fun routeSmartWhileOff() {
        val revert = GlobalScope.async(Dispatchers.IO) {
            try {
                val stored = RegimeStore.read() ?: return@async
                if (stored.regime != Regime.WHITELIST) return@async
                val (verdict, change) = TrafficModeApplier.applyRegime(app, stored, whitelist = false) { _, _ ->
                    if (session == null) ProbeGate.Verdict.ACCEPTED else ProbeGate.Verdict.SUPERSEDED
                }
                if (verdict == ProbeGate.Verdict.ACCEPTED && change != ServiceChange.NONE) {
                    Logs.i("simple regime: VPN off, «Авто» routes like «Умный» until the next connection checks its network")
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        runBlocking { withTimeoutOrNull(REVERT_WAIT_MS) { revert.await() } }
    }

    /**
     * The user chose «Авто» in the simple UI (UI process): a running watcher checks the current network. With the VPN
     * off nobody receives it, and the check runs when the VPN connects.
     */
    fun requestCheck(context: Context) {
        context.sendBroadcast(Intent(ACTION_CHECK).setPackage(context.packageName))
    }

    // ------------------------------------------------------------------------------------------------ events

    /** DefaultNetworkListener's thread: a new physical network, the same one again (capabilities), or none. */
    private fun onNetwork(owner: Session, network: Network?) = synchronized(lock) {
        if (session !== owner) return@synchronized
        val previous = owner.network
        if (network == previous) return@synchronized
        owner.network = network
        if (network == null) {
            gate.invalidate()
            owner.check?.cancel()
            owner.check = null
            Logs.i("simple regime: no physical network, checks wait for one")
            return@synchronized
        }
        val reason = if (owner.sawNetwork) "network changed" else "VPN connected"
        owner.sawNetwork = true
        start(owner, network, reason)
    }

    private fun onCheckRequested(owner: Session) = synchronized(lock) {
        if (session !== owner) return@synchronized
        val network = owner.network ?: return@synchronized Logs.i("simple regime: «Авто» chosen, no physical network to check")
        start(owner, network, "«Авто» chosen")
    }

    private fun onTimer(owner: Session) = synchronized(lock) {
        if (session !== owner) return@synchronized
        val network = owner.network ?: return@synchronized
        val cellular = SagerNet.connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        if (cellular) start(owner, network, "15-minute timer, mobile network")
    }

    // ------------------------------------------------------------------------------------------------ checks

    /** Under [lock]: cancels the check in flight and starts the next one on [network]. */
    private fun start(owner: Session, network: Network, reason: String) {
        val revision = try {
            TrafficModes.modeRevision()
        } catch (e: Exception) {
            Logs.w(e)
            -1L
        }
        val ticket = gate.begin(network.networkHandle, revision)
        owner.check?.cancel()
        owner.check = owner.scope.launch { run(owner, network, ticket, reason) }
    }

    private suspend fun run(
        owner: Session,
        network: Network,
        ticket: ProbeGate.Ticket<Long>,
        reason: String,
    ) {
        val tag = "check #${ticket.generation} on network ${ticket.network}"
        try {
            // Every check, not only the one of a network change: a check asked for (or timed) right after a change can
            // replace the change's own check before it ran, and the old network's result must not outlive it. A result
            // of this very network is kept.
            forgetOtherNetwork(owner, ticket)
            val mode = if (TrafficModes.isModeStored()) TrafficModes.mode() else null
            if (mode != TrafficMode.AUTO) {
                Logs.i("simple regime: $tag ($reason) skipped: the mode is «${mode?.key ?: "not chosen"}», not «Авто»")
                return
            }
            Logs.i("simple regime: $tag ($reason) started")
            var result = NetworkProber.probe(network, SimpleConfigRepo.current())
            Logs.i("simple regime: $tag: ${result.summary()} in ${result.elapsedMs} ms -> ${result.regime.key}")
            // A network that went away during the check leaves partial answers, which can read as whitelists (some
            // allowed addresses answered before the drop, nothing after). Such a result, and whitelists with an
            // allowed address missing, get a second look a moment later; a network change meanwhile cancels it.
            if (result.inconclusive || result.partial) {
                Logs.i("simple regime: $tag: ${if (result.inconclusive) "inconclusive" else "partial"}, checking again in ${RECHECK_DELAY_MS} ms")
                delay(RECHECK_DELAY_MS)
                result = NetworkProber.probe(network, SimpleConfigRepo.current())
                Logs.i("simple regime: $tag (second look): ${result.summary()} in ${result.elapsedMs} ms -> ${result.regime.key}")
                if (result.inconclusive) {
                    Logs.i("simple regime: $tag: the network went away during the check, the traffic mode stays as it is")
                    return
                }
            }
            val regime = result.regime
            if (regime == Regime.NO_INTERNET) {
                Logs.i("simple regime: $tag: no internet, the traffic mode stays as it is")
                return
            }
            val (verdict, change) = TrafficModeApplier.applyRegime(app, RegimeRecord(regime, ticket.network)) { m, rev ->
                gate.verdict(ticket, currentNetwork(owner), m, rev)
            }
            when (verdict) {
                ProbeGate.Verdict.ACCEPTED ->
                    Logs.i("simple regime: $tag: ${regime.key} applied (service change: ${change.name.lowercase()})")

                ProbeGate.Verdict.SUPERSEDED ->
                    Logs.i("simple regime: $tag: result ${regime.key} discarded, a newer check replaced it")

                ProbeGate.Verdict.OTHER_NETWORK ->
                    Logs.i("simple regime: $tag: result ${regime.key} discarded, the physical network changed")

                ProbeGate.Verdict.MODE_CHANGED ->
                    Logs.i("simple regime: $tag: result ${regime.key} discarded, the mode was changed by the user")
            }
            oweReload(owner, change)
        } catch (e: CancellationException) {
            Logs.i("simple regime: $tag cancelled, its result is discarded")
            throw e
        } catch (e: Exception) {
            Logs.w("simple regime: $tag failed", e)
        }
    }

    /**
     * A new physical network: a result of another network no longer counts (traffic-modes "Моменты проверки белых
     * списков"), so «Авто» routes like «Умный» until this network's check has a result.
     */
    private suspend fun forgetOtherNetwork(owner: Session, ticket: ProbeGate.Ticket<Long>) {
        val stored = RegimeStore.read()
        if (stored?.network == ticket.network) return
        val (verdict, change) = TrafficModeApplier.applyRegime(app, RegimeRecord(null, ticket.network)) { _, _ ->
            if (gate.isLatest(ticket)) ProbeGate.Verdict.ACCEPTED else ProbeGate.Verdict.SUPERSEDED
        }
        if (verdict == ProbeGate.Verdict.ACCEPTED && stored != null) {
            Logs.i(
                "simple regime: physical network ${ticket.network}, the result of network ${stored?.network ?: "-"} " +
                    "(${stored?.regime?.key ?: "none"}) no longer applies (service change: ${change.name.lowercase()})"
            )
        }
        oweReload(owner, change)
    }

    /**
     * Without [lock]: TrafficModeApplier asks this inside its database transaction, while an event holding [lock] may
     * be reading the database.
     */
    private fun currentNetwork(owner: Session): Long? =
        if (session === owner) owner.network?.networkHandle else null

    /**
     * Puts a changed routing into effect: a connected service reloads at once. One that is connecting or restarting
     * is not reloaded then (a reload while it connects can leave it without its VPN interface and stop it); it is
     * awaited until Connected and reloaded only if the core it started does not run the routing and server now stored.
     */
    private fun oweReload(owner: Session, change: ServiceChange) {
        if (change == ServiceChange.NONE) return
        synchronized(lock) {
            if (session !== owner) return
            if (DataStore.serviceState == BaseService.State.Connected && owner.pendingReload?.isActive != true) {
                SagerNet.reloadService()
                return
            }
            if (owner.pendingReload?.isActive == true) return
            Logs.i("simple regime: the service is not connected, the new routing waits for it")
            owner.pendingReload = owner.scope.launch {
                var waited = 0L
                while (DataStore.serviceState != BaseService.State.Connected) {
                    if (waited >= CONNECT_WAIT_MS) return@launch Logs.i("simple regime: the service did not connect, no reload")
                    delay(POLL_MS)
                    waited += POLL_MS
                }
                if (DataStore.runningRouteId != DataStore.currentRouteId || DataStore.currentProfile != DataStore.selectedProxy) {
                    Logs.i("simple regime: the connected core runs another routing than the network's, reload")
                    SagerNet.reloadService()
                }
            }
        }
    }

    private fun register(receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_CHECK)
        val permission = "${app.packageName}.SERVICE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter, permission, null)
        }
    }
}
