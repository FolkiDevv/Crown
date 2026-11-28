package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.IpPrefix
import android.net.ProxyInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.RoutePrefix
import io.throneproj.mobile.RoutePrefixIterator
import io.throneproj.mobile.StringIterator
import io.throneproj.mobile.TunOptions
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(),
    BaseService.Interface {

    companion object {

        const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        const val FAKEDNS_VLAN4_CLIENT = "198.18.0.0"
        const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

        /** How long [openTun] lets Android drop a closed interface's network before the next establish(). */
        private const val HANDOVER_SETTLE_MS = 500L

    }

    var conn: ParcelFileDescriptor? = null

    /** What [conn] was built from ([tunSpec]). */
    private var connSpec: TunSpec? = null

    private class Retired(val fd: ParcelFileDescriptor, val spec: TunSpec?)

    /**
     * The interface of the core a restart replaced: it stays the VPN network until the next core takes it over (the
     * same interface when its configuration is unchanged) or establish() replaces it, so nothing leaves around the
     * tunnel meanwhile. Closed then, or by [releaseRetiredTun] when no new one comes.
     */
    private val retiredConn = AtomicReference<Retired?>()

    private var metered = false

    override var upstreamInterfaceName: String? = null

    override suspend fun startProcesses() {
        DataStore.vpnService = this
        super.startProcesses() // launch proxy instance
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override suspend fun killProcesses(keepTun: Boolean): Throwable? {
        val currentConnection = conn
        var cleanupError: Throwable? = null
        // A changed metered flag, or a listed package installed, removed or reinstalled since, needs a new network
        // that a handover would not make (TunSpec.needsNewNetwork): the interface closes first, as a plain restart.
        // A failed read counts as changed; the package lookups are binder calls, so off the main thread.
        val spec = connSpec
        val keep = keepTun && (spec == null || withContext(Dispatchers.IO) {
            runCatching {
                !spec.needsNewNetwork(spec.now(DataStore.meteredNetwork, ::uidOf), meteredApplies())
            }.getOrDefault(false)
        })
        Logs.i(
            "VpnLifecycleTrace stage=tun-close begin " +
                "hasConnection=${currentConnection != null} keepTun=$keepTun keep=$keep"
        )
        try {
            if (keep) {
                currentConnection?.let { retiredConn.getAndSet(Retired(it, connSpec))?.fd?.close() }
                Logs.i("VpnLifecycleTrace stage=tun-close deferred")
            } else {
                releaseRetiredTun()
                currentConnection?.close()
                Logs.i("VpnLifecycleTrace stage=tun-close success")
            }
        } catch (error: Throwable) {
            Logs.w(
                "VpnLifecycleTrace stage=tun-close failed " +
                    "type=${error.javaClass.name} message=${error.message}"
            )
            cleanupError = error
        } finally {
            conn = null
        }
        super.killProcesses(keepTun)?.let { error ->
            if (cleanupError == null) {
                cleanupError = error
            } else if (cleanupError !== error) {
                cleanupError?.addSuppressed(error)
            }
        }
        Logs.i(
            "VpnLifecycleTrace stage=kill done hasCleanupError=${cleanupError != null}"
        )
        return cleanupError
    }

    override fun releaseRetiredTun() {
        val retired = retiredConn.getAndSet(null) ?: return
        try {
            retired.fd.close()
            Logs.i("VpnLifecycleTrace stage=retired-tun-close success")
        } catch (error: Throwable) {
            Logs.w("VpnLifecycleTrace stage=retired-tun-close failed message=${error.message}")
        }
    }

    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    private fun isAlwaysOnVpn(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching { isAlwaysOn }.getOrDefault(false)

    override fun onNoProfile() {
        if (isAlwaysOnVpn()) PlatformNotifications.alwaysOnNoProfile(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system starts an always-on VPN whatever the service mode says; the rest of the app follows the mode.
        if (DataStore.serviceMode != Key.MODE_VPN && isAlwaysOnVpn()) DataStore.serviceMode = Key.MODE_VPN
        // Crown (touch point 12): a restart after the process died is in the background, no activity may start.
        val restore = io.nekohasekai.sagernet.simple.ServiceRestore
        val restart = restore.isRestart(intent)
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                if (restart) restore.onConsentMissing(this)
                else startActivity(
                    Intent(
                        this, VpnRequestActivity::class.java
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        if (restart) restore.leaveForeground(this)
        stopRunner(false, if (restart) restore.takeReason() else null)
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(),
        BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    // The generated tun inbound is the only source of truth for the interface layout.
    fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing VPN permission")
        // Read once: the spec below must describe exactly what the builder gets.
        val bypass = DataStore.allowBypass
        val meteredNow = DataStore.meteredNetwork
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(getString(R.string.app_name))
            .setMtu(options.getMTU())

        options.getInet4Address().forEach { builder.addAddress(it.address(), it.prefix()) }
        options.getInet6Address().forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.getAutoRoute()) {
            if (options.getDNSMode().value != Mobile.DNSModeDisabled) {
                options.getDNSServerAddress().toList().forEach { builder.addDnsServer(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // A family gets its default route only when the tun has an address of it: without any address or
                // route of a family (and no allowFamily call) Android blocks that family, so IPv6 fails fast
                // instead of vanishing into a tun that has no IPv6 address (the core does the same below API 33).
                val inet4RouteAddress = options.getInet4RouteAddress()
                if (inet4RouteAddress.hasNext()) {
                    inet4RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
                } else if (options.getInet4Address().hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val inet6RouteAddress = options.getInet6RouteAddress()
                if (inet6RouteAddress.hasNext()) {
                    inet6RouteAddress.forEach { builder.addRoute(it.address(), it.prefix()) }
                } else if (options.getInet6Address().hasNext()) {
                    builder.addRoute("::", 0)
                }
                // Builder.check rejects a loopback prefix ("Bad address"), and loopback never enters the tun anyway.
                options.getInet4RouteExcludeAddress().forEach {
                    val address = InetAddress.getByName(it.address())
                    if (!address.isLoopbackAddress) builder.excludeRoute(IpPrefix(address, it.prefix()))
                }
                options.getInet6RouteExcludeAddress().forEach {
                    val address = InetAddress.getByName(it.address())
                    if (!address.isLoopbackAddress) builder.excludeRoute(IpPrefix(address, it.prefix()))
                }
            } else {
                // Builder.excludeRoute only exists from API 33; below that the core pre-splits
                // "everything minus the excludes" into plain ranges that replace the default routes.
                options.getInet4RouteRange().forEach { builder.addRoute(it.address(), it.prefix()) }
                options.getInet6RouteRange().forEach { builder.addRoute(it.address(), it.prefix()) }
            }
        }

        val allowed = ArrayList<String>()
        val includePackage = options.getIncludePackage()
        if (includePackage.hasNext()) {
            includePackage.toList().forEach {
                try {
                    builder.addAllowedApplication(it)
                    allowed += it
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            // None of the chosen apps is installed: an empty allow list would capture every app instead of none.
            if (allowed.isEmpty()) builder.addAllowedApplication(packageName).also { allowed += packageName }
        }
        val denied = ArrayList<String>()
        val excludePackage = options.getExcludePackage()
        if (excludePackage.hasNext()) {
            excludePackage.toList().forEach {
                try {
                    builder.addDisallowedApplication(it)
                    denied += it
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.isHTTPProxyEnabled()) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.getHTTPProxyServer(),
                    options.getHTTPProxyServerPort(),
                    options.getHTTPProxyBypassDomain().toList().filter { it.isNotBlank() },
                )
            )
        }

        // Apps may then bind sockets to the underlying network themselves; FCM does this for its push channel.
        if (bypass) builder.allowBypass()
        updateUnderlyingNetwork(builder)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(meteredNow)
        // A restart whose interface would come out the same keeps it: establish() would make Android reset the old
        // interface before the new one has its routes (Vpn.establish: jniReset after an async LinkProperties update),
        // and for that moment apps reach the network around the VPN. The builder is then dropped unused.
        val spec = tunSpec(options, allowed, denied, bypass, meteredNow)
        val retired = retiredConn.get()
        if (retired != null) {
            Logs.i("VpnLifecycleTrace stage=tun-compare changed=${spec.diff(retired.spec)} ${spec.appCounts()}")
        }
        if (retired != null && retired.spec == spec && retiredConn.compareAndSet(retired, null)) {
            conn = retired.fd
            connSpec = spec
            updateUnderlyingNetwork()
            Logs.i("VpnLifecycleTrace stage=tun-reuse")
            return retired.fd.fd
        }
        // Changed after killProcesses kept the interface: close it first here too (see killProcesses). Android learns
        // of the closed interface asynchronously (netd), and an establish() before that would still hand the network
        // over: give it a moment, as the old core's close does on the usual path. Rare: a change in that short window.
        if (retired?.spec?.needsNewNetwork(spec, meteredApplies()) == true) {
            releaseRetiredTun()
            Thread.sleep(HANDOVER_SETTLE_MS)
        }
        val connection = builder.establish() ?: throw NullConnectionException()
        conn = connection
        connSpec = spec
        metered = meteredNow
        // Android has already reset the old interface inside establish(): keeping its fd longer changes nothing.
        releaseRetiredTun()
        // The core dups this fd and closes only its own copy (core/mobile/service.go OpenInterface): conn alone
        // holds the interface, so it goes down exactly when conn is closed.
        return connection.fd
    }

    private fun meteredApplies() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun uidOf(name: String): Int? = try {
        packageManager.getPackageUid(name, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** The [TunSpec] of what the builder got: the app lists as added, each package with its uid now. */
    private fun tunSpec(
        options: TunOptions, allowed: List<String>, denied: List<String>, bypass: Boolean, metered: Boolean,
    ): TunSpec {
        // Reads exactly what openTun reads, under the same conditions: some getters are unsafe where the builder
        // skips them (the DNS address without DNS derivation).
        fun routes(prefixes: RoutePrefixIterator) = buildList { prefixes.forEach { add(it.address() to it.prefix()) } }
        val autoRoute = options.getAutoRoute()
        val dnsMode = if (autoRoute) options.getDNSMode().value else null
        val proxy = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && options.isHTTPProxyEnabled()
        return TunSpec(
            mtu = options.getMTU(), autoRoute = autoRoute, dnsMode = dnsMode,
            dns = if (autoRoute && dnsMode != Mobile.DNSModeDisabled) {
                options.getDNSServerAddress().toList()
            } else {
                emptyList()
            },
            routes = listOf(routes(options.getInet4Address()), routes(options.getInet6Address())) + when {
                !autoRoute -> emptyList()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                    routes(options.getInet4RouteAddress()), routes(options.getInet6RouteAddress()),
                    routes(options.getInet4RouteExcludeAddress()), routes(options.getInet6RouteExcludeAddress()),
                )
                else -> listOf(routes(options.getInet4RouteRange()), routes(options.getInet6RouteRange()))
            },
            allowed = TunSpec.apps(allowed, ::uidOf), denied = TunSpec.apps(denied, ::uidOf),
            httpProxy = if (proxy) {
                listOf(
                    options.getHTTPProxyServer(), options.getHTTPProxyServerPort(),
                    options.getHTTPProxyBypassDomain().toList(),
                )
            } else {
                null
            },
            bypass = bypass, metered = metered,
        )
    }

    fun updateUnderlyingNetwork(builder: Builder? = null) {
        val networks = SagerNet.underlyingNetwork?.let { arrayOf(it) }
        if (builder != null) {
            if (networks != null) builder.setUnderlyingNetworks(networks)
        } else {
            // null once the network is lost: the VPN follows the system default instead of declaring a gone network.
            setUnderlyingNetworks(networks)
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        releaseRetiredTun()
        DataStore.vpnService = null
        super.onDestroy()
        data.binder.close()
    }
}

private inline fun RoutePrefixIterator.forEach(block: (RoutePrefix) -> Unit) {
    while (hasNext()) block(next())
}

private fun StringIterator.toList(): List<String> {
    val values = ArrayList<String>()
    while (hasNext()) values.add(next())
    return values
}
