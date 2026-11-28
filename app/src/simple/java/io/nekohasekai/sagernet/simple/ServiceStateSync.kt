package io.nekohasekai.sagernet.simple

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore

/**
 * proxy-connection «UI process binding»: `DataStore.serviceState` outside `:bg` is a field of the process, which only
 * the bindings to the service (SagerConnection, touch point 16) can keep true: the state read when one connects, each
 * change it reports, Idle when the service's process dies. In `:bg` the service writes the field itself.
 */
object ServiceStateSync {

    private val tracker = ServiceStateTracker { DataStore.serviceState = it }

    @JvmStatic
    fun bound(binding: Any, read: () -> Int) = tracker.bound(binding) {
        BaseService.State.entries.getOrNull(read())
    }

    @JvmStatic
    fun changed(binding: Any, state: BaseService.State) = tracker.changed(binding, state)

    @JvmStatic
    fun lost(binding: Any) = tracker.lost(binding)

    @JvmStatic
    fun unbound(binding: Any) = tracker.unbound(binding)

    /** Whether a binding of this process vouches for `DataStore.serviceState` now. */
    fun isLive(): Boolean = tracker.isLive()
}
