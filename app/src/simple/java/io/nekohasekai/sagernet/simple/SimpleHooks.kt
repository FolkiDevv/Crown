package io.nekohasekai.sagernet.simple

import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.simple.regime.NetworkRegimeWatcher
import io.nekohasekai.sagernet.simple.subs.FetchMark
import io.nekohasekai.sagernet.simple.subs.SubscriptionAlerts
import io.nekohasekai.sagernet.simple.subs.SubscriptionState

/**
 * Calls from the service in `:bg` (design.md D2, touch point 6 in `bg/BaseService.kt`). Batch 2 keeps the start of
 * the connection for the home screen's timer; batch 6 starts and stops the network watcher (NetworkRegimeWatcher) here.
 *
 * State the UI process reads lives in DataStore keys with the `simple_` prefix (design.md D3), never in AIDL.
 */
object SimpleHooks {

    /** `SystemClock.elapsedRealtime()` of the first Connected of this connection, 0 = not connected. */
    const val KEY_CONNECTED_SINCE = "simple_connected_since"

    /** Counts the starts of a core (also restarts in place), so the simple UI can tell one happened meanwhile. */
    const val KEY_SERVICE_STARTS = "simple_service_starts"

    /** A restart in place is in flight: the connection goes on and keeps its start. Lives only in `:bg`. */
    @Volatile
    private var restarting = false

    /** Called just before the service reports Connected, so the UI finds the time when it gets the state. */
    @JvmStatic
    fun onServiceStarted() = guarded {
        val since = ConnectionClock.startedAt(
            stored = DataStore.configurationStore.getLong(KEY_CONNECTED_SINCE) ?: 0L,
            restarting = restarting,
            now = SystemClock.elapsedRealtime(),
        )
        restarting = false
        DataStore.configurationStore.putLong(KEY_CONNECTED_SINCE, since)
        countStart()
        NetworkRegimeWatcher.serviceStarted()
    }

    /**
     * Called just before the service reports Stopped. [restart]: a new core starts in place right after (a switch or
     * a reload), which the home screen shows as reconnecting and which keeps the start time.
     */
    @JvmStatic
    fun onServiceStopped(restart: Boolean) = guarded {
        restarting = restart
        if (!restart) DataStore.configurationStore.remove(KEY_CONNECTED_SINCE)
        // The new core of a restart reads its settings after this, before it reports Connected: counting it here
        // already lets an app list edit see that the core may have taken the edit's staged per-app keys.
        if (restart) countStart()
        if (!restart) NetworkRegimeWatcher.serviceStopped()
    }

    /**
     * A subscription fetch ended (touch point 9 in `group/SubscriptionRefresh.kt`): [deviceLimit] the panel's device
     * limit headers, [servers] the answer had servers, [announce] it had an announcement. Keeps the mark of
     * simple-ui "Сообщения подписки" until a fetch brings servers again; a failed fetch changes nothing else.
     */
    @JvmStatic
    fun onSubscriptionFetched(gid: Long, deviceLimit: Boolean, servers: Boolean, announce: Boolean) = guarded {
        val mark = when {
            deviceLimit -> FetchMark.LIMIT
            servers -> FetchMark.NONE
            announce -> FetchMark.EMPTY
            else -> return@guarded
        }
        SubscriptionState.setMark(gid, mark)
    }

    /** What a refresh refused for the device limit reports (the classic diff or error, the simple snackbar). */
    @JvmStatic
    fun deviceLimitMessage(announce: String): String {
        val text = app.getString(R.string.simple_notice_limit_text)
        return if (announce.isBlank()) text else text + "\n\n" + announce.trim()
    }

    /** A subscription update is through, whatever its outcome: the notifications of its state (in `:bg`). */
    @JvmStatic
    fun onSubscriptionRefreshed(gid: Long) = guarded {
        SubscriptionAlerts.check(app, gid)
    }

    private fun countStart() {
        val store = DataStore.configurationStore
        store.putLong(KEY_SERVICE_STARTS, (store.getLong(KEY_SERVICE_STARTS) ?: 0L) + 1)
    }

    /** The service must never fail because of the simple UI. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}

/** The pure rules of [SimpleHooks.KEY_CONNECTED_SINCE], covered by JVM unit tests. */
object ConnectionClock {

    /** A restart keeps a plausible stored start; anything else starts the clock now. */
    fun startedAt(stored: Long, restarting: Boolean, now: Long): Long =
        if (restarting && isValid(stored, now)) stored else now

    /** A start from this boot (elapsedRealtime restarts at boot, so a later value is left from an earlier one). */
    fun isValid(since: Long, now: Long): Boolean = since in 1..now
}
