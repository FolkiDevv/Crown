package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The service as «Починка» waits for it while it changes settings (design.md D8). Pure: the device side is the home
 * screen's service connection; JVM unit tests use a fake.
 */
interface RepairVpn {
    /** This app's VPN is connected. */
    fun connected(): Boolean

    /** This app's service is off altogether (not starting, connected or stopping). */
    fun off(): Boolean

    /** How many times the service has connected so far. */
    fun connects(): Int

    /** Until the service has connected more than [after] times and is connected; false after [timeoutMs]. */
    suspend fun awaitConnected(after: Int, timeoutMs: Long): Boolean

    /** Asks the running service to reload (it restarts with the settings stored now). */
    fun sendReload()
}

/**
 * Reloads of the running VPN after a change, each waited for: a fix counts as made only when the VPN that took it up
 * is connected again (false otherwise, and the fix is put back).
 */
class VpnReloader(
    private val vpn: RepairVpn,
    private val log: (String) -> Unit = {},
    private val reconnectMs: Long = 60_000L,
    /** How long a restart sent by someone else (`:bg` after a subscription update) may take to show. */
    private val restartShowMs: Long = 2_000L,
) {

    /** Reloads the connected VPN; true when it is connected again within the time limit. */
    suspend fun reload(why: String): Boolean {
        val before = vpn.connects()
        log("$why, reload of the running service")
        vpn.sendReload()
        val connected = vpn.awaitConnected(before, reconnectMs)
        if (!connected) log("the service did not reconnect after the reload")
        return connected
    }

    /**
     * A reload once the service is connected: it may be restarting already, and a reload that reaches a start in
     * flight would stop it. False when it does not connect, or does not reconnect after the reload.
     */
    suspend fun reloadWhenConnected(why: String): Boolean {
        delay(restartShowMs)
        val connects = vpn.connects()
        if (!vpn.connected() && !vpn.awaitConnected(connects, reconnectMs)) {
            log("the service did not connect; $why without a reload")
            return false
        }
        return reload(why)
    }
}

/** The service as a [PendingReload] watches it. */
enum class ServicePhase {
    OFF,

    /** Connecting, reloading or stopping. */
    STARTING,
    CONNECTED,
}

/**
 * A reload owed to the running VPN that outlives the run of «Починка» that owes it: values were put back while a start
 * was in flight (it may have read the replaced ones), and the start did not connect while the run waited. It fires
 * once, on the next connected state — never into a start — and is dropped when the service goes off (its next start
 * reads the restored values) or after [lifetimeMs].
 */
class PendingReload(
    private val phase: () -> ServicePhase,
    private val sendReload: () -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 500L,
    private val lifetimeMs: Long = 10 * 60_000L,
) {
    private var job: Job? = null

    val armed: Boolean @Synchronized get() = job?.isActive == true

    @Synchronized
    fun arm() {
        if (job?.isActive == true) return
        log("a reload is owed to the service; it is sent once it is connected")
        job = scope.launch {
            withTimeoutOrNull(lifetimeMs) {
                while (true) {
                    when (phase()) {
                        ServicePhase.CONNECTED -> {
                            log("the service is connected: the owed reload")
                            sendReload()
                            return@withTimeoutOrNull
                        }

                        ServicePhase.OFF -> return@withTimeoutOrNull
                        ServicePhase.STARTING -> delay(pollMs)
                    }
                }
            }
        }
    }
}

/**
 * The wait for the subscription updates «Починка» asked for (connection-repair "Безопасные автоматические
 * исправления": обновление подписки), so that its rollback never runs while an update can still change the selection
 * or start the service. An update counts as finished only when it was seen to run — the queue reported it, or the
 * group's last update time changed — and the queue then reported none of the groups any more. When the update
 * replaced the profile the service ran, `:bg` starts its successor in a coroutine of its own once the service has
 * stopped (within 15 s): that start is waited for too, up to [successorMs].
 */
class SubscriptionWait(
    /** gid → queued/running; absent = idle (SubscriptionClient.states). */
    private val states: Flow<Map<Long, Int>>,
    /** Asks the queue to update a group. */
    private val request: (Long) -> Unit,
    /** The group's last update time. */
    private val lastUpdate: (Long) -> Long,
    private val profileExists: (Long) -> Boolean,
    private val vpn: RepairVpn,
    private val startMs: Long = 15_000L,
    private val perGroupMs: Long = 30_000L,
    private val maxMs: Long = 180_000L,
    private val successorMs: Long = 20_000L,
) {

    /** True when every update finished; [running]: the profile the service ran before, null when it was off. */
    suspend fun await(groups: List<Long>, running: Long?): Boolean = coroutineScope {
        val before = groups.associateWith(lastUpdate)
        val connects = vpn.connects()
        // Collecting the states keeps the queue's callback registered while the updates run.
        val watch = launch { states.collect { } }
        try {
            groups.forEach(request)
            val seen = withTimeoutOrNull(startMs) { states.first { s -> groups.any { it in s } } } != null
            val limit = minOf(maxMs, startMs + perGroupMs * groups.size)
            val done = withTimeoutOrNull(limit) { states.first { s -> groups.none { it in s } } } != null
            // Too quick to be seen queued, but its time was written: it ran.
            val ran = seen || groups.any { lastUpdate(it) != before.getValue(it) }
            if (running != null && !profileExists(running)) vpn.awaitConnected(connects, successorMs)
            done && ran
        } finally {
            watch.cancel()
        }
    }
}

/**
 * «Обновление подписки»: every subscription is updated, the wait ([SubscriptionWait]) cannot be cut short by a stop,
 * and a VPN that runs takes the update up before the recheck (a running profile that kept its id but took new content
 * is otherwise applied only at its next start). An update that did not finish, or a VPN that did not connect with it,
 * makes the fix unsuccessful. The servers are the provider's current ones and stay; [restoreSelection] puts back a
 * selection the update moved.
 */
class SubscriptionUpdateFix(
    private val groups: List<Long>,
    private val wait: SubscriptionWait,
    private val vpn: RepairVpn,
    private val reloader: VpnReloader,
    /** The profile the service runs. */
    private val running: () -> Long,
    /** False once «Починка» stopped the VPN it had started: nothing may reload it. */
    private val reloadAllowed: () -> Boolean,
    private val restoreSelection: () -> Unit,
    private val log: (String) -> Unit = {},
) : RepairFix {

    override val kind = FixKind.SUBSCRIPTION_UPDATED

    override suspend fun apply() {
        log("fix subscription_updated: ${groups.size} subscription(s)")
        val before = if (vpn.off()) null else running()
        val finished = withContext(NonCancellable) { wait.await(groups, before) }
        if (!finished) throw IllegalStateException("the subscription update did not finish")
        // The update removed the profile the VPN ran and nothing took over: the user's VPN is off now — no fix.
        if (before != null && vpn.off()) throw IllegalStateException("the update stopped the VPN")
        if (!vpn.off() && reloadAllowed() && !reloader.reloadWhenConnected("subscriptions updated")) {
            throw IllegalStateException("the VPN did not connect with the updated subscription")
        }
    }

    override fun restore() = restoreSelection()
}
