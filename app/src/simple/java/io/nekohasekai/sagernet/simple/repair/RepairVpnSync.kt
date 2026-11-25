package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

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

/** How an owed reload ended. */
enum class ReloadOutcome {
    /** Sent to a connected service. */
    SENT,

    /** Not needed any more: the service went off (its next start reads the stored values), or it was disarmed. */
    DROPPED,
}

/**
 * The one reload the running VPN is owed after «Починка» put a value back (design.md D8): process-wide, so it outlives
 * the run, its screen and any wait for it. It is armed where the value is written — before anything waits — and
 * resolved only by a reload actually sent while the service is connected (never into a start, which a reload would
 * stop), or dropped when the service goes off, when it is [disarm]ed (the VPN «Починка» started is being stopped), or
 * after [lifetimeMs]. A cancelled wait for it therefore never loses it.
 */
class PendingReload(
    private val phase: () -> ServicePhase,
    private val sendReload: () -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 500L,
    private val lifetimeMs: Long = 10 * 60_000L,
) {

    /** One arming: its outcome, and the connects of the service when the reload was sent (for the wait after it). */
    class Owed internal constructor(private val connects: () -> Int) {
        internal val outcome = CompletableDeferred<ReloadOutcome>()

        @Volatile
        var connectsAtSend: Int = 0
            internal set

        internal fun sent() {
            connectsAtSend = connects()
        }

        /** The outcome; null when still owed after [timeoutMs]. */
        suspend fun await(timeoutMs: Long): ReloadOutcome? = withTimeoutOrNull(timeoutMs) { outcome.await() }
    }

    private var current: Owed? = null
    private var job: Job? = null

    val armed: Boolean @Synchronized get() = current != null

    /**
     * Owes a reload (a second arming before it resolved shares the first); [connects] counts the service's connects
     * for the wait after the send.
     */
    @Synchronized
    fun arm(connects: () -> Int = { 0 }): Owed {
        current?.let { owed ->
            // Owed already; a watcher that is gone (it cannot end while owed, but if it did) is started again.
            if (job?.isActive != true) watch(owed)
            return owed
        }
        val owed = Owed(connects)
        current = owed
        log("a reload is owed to the service; it is sent once it is connected")
        watch(owed)
        return owed
    }

    private fun watch(owed: Owed) {
        job = scope.launch {
            val sent = withTimeoutOrNull(lifetimeMs) { watchUntilResolved(owed) } ?: false
            if (!sent) finish(owed, ReloadOutcome.DROPPED)
        }
    }

    /**
     * Until the service is connected (then the reload goes) or off; true when the reload was sent. A look that fails
     * (the state cannot be read, the reload cannot be sent) is tried again at the next poll: the reload stays owed.
     */
    private suspend fun watchUntilResolved(owed: Owed): Boolean {
        while (true) {
            try {
                when (phase()) {
                    ServicePhase.CONNECTED -> return sendIfOwed(owed)
                    ServicePhase.OFF -> return false
                    ServicePhase.STARTING -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("the owed reload is tried again: ${e.message}")
            }
            delay(pollMs)
        }
    }

    /** No reload is owed any more: the VPN is being stopped (a reload reaching it would start it again). */
    @Synchronized
    fun disarm() {
        current?.let { finish(it, ReloadOutcome.DROPPED) }
        job?.cancel()
    }

    @Synchronized
    private fun sendIfOwed(owed: Owed): Boolean {
        if (current !== owed) return false
        log("the service is connected: the owed reload")
        owed.sent()
        sendReload()
        finish(owed, ReloadOutcome.SENT)
        return true
    }

    @Synchronized
    private fun finish(owed: Owed, outcome: ReloadOutcome) {
        if (current === owed) current = null
        owed.outcome.complete(outcome)
    }
}

/**
 * The wait for the subscription updates «Починка» asks for — best effort, for the recheck to see the updated servers;
 * no rollback depends on it (design.md D8: an update of provider content is not rolled back, and «Починка» puts back
 * only what it wrote itself). [submit] hands a group to `:bg`'s queue, [barrier] is a no-op queued behind them in the
 * same FIFO queue, so it returns once every submitted update has run. When the update replaced the profile the service
 * ran, the successor start `:bg` makes afterwards is waited for too (the service runs another profile and is
 * connected). Every wait is bounded: [limitMs] for the queue, [successorMs] for the successor; over the limit the
 * update counts as not done (the fix is not counted), never a hang. Cancellable: a stop ends the wait at once, and
 * the update goes on in `:bg` with what it writes.
 */
class SubscriptionWait(
    private val submit: suspend (Long) -> Unit,
    private val barrier: suspend () -> Unit,
    private val profileExists: (Long) -> Boolean,
    private val vpn: RepairVpn,
    /** The profile the service runs now. */
    private val runningNow: () -> Long = { 0L },
    private val limitMs: Long = 90_000L,
    private val successorMs: Long = 17_000L,
    private val pollMs: Long = 200L,
    private val log: (String) -> Unit = {},
) {

    /** True when every update was submitted and ran within the limit. [running]: the profile the service ran, or null. */
    suspend fun await(groups: List<Long>, running: Long?): Boolean {
        val ran = withTimeoutOrNull(limitMs) {
            try {
                for (gid in groups) submit(gid)
                barrier()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("the subscription update could not be submitted: ${e.message}")
                false
            }
        } ?: false.also { log("the subscription update did not finish within ${limitMs / 1000} s") }
        if (ran && running != null && !profileExists(running)) {
            withTimeoutOrNull(successorMs) {
                while (!(vpn.connected() && runningNow() != running)) delay(pollMs)
            }
        }
        return ran
    }
}

/**
 * «Обновление подписки»: every subscription is updated and a VPN that runs takes the update up before the recheck (a
 * running profile that kept its id but took new content is otherwise applied only at its next start). An update that
 * did not run in time, one that left the user's VPN off, or a VPN that did not connect with it makes the fix
 * unsuccessful. Not reversible (design.md D8): the servers are the provider's current ones and stay, and so does a
 * selection the update itself made (a successor of a replaced profile); a rollback writes nothing.
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
    private val log: (String) -> Unit = {},
) : RepairFix {

    override val kind = FixKind.SUBSCRIPTION_UPDATED

    override val reversible: Boolean get() = false

    override suspend fun apply() {
        log("fix subscription_updated: ${groups.size} subscription(s)")
        val before = if (vpn.off()) null else running()
        if (!wait.await(groups, before)) throw IllegalStateException("the subscription update did not run in time")
        // The update removed the profile the VPN ran and nothing took over: the user's VPN is off now — no fix.
        if (before != null && vpn.off()) throw IllegalStateException("the update stopped the VPN")
        if (!vpn.off() && reloadAllowed() && !reloader.reloadWhenConnected("subscriptions updated")) {
            throw IllegalStateException("the VPN did not connect with the updated subscription")
        }
    }

    // Provider content and the update's own selection stay.
    override fun restore() = Unit
}

/**
 * A call to `:bg` that a wait may give up on (a stop, a time limit): [block] runs in [scope], since a binder call
 * cannot be interrupted, and the caller waits at most [limitMs] (null then); a wait that ends early cancels the call
 * too — a service binding that never answered is given up (and unbound).
 *
 * [block] must `claim()` before it hands anything to the service. The claim and the give-up are one compare-and-set on
 * the same state, so they cannot interleave: either the give-up comes first — the claim fails and nothing is
 * submitted — or the claim does, and the submission is part of the run whatever happens after (a subscription update
 * is not rolled back anyway). No pause between the check and the submission can let a submission slip in after a stop.
 */
suspend fun <T> detachedCall(
    scope: CoroutineScope,
    limitMs: Long,
    gate: RepairStopGate = RepairStopGate(),
    block: suspend (claim: () -> Boolean) -> T,
): T? {
    val state = AtomicInteger(LIVE)
    // A run already stopped gives the call up before it starts.
    gate.register(state)
    val call = scope.async { block { state.compareAndSet(LIVE, CLAIMED) } }
    // A claimed call goes on (its work is part of the run); one not claimed yet is given up and submits nothing.
    fun giveUp() {
        state.compareAndSet(LIVE, GIVEN_UP)
        call.cancel()
    }
    try {
        val result = withTimeoutOrNull(limitMs) { call.await() }
        if (result == null) giveUp()
        return result
    } catch (e: CancellationException) {
        giveUp()
        throw e
    } finally {
        gate.unregister(state)
    }
}

/**
 * The stop of one run of «Починка», seen by its calls to `:bg` at once: [close] is called by «Остановить» itself,
 * before the run is cancelled, and gives every call of the run that has not claimed yet up in the same moment (the
 * cancellation reaches the waiting coroutine only later — a callback in that gap must not be able to claim). The order
 * is therefore always: stop → give up → cancel. A call made after the close starts given up.
 */
class RepairStopGate {
    private val calls = HashSet<AtomicInteger>()
    private var closed = false

    val isClosed: Boolean @Synchronized get() = closed

    @Synchronized
    fun close() {
        closed = true
        for (state in calls) state.compareAndSet(LIVE, GIVEN_UP)
    }

    @Synchronized
    internal fun register(state: AtomicInteger) {
        if (closed) state.compareAndSet(LIVE, GIVEN_UP) else calls += state
    }

    @Synchronized
    internal fun unregister(state: AtomicInteger) {
        calls -= state
    }
}

private const val LIVE = 0
private const val CLAIMED = 1
private const val GIVEN_UP = 2
