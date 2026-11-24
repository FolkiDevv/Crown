package io.nekohasekai.sagernet.simple.repair

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlanner
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.simple.SimpleHooks
import io.nekohasekai.sagernet.simple.config.SimpleConfig
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.simple.regime.DnsMessage
import io.nekohasekai.sagernet.simple.regime.NetworkProber
import io.nekohasekai.sagernet.simple.regime.NetworkRegimeWatcher
import io.nekohasekai.sagernet.simple.regime.Regime
import io.nekohasekai.sagernet.simple.servers.ServerCatalog
import io.nekohasekai.sagernet.simple.servers.SubscriptionHealth
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.utils.BatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * What «Починка» needs from the rest of the app: the state of this app's VPN, a URL test of the running connection
 * (the home screen's service connection), and starting and stopping the VPN with the user's consent.
 */
class RepairEnvironment(
    /** This app's VPN is connected. */
    val vpnConnected: () -> Boolean,
    /** This app's service is off (not connecting, connected or stopping): another VPN can only be someone else's. */
    val serviceOff: () -> Boolean,
    /** The delay through the running connection within the time given; > 0 ok, < 0 failed, null: no answer. */
    val testConnection: suspend (timeoutMs: Long) -> Int?,
    /** Shows the system's VPN consent request; true when the user gave it. */
    val requestConsent: suspend () -> Boolean,
    /** Starts the service on the selected server (the consent is there). */
    val startVpn: () -> Unit,
    /** Stops the service as the user's own stop does (no «Соединение потеряно»). */
    val stopVpn: () -> Unit,
    /** How many times the service has connected so far; with [awaitConnected] a start or reload is waited for. */
    val connects: () -> Int,
    /** Waits until the service has connected more than `after` times and is connected; false after `timeoutMs`. */
    val awaitConnected: suspend (after: Int, timeoutMs: Long) -> Boolean,
)

/**
 * The checks of «Починка» on the device (design.md D8). The checks without the VPN go through the physical network
 * (`SagerNet.underlyingNetwork`, the same source as the network watcher of D6) with [NetworkProber]; the servers with a
 * URL test session of `:bg` ([TestSessionClient]). For the checks through the VPN a VPN that is off is started on the
 * server «Доступность серверов» chose (connection-repair "Подключение для проверок через VPN"), after the system's
 * consent request when there is no consent yet, and never while another app's VPN is active; it stays on when «Сайты
 * через VPN» passes and is stopped again otherwise ([journal]). The automatic fixes are [DeviceRepairFixes]; Android's
 * own settings are never changed. Requests carry no results of the checks and no identifiers (connection-repair "Без
 * автоматической отправки").
 */
class DeviceRepairChecks(
    private val context: Context,
    private val env: RepairEnvironment,
    private val journal: RepairJournal,
) : RepairChecks {

    private val config: SimpleConfig = SimpleConfigRepo.current()

    private val fixes = DeviceRepairFixes(env, config) { id -> urlTest(listOf(id))?.get(id) }

    /** Why the VPN could not be had for the checks through it; both checks share the answer (one consent request). */
    private var vpnProblem: SkipReason? = null

    override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>): List<RepairFix> =
        withContext(Dispatchers.IO) { fixes.fixesFor(step, result) }

    override suspend fun settle(wait: Boolean) = fixes.settle(wait)

    /** The physical network the checks went through, from «Интернет без VPN» on. */
    private var network: Network? = null

    /** The answers of «Интернет без VPN», read again by «Белые списки» and «Российские сайты напрямую». */
    private var probe: NetworkProber.Result? = null

    /** Off the main thread: the checks read the database and wait for lookups. */
    override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult = withContext(Dispatchers.IO) {
        val result = when (step) {
            RepairStep.INTERNET -> internet()
            RepairStep.WHITELISTS -> whitelists()
            RepairStep.CLOCK -> clock()
            RepairStep.PRIVATE_DNS_VPN -> privateDnsAndVpn()
            RepairStep.DNS_DIRECT -> secondLook(step, tries = 2) { directDns() }
            RepairStep.SUBSCRIPTION -> subscription()
            RepairStep.SERVERS -> servers(done)
            RepairStep.DNS_VPN -> ensureVpn(step, done)?.let { StepResult.skipped(it) } ?: secondLook(step, tries = 2) { vpnDns() }
            // An auto-selector that has just started may test through a member it is about to leave: a few looks.
            RepairStep.SITES_VPN -> ensureVpn(step, done)?.let { StepResult.skipped(it) } ?: secondLook(step, tries = 3) { vpnSites() }
            RepairStep.RU_DIRECT -> russianSites()
            RepairStep.BACKGROUND -> background()
        }
        Logs.i("simple repair: ${step.name.lowercase()} -> ${describe(result)}")
        result
    }

    /**
     * A failure of a check that a single miss can fake (a DNS answer lost, a VPN that has just started or reloaded) is
     * looked at again a moment later before it counts, so no setting is changed over a fluke: up to [tries] looks. Only
     * a quick failure is looked at again; one that waited out its whole time limit had no answer to miss.
     */
    private suspend fun secondLook(step: RepairStep, tries: Int, check: suspend () -> StepResult): StepResult {
        var started = System.currentTimeMillis()
        var result = check()
        var look = 1
        while (result.status == StepStatus.FAIL && look < tries &&
            System.currentTimeMillis() - started < SECOND_LOOK_QUICK_MS
        ) {
            Logs.i("simple repair: ${step.name.lowercase()} failed (${describe(result)}), looking again")
            delay(SECOND_LOOK_MS)
            started = System.currentTimeMillis()
            result = check()
            look++
        }
        return result
    }

    private fun describe(result: StepResult): String = buildString {
        append(result.status.name.lowercase())
        result.skip?.let { append(" (").append(it.name.lowercase()).append(')') }
        if (result.findings.isNotEmpty()) append(' ').append(result.findings.joinToString { it.name.lowercase() })
        if (result.values.isNotEmpty()) append(' ').append(result.values)
        result.servers?.let {
            append(" servers ${it.answering}/${it.total}, whitelist ${it.whitelistAnswering}/${it.whitelistTotal}")
            append(", current answers ${it.currentAnswers}")
        }
    }

    // ------------------------------------------------------------------------------------------------ physical network

    /** The physical network now: the watcher's source, else the active network when it is not a VPN. */
    private fun physicalNetwork(): Network? {
        SagerNet.underlyingNetwork?.let { return it }
        val active = SagerNet.connectivity.activeNetwork ?: return null
        val caps = SagerNet.connectivity.getNetworkCapabilities(active) ?: return null
        return active.takeIf { !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }

    private suspend fun internet(): StepResult {
        val physical = physicalNetwork() ?: return StepResult.fail(Finding.NO_NETWORK)
        network = physical
        var result = NetworkProber.probe(physical, config)
        Logs.i("simple repair: internet: ${result.summary()} in ${result.elapsedMs} ms -> ${result.regime.key}")
        // The network went away during the check, or whitelists with an allowed address missing: a second look, as
        // the watcher does (design.md D6).
        if (result.inconclusive || result.partial) {
            delay(RECHECK_DELAY_MS)
            // The network the second look runs on is the one the later checks (time, DNS) use too.
            val again = physicalNetwork() ?: physical
            network = again
            result = NetworkProber.probe(again, config)
            Logs.i("simple repair: internet (second look): ${result.summary()} -> ${result.regime.key}")
        }
        // Another app's VPN that takes this app in forbids requests over the physical network: nothing answering then
        // says nothing about the internet (the check «Частный DNS и другие VPN» names the cause).
        if ((result.regime == Regime.NO_INTERNET || result.inconclusive) && otherVpnActive()) {
            return StepResult.skipped(SkipReason.OTHER_VPN, note = result.summary())
        }
        probe = result
        val reached = (result.allowed + result.blocked).count { it.reachable }.toLong()
        val values = mapOf(RepairValues.REACHED to reached, RepairValues.ASKED to (result.allowed.size + result.blocked.size).toLong())
        return if (result.regime == Regime.NO_INTERNET) {
            StepResult.fail(Finding.NOTHING_ANSWERS, values = values)
        } else {
            StepResult.ok(values = values)
        }
    }

    private suspend fun whitelists(): StepResult {
        val result = probe ?: return StepResult.skipped(SkipReason.ERROR)
        // «Авто» on and the VPN running: the watcher in :bg checks this network now too, so a check of its own in flight
        // (the 15-minute one) gives way and the mode follows what is found (traffic-modes "Две проверки в одной сети").
        withContext(Dispatchers.IO) {
            try {
                if (env.vpnConnected() && TrafficModes.isModeStored() && TrafficModes.mode() == TrafficMode.AUTO) {
                    NetworkRegimeWatcher.requestCheck(context)
                }
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
        return if (result.regime == Regime.WHITELIST) {
            StepResult.ok(Finding.WHITELISTS_FOUND)
        } else {
            StepResult.ok(Finding.NO_WHITELISTS)
        }
    }

    /**
     * The clock against the `Date` header of a trusted host (design.md D8). A clock off by far more breaks TLS itself
     * (and so «Интернет без VPN», whose requests are HTTPS); then the same host is asked over plain HTTP, which still
     * tells the time. So the check needs a physical network, not a passed internet check.
     */
    private suspend fun clock(): StepResult {
        val physical = network ?: physicalNetwork() ?: return StepResult.skipped(SkipReason.NO_INTERNET)
        val client = NetworkProber.client(physical, config)
        val http = client.http
        try {
            for (host in config.timeHosts) {
                for (scheme in arrayOf("https", "http")) {
                    val sent = System.currentTimeMillis()
                    val date = http.await(NetworkProber.head("$scheme://$host/")) { it.headers.getDate("Date")?.time }
                    val received = System.currentTimeMillis()
                    if (date == null) continue
                    // The header has whole seconds; the device time is taken halfway through the request.
                    val offsetMs = (sent + received) / 2 - date
                    val values = mapOf(RepairValues.CLOCK_OFFSET_S to offsetMs / 1000)
                    return if (abs(offsetMs) > CLOCK_TOLERANCE_MS) {
                        StepResult.fail(Finding.CLOCK_OFF, values = values)
                    } else {
                        StepResult.ok(values = values)
                    }
                }
            }
        } finally {
            client.close()
        }
        return StepResult.skipped(SkipReason.NO_TIME_SOURCE)
    }

    /**
     * A private DNS set by hand (a host name in Android's settings: `privateDnsServerName`, API 28+) and another app's
     * VPN: a VPN network among the networks while this app's service is off. The missing VPN consent of this app
     * (`VpnService.prepare() != null`, e.g. right after installing) is no sign of another VPN and is not looked at.
     */
    private fun privateDnsAndVpn(): StepResult {
        val connectivity = SagerNet.connectivity
        val findings = mutableSetOf<Finding>()
        if (Build.VERSION.SDK_INT >= 28) {
            val physical = network ?: physicalNetwork()
            val hostname = physical?.let { connectivity.getLinkProperties(it)?.privateDnsServerName }
            val mode = try {
                Settings.Global.getString(context.contentResolver, PRIVATE_DNS_MODE)
            } catch (e: Exception) {
                null
            }
            if (hostname != null || mode == PRIVATE_DNS_MODE_HOSTNAME) findings += Finding.PRIVATE_DNS
        }
        if (otherVpnActive()) findings += Finding.OTHER_VPN
        return if (findings.isEmpty()) StepResult.ok() else StepResult(StepStatus.FAIL, findings)
    }

    /** A VPN network among the networks while this app's service is off: another app's VPN. */
    private fun otherVpnActive(): Boolean {
        if (!env.serviceOff()) return false
        val connectivity = SagerNet.connectivity
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.any {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    /** The direct DNS server of the app (`direct_dns`) for a trusted name, through the physical network. */
    private suspend fun directDns(): StepResult {
        val physical = network ?: return StepResult.skipped(SkipReason.NO_INTERNET)
        val entry = DataStore.directDns.split('\n', ',', ';').map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "local"
        val name = config.timeHosts.firstOrNull() ?: return StepResult.skipped(SkipReason.ERROR)
        val plain = DnsMessage.plainServer(entry)
        val found: List<InetAddress>? = when {
            entry.startsWith("local", ignoreCase = true) -> withContext(Dispatchers.IO) {
                NetworkProber.lookupSystem(physical, name, DNS_WAIT_MS)
            }

            plain != null -> withContext(Dispatchers.IO) {
                try {
                    NetworkProber.askPlain(physical, plain, name, DnsMessage.TYPE_A, DNS_WAIT_MS.toInt())
                } catch (e: IOException) {
                    null
                }
            }

            entry.startsWith("https://", ignoreCase = true) -> doh(physical, entry, name)
            else -> return StepResult.skipped(SkipReason.UNSUPPORTED_DNS)
        }
        return if (found.isNullOrEmpty()) StepResult.fail(Finding.DNS_NO_ANSWER) else StepResult.ok()
    }

    /** One DNS-over-HTTPS question (RFC 8484, POST) to [url] through [physical]. */
    private suspend fun doh(physical: Network, url: String, name: String): List<InetAddress>? {
        val client = NetworkProber.client(physical, config)
        val http = client.http
        try {
            val query = DnsMessage.query(0, name, DnsMessage.TYPE_A)
            val request = Request.Builder()
                .url(url)
                .header("Accept", DNS_MESSAGE)
                .post(query.toRequestBody(DNS_MESSAGE.toMediaType()))
                .build()
            return http.await(request) { response ->
                if (!response.isSuccessful) return@await null
                val body = response.body?.bytes() ?: return@await null
                DnsMessage.addresses(body, body.size, 0).map { InetAddress.getByAddress(name, it) }
            }
        } catch (e: IllegalArgumentException) {
            return null
        } finally {
            client.close()
        }
    }

    /** The subscriptions as their providers last described them: ended, used up, empty, ending soon. */
    private fun subscription(): StepResult {
        val groups = GroupRepo.all().filter { it.isSubscription && !it.archive }
        if (groups.isEmpty()) return StepResult.skipped(SkipReason.NO_SUBSCRIPTION)
        val now = System.currentTimeMillis() / 1000
        val findings = mutableSetOf<Finding>()
        var daysLeft: Int? = null
        for (group in groups) {
            val info = group.subInfo
            val expire = if (info.valid) info.expire else 0L
            when (SubscriptionHealth.expiry(expire, now)) {
                SubscriptionHealth.Expiry.EXPIRED -> findings += Finding.SUBSCRIPTION_EXPIRED
                SubscriptionHealth.Expiry.SOON -> {
                    findings += Finding.SUBSCRIPTION_ENDS_SOON
                    val days = SubscriptionHealth.daysLeft(expire, now)
                    daysLeft = minOf(daysLeft ?: days, days)
                }

                else -> Unit
            }
            when (SubscriptionHealth.traffic(info.valid && info.hasQuota, info.total, info.used)) {
                SubscriptionHealth.Traffic.EXHAUSTED -> findings += Finding.TRAFFIC_EXHAUSTED
                SubscriptionHealth.Traffic.LOW -> findings += Finding.TRAFFIC_LOW
                else -> Unit
            }
            val servers = SagerDatabase.proxyDao.getByGroup(group.id).count { it.type != AutoSelectorProfiles.TYPE }
            if (servers == 0) findings += Finding.SUBSCRIPTION_EMPTY
        }
        val values = daysLeft?.let { mapOf(RepairValues.DAYS_LEFT to it.toLong()) }.orEmpty()
        val failing = setOf(Finding.SUBSCRIPTION_EXPIRED, Finding.TRAFFIC_EXHAUSTED, Finding.SUBSCRIPTION_EMPTY)
        val status = when {
            findings.any { it in failing } -> StepStatus.FAIL
            findings.isNotEmpty() -> StepStatus.WARN
            else -> StepStatus.OK
        }
        return StepResult(status, findings, values = values)
    }

    /**
     * A URL test of every server (a test session of `:bg`, which stores the delays as any test does), then the server
     * for the checks through the VPN: the current one if it answers, else the best answering one — servers for
     * whitelists first when the network has whitelists (design.md D8).
     */
    private suspend fun servers(done: Map<RepairStep, StepResult>): StepResult {
        val snapshot = withContext(Dispatchers.IO) { ServerCatalog.load(config.wlKeywords, emptySet(), "") }
        val servers = snapshot.servers
        val whitelistIds = servers.filter { it.whitelist }.map { it.id }.toSet()
        if (servers.isEmpty()) {
            return StepResult(StepStatus.FAIL, setOf(Finding.NO_SERVERS), servers = ServersSummary(0, 0, 0, 0))
        }
        val rows = urlTest(servers.map { it.id }) ?: return StepResult.skipped(SkipReason.TESTS_BUSY)
        val latency = rows.filterValues { it != null }.mapValues { it.value!! }
        val answering = latency.keys
        val current = DataStore.selectedProxy
        // An auto-selector (the managed «Авто», its whitelist twin, a country) answers when one of the members it runs
        // does — its build, capped by its build limit — not any server of the list.
        val members = selectorMembers(current)
        val currentAnswers = if (members != null) SelectorRunSet.answers(members, answering) else current in answering
        val whitelists = done[RepairStep.WHITELISTS]?.has(Finding.WHITELISTS_FOUND) == true
        val chosen = when {
            currentAnswers -> current
            else -> answering.sortedWith(
                compareBy<Long>({ if (whitelists && it !in whitelistIds) 1 else 0 }, { latency.getValue(it).let { ms -> if (ms > 0) ms else Int.MAX_VALUE } })
            ).firstOrNull() ?: 0L
        }
        val summary = ServersSummary(
            total = servers.size,
            answering = answering.size,
            whitelistTotal = whitelistIds.size,
            whitelistAnswering = answering.count { it in whitelistIds },
            chosenId = chosen,
            currentAnswers = currentAnswers,
        )
        val (status, findings) = when {
            answering.isEmpty() -> StepStatus.FAIL to setOf(Finding.NO_SERVER_ANSWERS)
            currentAnswers -> StepStatus.OK to emptySet()
            else -> StepStatus.WARN to setOf(Finding.CURRENT_SERVER_DOWN)
        }
        return StepResult(status, findings, servers = summary)
    }

    /**
     * The members [id] runs when it is an auto-selector, else null: its running build while this app's VPN is on
     * (the capped `lastBuilt` the runtime keeps), otherwise the build its planner would make now — after the URL test
     * just stored, so it orders by these delays ([SelectorRunSet]).
     */
    private fun selectorMembers(id: Long): Set<Long>? {
        val selector = ProfileManager.getProfile(id)?.outbound as? AutoSelector ?: return null
        return try {
            SelectorRunSet.of(env.vpnConnected(), selector.lastBuilt) {
                AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(id, selector).let { if (it.ok) it.build else emptyList() }
            }
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }

    /**
     * Runs a URL test of [ids] and returns each server's delay (null = no answer); null when the test engine stays
     * busy with another session. A session of the servers section or of the classic interface is waited for first.
     */
    private suspend fun urlTest(ids: List<Long>): Map<Long, Int?>? {
        val idle = withTimeoutOrNull(BUSY_WAIT_MS) { TestSessionClient.state.first { !it.running } } != null
        // A started session is published at once as running, and its end at once as finished.
        if (!idle || !TestSessionClient.startProfiles(TestSpec.KIND_URL, ids)) return null
        try {
            val finished = TestSessionClient.state.first { !it.running }
            return ids.associateWith { id ->
                val row = finished.rows[id]
                if (row?.ok == true) row.latency else null
            }
        } catch (e: Exception) {
            TestSessionClient.stop()
            throw e
        } finally {
            if (!TestSessionClient.state.value.running) TestSessionClient.dismiss()
        }
    }

    /**
     * This app's VPN for the checks through it (connection-repair "Подключение для проверок через VPN", "Согласие на
     * VPN и чужое VPN при проверках через VPN"): null when it is connected, else why it is not. A VPN that is off is
     * started on the server «Доступность серверов» chose, unless another app's VPN is active (the system would revoke
     * that app's VPN); without the consent the system's request is shown first. The start is recorded in the [journal]
     * to stay only while «Сайты через VPN» passes, and goes with a stop of «Починка».
     */
    private suspend fun ensureVpn(step: RepairStep, done: Map<RepairStep, StepResult>): SkipReason? {
        if (env.vpnConnected()) return null
        vpnProblem?.let { return it }
        if (!env.serviceOff()) {
            // Starting or stopping meanwhile (the user's button, a reload): a start in flight is waited for.
            return if (env.awaitConnected(env.connects(), VPN_START_MS)) null else SkipReason.VPN_OFF
        }
        if (otherVpnActive()) return SkipReason.OTHER_VPN.also { vpnProblem = it }
        val chosen = done[RepairStep.SERVERS]?.servers?.chosenId ?: 0L
        if (chosen <= 0L) return SkipReason.NO_WORKING_SERVER
        if (DataStore.serviceMode == Key.MODE_VPN && VpnService.prepare(context) != null) {
            Logs.i("simple repair: no VPN consent yet, asking for it")
            if (!env.requestConsent()) {
                Logs.i("simple repair: the VPN consent was refused")
                return SkipReason.NO_VPN_PERMISSION.also { vpnProblem = it }
            }
        }
        val selected = DataStore.selectedProxy
        val connects = env.connects()
        // The connection «Починка» starts, by its start time: `:bg` keeps it across restarts in place (the fixes'
        // reloads) and drops it when the service stops, so a VPN the user stopped and started again meanwhile (the
        // quick toggle) has another one and is never stopped by this rollback. Null while the start is in flight.
        var session: Long? = null
        // Bound to this run (not retriable): a later retry could stop a VPN the user started since.
        val entry = journal.record(step, kind = null, keepIf = RepairStep.SITES_VPN, retriable = false, key = "server") {
            val running = DataStore.configurationStore.getLong(SimpleHooks.KEY_CONNECTED_SINCE)
            if (!env.serviceOff() && (session == null || running == session)) {
                Logs.i("simple repair: stopping the VPN started for the checks")
                fixes.vpnStopped()
                env.stopVpn()
            } else {
                Logs.i("simple repair: the VPN started for the checks is gone; the one running now is left alone")
            }
            // The server selected for it goes back while it is still the one «Починка» wrote (compare and set).
            if (selected != chosen && DataStore.selectedProxy == chosen) DataStore.selectedProxy = selected
        }
        if (selected != chosen) withContext(Dispatchers.IO) { DataStore.selectedProxy = chosen }
        Logs.i("simple repair: starting the VPN on server $chosen for the checks through it")
        env.startVpn()
        if (env.awaitConnected(connects, VPN_START_MS)) {
            session = withContext(Dispatchers.IO) { DataStore.configurationStore.getLong(SimpleHooks.KEY_CONNECTED_SINCE) }
            return null
        }
        Logs.w("simple repair: the VPN started for the checks did not connect")
        journal.restore(entry)
        return SkipReason.VPN_START_FAILED.also { vpnProblem = it }
    }

    /** DNS through this app's VPN: a name of the blocked addresses looked up through the VPN network. */
    private suspend fun vpnDns(): StepResult {
        if (!env.vpnConnected()) return StepResult.skipped(SkipReason.VPN_OFF)
        val connectivity = SagerNet.connectivity
        fun vpnNetwork(): Network? = connectivity.activeNetwork?.takeIf {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
        // A VPN that has just connected becomes this app's default network a moment later.
        val active = vpnNetwork() ?: withTimeoutOrNull(VPN_NETWORK_WAIT_MS) {
            var network = vpnNetwork()
            while (network == null) {
                delay(250L)
                network = vpnNetwork()
            }
            network
        } ?: return StepResult.skipped(SkipReason.APP_BYPASSES_VPN)
        val name = config.probesBlocked.firstNotNullOfOrNull { hostOf(it) } ?: return StepResult.skipped(SkipReason.ERROR)
        val found = withContext(Dispatchers.IO) { NetworkProber.lookupSystem(active, name, DNS_WAIT_MS) }
        return if (found.isNullOrEmpty()) StepResult.fail(Finding.VPN_DNS_NO_ANSWER) else StepResult.ok()
    }

    /** Sites through this app's VPN: the URL test of the running connection. */
    private suspend fun vpnSites(): StepResult {
        if (!env.vpnConnected()) return StepResult.skipped(SkipReason.VPN_OFF)
        // Bounded below the step's own limit: the binder call itself cannot be interrupted.
        val delay = env.testConnection(SITES_WAIT_MS)
            ?: return StepResult.skipped(if (env.vpnConnected()) SkipReason.TIMED_OUT else SkipReason.VPN_OFF)
        return when {
            delay > 0 -> StepResult.ok(values = mapOf(RepairValues.LATENCY_MS to delay.toLong()))
            // -2: an OpenVPN/OpenConnect exit whose tunnel is up failed the test: says nothing either way.
            delay == VPN_EXIT_UNKNOWN -> StepResult.skipped(SkipReason.ERROR, note = "vpn exit: no verdict")
            else -> StepResult.fail(Finding.VPN_SITES_DOWN)
        }
    }

    /** The whitelisted (Russian) addresses through the physical network, as «Интернет без VPN» asked them. */
    private fun russianSites(): StepResult {
        val result = probe ?: return StepResult.skipped(SkipReason.ERROR)
        val reached = result.allowed.count { it.reachable }.toLong()
        val values = mapOf(RepairValues.REACHED to reached, RepairValues.ASKED to result.allowed.size.toLong())
        return if (reached > 0) StepResult.ok(values = values) else StepResult.fail(Finding.RU_SITES_DOWN, values = values)
    }

    private fun background(): StepResult = when (BatteryOptimizations.state(context)) {
        BatteryOptimizations.State.UNRESTRICTED -> StepResult.ok()
        BatteryOptimizations.State.OPTIMIZED -> StepResult.warn(Finding.BATTERY_OPTIMIZED)
        BatteryOptimizations.State.RESTRICTED -> StepResult.fail(Finding.BACKGROUND_RESTRICTED)
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun hostOf(url: String): String? = url.removePrefix("https://").substringBefore('/').takeIf { it.isNotEmpty() }

    /** [request] through this client; [read] takes the response (closed afterwards), null on failure or timeout. */
    private suspend fun <T> OkHttpClient.await(request: Request, read: (Response) -> T?): T? =
        withTimeoutOrNull(NetworkProber.DEADLINE_MS) {
            suspendCancellableCoroutine { continuation ->
                val call = newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        val value = try {
                            response.use(read)
                        } catch (e: Exception) {
                            null
                        }
                        if (continuation.isActive) continuation.resume(value)
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            }
        }

    private companion object {
        const val RECHECK_DELAY_MS = 3_000L

        /** The pause before a failed check is looked at again ([secondLook]). */
        const val SECOND_LOOK_MS = 4_000L

        /** A failure quicker than this may be a fluke and gets [SECOND_LOOK_MS] and another look. */
        const val SECOND_LOOK_QUICK_MS = 15_000L

        /** connection-repair "Проверка времени на устройстве": more than 60 s off is a problem. */
        const val CLOCK_TOLERANCE_MS = 60_000L

        const val DNS_WAIT_MS = 4_000L

        /** The URL test through the VPN; within the limit of «Сайты через VPN» (RepairEngine.defaultTimeout). */
        const val SITES_WAIT_MS = 40_000L

        /** A VPN start (or a start in flight) for the checks through it; a cold core start may take a while. */
        const val VPN_START_MS = 75_000L

        /** How long a VPN that has just connected may take to become the default network. */
        const val VPN_NETWORK_WAIT_MS = 5_000L

        const val DNS_MESSAGE = "application/dns-message"

        /** How long a test session of another part of the app may keep the engine busy. */
        const val BUSY_WAIT_MS = 60_000L

        /** HomeViewModel's code for a failed test through an OpenVPN/OpenConnect exit whose tunnel is up. */
        const val VPN_EXIT_UNKNOWN = -2

        /** Settings.Global.PRIVATE_DNS_MODE and its value for a host name set by hand (hidden constants). */
        const val PRIVATE_DNS_MODE = "private_dns_mode"
        const val PRIVATE_DNS_MODE_HOSTNAME = "hostname"
    }
}
