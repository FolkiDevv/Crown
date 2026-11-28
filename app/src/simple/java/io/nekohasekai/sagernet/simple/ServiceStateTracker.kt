package io.nekohasekai.sagernet.simple

import io.nekohasekai.sagernet.bg.BaseService
import java.util.Collections
import java.util.WeakHashMap

/**
 * The rule of proxy-connection «UI process binding» for the service state a process other than `:bg` keeps (see
 * [ServiceStateSync]), without Android: [write] stores it.
 *
 * - A binding that connects reads the state the service has now ([bound]) and stores it, unless a change was reported
 *   to this process after the read began: that change is newer than what was read.
 * - Each reported change is stored ([changed]).
 * - The service's process died ([lost]): Idle.
 * - A binding the process lets go ([unbound]) leaves the state as last known, but no longer vouches for it.
 *
 * [isLive]: some binding that read the service's state is still in place (neither died nor let go, also not while
 * it read), so the stored state follows the service.
 */
class ServiceStateTracker(private val write: (BaseService.State) -> Unit) {

    /** Counts what was stored from a report or a death; a read that saw it move is older than what is stored. */
    private var reports = 0L

    /** Only [bound] makes a binding live: a late report of one let go (the callback is one-way) must not. */
    private val live = HashSet<Any>()

    /** Bindings that died or were let go while their [bound] read ran; weak, so a dropped binding is not kept. */
    private val ended: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    /** [binding] connected: [read] asks the service for its state (null or a throw: it could not tell). */
    fun bound(binding: Any, read: () -> BaseService.State?) {
        val before = synchronized(this) {
            ended.remove(binding)
            reports
        }
        val state = try {
            read()
        } catch (_: Exception) {
            null
        } ?: return
        synchronized(this) {
            if (binding in ended) return
            live.add(binding)
            if (reports == before) write(state)
        }
    }

    /** [binding] was told of a change. */
    fun changed(@Suppress("UNUSED_PARAMETER") binding: Any, state: BaseService.State) = synchronized(this) {
        reports++
        write(state)
    }

    /** The service's process died under [binding]. */
    fun lost(binding: Any) = synchronized(this) {
        reports++
        end(binding)
        write(BaseService.State.Idle)
    }

    /** The process let [binding] go. */
    fun unbound(binding: Any) = synchronized(this) { end(binding) }

    private fun end(binding: Any) {
        live.remove(binding)
        ended.add(binding)
    }

    fun isLive(): Boolean = synchronized(this) { live.isNotEmpty() }
}
