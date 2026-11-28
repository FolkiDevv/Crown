package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import io.nekohasekai.sagernet.appwidget.Widgets
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.bg.proto.DeferredRuleSets
import io.nekohasekai.sagernet.bg.proto.LocalDnsFailedException
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.RuleSetDownloadFailedException
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.bg.proto.urlTestCurrent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.simple.data.DataGate
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.throneproj.mobile.Instance
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BaseService {

    companion object {
        /**
         * The main core's build, start and close: blocking JNI and database work kept off the main looper, one at a
         * time, so a close never overlaps a start.
         */
        private val coreDispatcher = Executors.newSingleThreadExecutor { Thread(it, "CoreLifecycle") }.asCoroutineDispatcher()
    }

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var wifiMonitor: WifiStateAccess.Monitor? = null

        // Pause and wake are JNI calls into the core: run them in order and off the main thread.
        private val idleLock = Mutex()

        @Volatile
        private var pausedBox: Instance? = null

        /** [box] is paused for device idle. */
        fun idlePaused(box: Instance): Boolean = pausedBox === box

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                Action.CLOSE -> service.stopRunner()
                Action.SWITCH_NEXT -> service.switchRelative(1)
                Action.SWITCH_PREVIOUS -> service.switchRelative(-1)
                Action.SWITCH_PROFILE -> service.switchProfile(intent.getLongExtra(Action.EXTRA_PROFILE_ID, 0L))
                Action.REFRESH_WIFI_STATE -> afterStart { refreshWifiState(ctx) }
                Action.REFRESH_NOTIFICATION -> refreshNotification()
                Action.AUTO_SELECTOR_AUTOMATIC -> AutoSelectorRuntime.releasePin(intent.getLongExtra(Action.EXTRA_PROFILE_ID, 0L))
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> afterStart { applyIdleMode() }

                Action.RESET_UPSTREAM_CONNECTIONS -> afterStart {
                    runOnDefaultDispatcher {
                        proxy?.boxOrNull?.resetNetwork()
                        runOnMainDispatcher {
                            Util.collapseStatusBar(ctx)
                            Toast.makeText(ctx, R.string.reset_upstream_connections_done, Toast.LENGTH_SHORT)
                                .show()
                        }
                    }
                }
            }
        }

        private fun refreshNotification() {
            val shown = notification ?: return
            val running = proxy
            runOnMainDispatcher {
                var oldTitle: String? = null
                val newTitle = running?.let { p ->
                    oldTitle = p.displayProfileName
                    withContext(Dispatchers.IO) { ServiceNotification.genTitle(p.profile) }
                        .also { p.displayProfileName = it }
                }
                if (notification === shown) shown.reloadSettings(oldTitle, newTitle)
            }
        }

        /** Runs [block] once a start in progress is over: the box is never touched while it starts on another thread. */
        private fun afterStart(block: () -> Unit) {
            val job = connectingJob?.takeIf { it.isActive } ?: return block()
            runOnMainDispatcher {
                job.join()
                block()
            }
        }

        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        /** A plain stop asked for while a restart was stopping the old core: the restart ends stopped instead. */
        var stopRequested = false

        /** A reload asked for while a restart was building the next core: it builds it again with the new settings. */
        var restartAgain = false
        /** A start asked for while stopping, the later of it and [stopRequested] winning: the stop ends in a start. */
        var startRequested = false

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
            Widgets.push(service as Context)
        }

        /** Reads the idle mode when the job runs, so the last of several quick events always wins. */
        private fun applyIdleMode() = runOnDefaultDispatcher {
            idleLock.withLock {
                val box = proxy?.boxOrNull ?: return@withLock
                if (SagerNet.power.isDeviceIdleMode) {
                    if (pausedBox !== box) {
                        box.pause()
                        pausedBox = box
                    }
                } else if (pausedBox === box) {
                    pausedBox = null
                    box.wake()
                    if (DataStore.configurationStore.getBoolean(Key.WAKE_RESET_CONNECTIONS, true)) {
                        box.resetNetwork()
                    }
                }
            }
        }

        private fun refreshWifiState(ctx: Context) = runOnDefaultDispatcher {
            val box = proxy?.boxOrNull ?: return@runOnDefaultDispatcher
            if (!box.needWIFIState()) return@runOnDefaultDispatcher
            box.updateWIFIState()
            if (WifiStateAccess.status(ctx) == WifiStateAccess.Status.OK) {
                PlatformNotifications.cancelWifiRulesInactive(ctx)
            }
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        /** Client binder -> connection id: a remote client's callback arrives as a new proxy object on every call. */
        private val callbackIds = ConcurrentHashMap<IBinder, Int>()

        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                callback?.let { callbackIds.remove(it.asBinder()) }
            }
        }

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            val binder = cb.asBinder()
            synchronized(callbackIds) {
                if (callbackIds.put(binder, id) == null && !callbacks.register(cb)) callbackIds.remove(binder)
            }
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        /** [work] for the callbacks registered with [id] only. */
        suspend fun broadcast(id: Int, work: (ISagerNetServiceCallback) -> Unit) =
            broadcast { if (callbackIds[it.asBinder()] == id) work(it) }

        fun hasCallback(id: Int): Boolean = callbackIds.containsValue(id)

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            synchronized(callbackIds) {
                callbackIds.remove(cb.asBinder())
                callbacks.unregister(cb)
            }
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            val proxy = data?.proxy?.takeIf { it.isInitialized() } ?: error("core not started")
            try {
                return runBlocking {
                    urlTestCurrent(
                        proxy.box, proxy.core, DataStore.testUrl, DataStore.urlTestTimeoutMs,
                        exitsThroughVpn(proxy.profile),
                    )
                }
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        /**
         * Instance.updateRuleSets (core/internal/rulesets UpdateAll): every remote rule-set of the running instance,
         * at most 5 at a time within 60 s. Arrives on a binder thread, so blocking that long is fine.
         */
        override fun updateRuleSets(): String = try {
            val box = data?.takeIf { it.state.connected }?.proxy?.boxOrNull
            if (box == null) {
                ruleSetUpdateJson(0, "not running")
            } else {
                val update = box.updateRuleSets()
                ruleSetUpdateJson(update.updated, update.error ?: "")
            }
        } catch (e: Throwable) {
            ruleSetUpdateJson(0, e.readableMessage)
        }

        private fun ruleSetUpdateJson(updated: Int, error: String): String =
            jsonObjectOf("updated" to updated, "error" to error).toCompact()

        override fun autoSelectorStatus(withMembers: Boolean): String = AutoSelectorRuntime.statusJson(withMembers)

        override fun autoSelectorRecheck() = AutoSelectorRuntime.recheck()

        override fun autoSelectorSelect(memberProfileId: Long) = AutoSelectorRuntime.select(memberProfileId)

        override fun liveStats(session: Long, afterSerial: Long): LiveStatsBatch = LiveStatsRecorder.batch(session, afterSerial)

        override fun vpnAuthState(): String = VpnAuthRuntime.stateJson()

        override fun submitVpnChallenge(tag: String?, id: String?, answerJson: String?): String =
            VpnAuthRuntime.submit(tag.orEmpty(), id.orEmpty(), answerJson.orEmpty())

        override fun cancelVpnChallenge(tag: String?, id: String?): String = VpnAuthRuntime.cancel(tag.orEmpty(), id.orEmpty())

        /** A screen of the app is in the foreground: MainActivity between onStart and onStop, or the VPN sign-in prompt. */
        fun uiForeground(): Boolean = callbackIds.values.any {
            it == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND || it == SagerConnection.CONNECTION_ID_VPN_AUTH
        }

        fun vpnAuthChanged(json: String) = launch { broadcast { it.cbVpnAuthUpdate(json) } }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            callbackIds.clear()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> AutoSelectorRuntime.restart(this) { stopRunner(true) }
                s == State.Stopping -> data.restartAgain = true
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        /** Runs [id] instead of the current profile, staying in the foreground; the running profile is left alone. */
        fun switchProfile(id: Long) {
            if (id <= 0L) return
            DataStore.selectedProxy = id
            val s = data.state
            if (s.canStop && data.proxy?.profile?.id == id) return
            runOnDefaultDispatcher {
                data.binder.broadcast { it.cbSelectorUpdate(id) }
            }
            // While stopping, a restart in flight starts whatever is selected once the old core is gone.
            if (s.canStop) AutoSelectorRuntime.restart(this) { stopRunner(true) } else Widgets.push(this as Context)
        }

        /** The neighbour [step] away in the group of the selected profile, wrapping around. */
        fun switchRelative(step: Int) {
            val current = DataStore.selectedProxy.takeIf { it > 0L } ?: data.proxy?.profile?.id ?: return
            val target = ProfileOrder.neighbour(current, step) ?: return
            switchProfile(target.id)
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            // Crown (touch point 12): the service's own start, not the user's: it keeps the count of restores.
            val intent = Intent(this, javaClass).putExtra(io.nekohasekai.sagernet.simple.ServiceRestore.EXTRA_INTERNAL, true)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
        }

        /** [keepTun]: a restart whose next core is built keeps the VPN interface until that core opens its own. */
        suspend fun killProcesses(keepTun: Boolean = false): Throwable? {
            val proxy = data.proxy
            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            var cleanupError: Throwable? = null
            fun recordCleanupFailure(stage: String, error: Throwable) {
                if (cleanupError == null) {
                    cleanupError = error
                } else if (cleanupError !== error) {
                    cleanupError?.addSuppressed(error)
                }
                Logs.w(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=$stage failed " +
                        "type=${error.javaClass.name} message=${error.message}"
                )
            }
            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill begin"
            )
            try {
                data.wifiMonitor?.stop()
            } catch (error: Throwable) {
                recordCleanupFailure("wifi-monitor-stop", error)
            } finally {
                data.wifiMonitor = null
            }

            try {
                if (proxy != null) withContext(coreDispatcher) { proxy.close() }
                Logs.i(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=proxy-close success"
                )
            } catch (error: Throwable) {
                recordCleanupFailure("proxy-close", error)
            }

            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                recordCleanupFailure("network-listener-stop", error)
            }

            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill done " +
                    "hasCleanupError=${cleanupError != null}"
            )
            return cleanupError
        }

        /** Closes the interface a restart kept ([killProcesses] keepTun) when no new core is going to replace it. */
        fun releaseRetiredTun() {}

        /**
         * The next core of a restart, built while the running one still carries traffic (and the app's own requests
         * of the build, geo downloads and measurements, still have a working network); null when it could not be
         * built or a stop came, and the restart then closes the tunnel first, as a plain one. A reload or another
         * selection meanwhile builds it again. Built instances it does not return are left in [discarded]: they are
         * closed once the running core is, as closing a core drops the socket protector the newest one installed.
         */
        private suspend fun prepareRestart(deferRuleSets: Boolean, discarded: MutableList<ProxyInstance>): ProxyInstance? {
            repeat(3) {
                data.restartAgain = false
                // The build reads the per-app keys now: an app list edit must see a start (SimpleHooks).
                io.nekohasekai.sagernet.simple.SimpleHooks.onRestartBuilding()
                val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return null
                val next = ProxyInstance(profile, this).also {
                    it.deferRuleSets = deferRuleSets
                    it.replacedApiPort = data.proxy?.apiPort ?: 0
                }
                discarded += next
                // A Stop cancels it (stopRunner): the build may wait for downloads.
                val job = CoroutineScope(Dispatchers.Main).launch {
                    try {
                        withContext(coreDispatcher) { next.init() }
                    } catch (e: Throwable) {
                        Logs.w("restart: next core not built, the tunnel closes first: ${e.readableMessage}")
                    }
                }
                data.connectingJob = job
                try {
                    job.join()
                } finally {
                    if (data.connectingJob === job) data.connectingJob = null
                }
                if (!next.isInitialized() || data.stopRequested) return null
                if (!data.restartAgain && next.profile.id == DataStore.selectedProxy) {
                    discarded -= next
                    return next
                }
            }
            return null
        }

        /**
         * A restart keeps the service, its notification and the receiver: the new core starts in place, so a switch
         * from the notification or a widget never needs another foreground-service start from the background.
         */
        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            // A restart's running core keeps serving while the next one is built: its sockets still need protect().
            if (!restart) {
                // Crown (touch point 12): a stop of any kind ends the connection the system would restore.
                io.nekohasekai.sagernet.simple.ServiceRestore.onStopRequested()
                DataStore.baseService = null
                DataStore.vpnService = null
            }

            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxy = data.proxy
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                frame.className != Thread::class.java.name && frame.methodName != "stopRunner"
            }?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                ?: "unknown"
            Logs.i(
                "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId restart=$restart " +
                    "state=${data.state} profileId=${proxy?.profile?.id ?: -1L} " +
                    "hasMessage=${msg != null} caller=$caller"
            )
            if (data.state == State.Stopping) {
                // the old core now closes off the main thread, so a Stop can arrive in the middle of a restart
                if (!restart) {
                    data.stopRequested = true
                    data.startRequested = false
                    data.connectingJob?.cancel() // the build of the next core (prepareRestart)
                } else {
                    data.restartAgain = true
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=ignored-already-stopping"
                )
                return
            }
            this as Service

            data.stopRequested = false
            data.startRequested = false
            data.changeState(State.Stopping)
            val originalMessage = msg

            runOnMainDispatcher {
                var cleanupError: Throwable? = null
                fun recordCleanupFailure(stage: String, error: Throwable) {
                    if (cleanupError == null) {
                        cleanupError = error
                    } else if (cleanupError !== error) {
                        cleanupError?.addSuppressed(error)
                    }
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=$stage failed type=${error.javaClass.name} " +
                            "message=${error.message}"
                    )
                }

                try {
                    data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                } catch (error: Throwable) {
                    recordCleanupFailure("connecting-job-cancel", error)
                } finally {
                    data.connectingJob = null
                }

                // A start that already came in keeps the notification too, so it needs no second startForeground.
                val keepNotification = (restart || data.startRequested) && data.notification != null
                if (keepNotification) {
                    try {
                        data.notification?.postNotificationTitle(getString(R.string.notification_switching))
                    } catch (error: Throwable) {
                        recordCleanupFailure("notification-title", error)
                    }
                } else {
                    try {
                        data.notification?.destroy()
                    } catch (error: Throwable) {
                        recordCleanupFailure("notification-destroy", error)
                    } finally {
                        data.notification = null
                    }
                }

                // An in-place restart builds the next core first and keeps the interface while it starts.
                val discarded = ArrayList<ProxyInstance>()
                var deferRuleSets: Boolean? = null
                var next: ProxyInstance? = null
                if (restart && keepNotification) {
                    try {
                        val consent = DeferredRuleSets.consume()
                        deferRuleSets = consent
                        next = prepareRestart(consent, discarded)
                    } catch (error: Throwable) {
                        recordCleanupFailure("next-build", error)
                    }
                }
                DataStore.baseService = null
                DataStore.vpnService = null
                try {
                    killProcesses(keepTun = next != null)?.let { recordCleanupFailure("process-cleanup", it) }
                } catch (error: Throwable) {
                    recordCleanupFailure("process-cleanup-boundary", error)
                }
                // The old core's traffic counter wrote its last bytes as it stopped, after the next core read its
                // profiles: their totals are read again, or the next counter would start below the stored ones.
                next?.let { prepared ->
                    try {
                        withContext(Dispatchers.IO) {
                            val entities = prepared.trafficMap.values.flatten()
                            val stored = SagerDatabase.proxyDao.getEntities(entities.map { it.id }.distinct())
                                .associateBy { it.id }
                            for (entity in entities) stored[entity.id]?.let {
                                entity.rx = it.rx
                                entity.tx = it.tx
                            }
                        }
                    } catch (error: Throwable) {
                        recordCleanupFailure("next-traffic", error)
                    }
                }

                // A stop, reload or selection that came meanwhile: the built core is not what should run. A fresh
                // start replaces the kept interface (or a stop closes it); nothing suspends after the last check.
                while (true) {
                    next?.takeIf { data.stopRequested || data.restartAgain || it.profile.id != DataStore.selectedProxy }
                        ?.let {
                            discarded += it
                            next = null
                        }
                    if (discarded.isEmpty()) break
                    for (instance in discarded) {
                        try {
                            withContext(coreDispatcher) { instance.close() }
                        } catch (error: Throwable) {
                            recordCleanupFailure("next-close", error)
                        }
                    }
                    discarded.clear()
                }
                val stopInstead = restart && data.stopRequested
                val startInstead = data.startRequested
                data.stopRequested = false
                data.restartAgain = false
                data.startRequested = false
                val finishRestart = restart && !stopInstead
                // A start asked for meanwhile runs in place: on the kept notification or on the one it put up itself.
                val startInPlace = startInstead || (finishRestart && data.notification != null)

                if (!startInPlace) {
                    // Crown (touch point 10): nothing starts on the interface a restart kept
                    releaseRetiredTun()
                    try {
                        data.notification?.destroy()
                    } catch (error: Throwable) {
                        recordCleanupFailure("notification-destroy", error)
                    } finally {
                        data.notification = null
                    }
                    try {
                        if (data.closeReceiverRegistered) {
                            unregisterReceiver(data.receiver)
                        }
                    } catch (error: Throwable) {
                        recordCleanupFailure("receiver-unregister", error)
                    } finally {
                        data.closeReceiverRegistered = false
                    }
                    PlatformNotifications.cancelWifiRulesInactive(this@Interface)
                }
                data.proxy = null

                cleanupError?.let { error ->
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=cleanup failed type=${error.javaClass.name} " +
                            "message=${error.message} suppressed=${error.suppressed.size} " +
                            "originalMessagePreserved=${originalMessage != null}"
                    )
                }

                io.nekohasekai.sagernet.simple.SimpleHooks.onServiceStopped(finishRestart)
                try {
                    data.changeState(State.Stopped, originalMessage)
                } catch (error: Throwable) {
                    recordCleanupFailure("state-stopped", error)
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=stopped restart=$restart startInstead=$startInstead " +
                        "hasCleanupError=${cleanupError != null}"
                )
                if (!finishRestart) {
                    DeferredRuleSets.serviceStopped()
                    AutoSelectorRuntime.serviceStopped()
                }

                try {
                    when {
                        startInPlace -> startProxy(next, deferRuleSets)
                        finishRestart -> startRunner()
                        else -> stopSelf() // stop the service if nothing has bound to it
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("service-finish", error)
                    if (startInPlace) {
                        failRunner("${getString(R.string.service_failed)} ${error.readableMessage}")
                    }
                }
            }
        }

        /**
         * A start that failed: MainActivity keeps showing [message] until the next start, also on a later visit, and
         * offers the DNS settings with it when [dnsSettings], the Xray geo asset settings when [geoSettings], a start
         * without the rule-sets when [ruleSets].
         */
        fun failRunner(message: String, dnsSettings: Boolean = false, geoSettings: Boolean = false, ruleSets: Boolean = false) {
            io.nekohasekai.sagernet.simple.ServiceRestore.onStartFailed(this as Context, message)
            if (data.state != State.Stopping) {
                DataStore.serviceError = message
                DataStore.serviceErrorDns = dnsSettings
                DataStore.serviceErrorGeo = geoSettings
                DataStore.serviceErrorRuleSets = ruleSets
            }
            stopRunner(false, message)
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            // Tracks the underlying network for VpnService.setUnderlyingNetworks only; the
            // "reset on network change" switch is applied by NativeInterface's monitor callback.
            DefaultNetworkListener.start(this) { network ->
                if (network == null) {
                    SagerNet.underlyingNetwork = null
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    return@start
                }
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        Logs.d("Network changed: $oldName -> ${link.interfaceName}")
                        upstreamInterfaceName = link.interfaceName
                    }
                }
            }
        }

        suspend fun lateInit() {
            data.notification?.postConnected()
        }

        /** Wi-Fi rules (route, DNS or rule-set ones) need location access and a fresh Wi-Fi state on roaming. */
        fun watchWifiRules(proxy: ProxyInstance) {
            this as Context
            val box = proxy.boxOrNull ?: return
            if (!box.needWIFIState()) {
                PlatformNotifications.cancelWifiRulesInactive(this)
                return
            }
            data.wifiMonitor = WifiStateAccess.Monitor(this) { box.updateWIFIState() }.also { it.start() }
            if (WifiStateAccess.status(this) == WifiStateAccess.Status.OK) {
                PlatformNotifications.cancelWifiRulesInactive(this)
            } else {
                PlatformNotifications.wifiRulesInactive(this)
            }
        }

        /** Always-on VPN starts the service by itself; without a profile it can only explain why nothing connects. */
        fun onNoProfile() {}

        /**
         * Crown (touch point 12): sticky while the user wants the connection; a restart by the system after the process
         * died (a null intent) starts the selected profile again, or stops when that is not wanted or not possible.
         */
        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            // Crown (touch point 21): no database, no start; the process stops without writing anything
            if (DataGate.refuseStart(this as Service)) return Service.START_NOT_STICKY
            DataStore.baseService = this
            val restore = io.nekohasekai.sagernet.simple.ServiceRestore

            val restart = restore.isRestart(intent) // the system's restart (null intent) or the restore watchdog's
            if (restart && data.state == State.Stopped && !restore.onSystemRestart(this as Service)) {
                restore.leaveForeground(this as Service) // nothing of the dead process may stay shown as connected
                stopRunner(false, restore.takeReason())
                return Service.START_NOT_STICKY
            }
            if (restart && data.state == State.Stopping && data.notification == null) {
                // A second restore (the watchdog's after the system's) while that one stops: its foreground start too.
                restore.leaveForeground(this as Service)
                return Service.START_NOT_STICKY
            }
            // A start that comes while a stop is in progress ends that stop in a start (below), so it marks it wanted too.
            if (!restart) restore.onStartRequested(this as Context, intent)
            when (data.state) {
                State.Stopped -> startProxy()
                // startForeground now, as this start requires; the stop then ends in a start instead of stopSelf.
                State.Stopping -> if (!restart) {
                    if (data.notification == null) {
                        val title = (this as Context).getString(R.string.notification_switching)
                        data.notification = createNotification(title)
                    }
                    data.stopRequested = false
                    data.startRequested = true
                }

                else -> {}
            }
            return restore.startResult()
        }

        /**
         * Starts the selected profile, or [prepared], the one a restart built already; the notification and the receiver
         * of a restart are reused. [deferRuleSets]: the consent a restart has taken already.
         */
        fun startProxy(prepared: ProxyInstance? = null, deferRuleSets: Boolean? = null) {
            DataStore.baseService = this
            val data = data
            val profile = prepared?.profile ?: SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                if (data.notification == null) data.notification = createNotification("")
                onNoProfile()
                stopRunner(false, getString(R.string.profile_empty))
                return
            }
            PlatformNotifications.cancelAlwaysOnNoProfile(this)

            val proxy = prepared ?: ProxyInstance(profile, this).also {
                it.deferRuleSets = deferRuleSets ?: DeferredRuleSets.consume()
            }
            data.proxy = proxy
            BootReceiver.enabled = DataStore.rememberEnable
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.SWITCH_NEXT)
                    addAction(Action.SWITCH_PREVIOUS)
                    addAction(Action.SWITCH_PROFILE)
                    addAction(Action.REFRESH_WIFI_STATE)
                    addAction(Action.REFRESH_NOTIFICATION)
                    addAction(Action.AUTO_SELECTOR_AUTOMATIC)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            if (DataStore.serviceError.isNotEmpty()) {
                DataStore.serviceError = ""
                DataStore.serviceErrorDns = false
                DataStore.serviceErrorGeo = false
                DataStore.serviceErrorRuleSets = false
            }
            data.changeState(State.Connecting)
            // startForeground before anything can stop the service (see the link above).
            val title = ServiceNotification.genTitle(profile)
            val notification = data.notification
            if (notification == null) {
                data.notification = createNotification(title)
            } else {
                runOnMainDispatcher { notification.refresh(title) }
            }
            data.connectingJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    preInit()
                    if (prepared == null) withContext(coreDispatcher) { proxy.init() }
                    DataStore.currentProfile = profile.id
                    DataStore.runningProfiles = proxy.config.involvedProfileIds.map { it.toString() }
                    DataStore.runningRouteId = proxy.routeId

                    withContext(coreDispatcher) { startProcesses() }
                    releaseRetiredTun() // a config without a tun inbound never replaces it
                    io.nekohasekai.sagernet.simple.SimpleHooks.onServiceStarted()
                    data.changeState(State.Connected)
                    runCatching { watchWifiRules(proxy) }.onFailure { Logs.w(it) }

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    failRunner(getString(R.string.invalid_server))
                } catch (exc: LocalDnsFailedException) {
                    failRunner(
                        if (exc.servers.isEmpty()) getString(R.string.local_dns_failed_system)
                        else getString(R.string.local_dns_failed, exc.servers),
                        dnsSettings = true,
                    )
                } catch (exc: RuleSetDownloadFailedException) {
                    failRunner(
                        getString(R.string.rule_set_deferred_failed, DeferredRuleSets.describe(exc.readableMessage)),
                        ruleSets = true,
                    )
                } catch (exc: XrayGeoAssets.DownloadException) {
                    failRunner(
                        if (exc.fetchable) getString(R.string.xray_geo_start_download_failed, exc.readableMessage)
                        else exc.readableMessage,
                        geoSettings = true,
                    )
                } catch (exc: Throwable) {
                    // gomobile surfaces Go errors as go.Universe$proxyerror: message only, no stack worth logging
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    val geoFailure = XrayGeoAssets.describeFailure(exc.readableMessage, profile.displayName())
                    if (geoFailure != null) {
                        failRunner(geoFailure, geoSettings = true)
                    } else {
                        failRunner("${getString(R.string.service_failed)} ${exc.readableMessage}")
                    }
                } finally {
                    data.connectingJob = null
                }
            }
        }
    }

}
