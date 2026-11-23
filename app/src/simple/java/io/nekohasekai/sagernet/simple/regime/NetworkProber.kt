package io.nekohasekai.sagernet.simple.regime

import android.net.Network
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.config.SimpleConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * The requests of a network check (design.md D6): HTTPS `HEAD` to every configured address, all at once, through the
 * given physical [Network] (its socket factory), so the VPN does not carry them. Only the fixed address, a neutral
 * User-Agent and the standard headers go out: no cookies, no cache, no identifiers (traffic-modes "Приватность
 * проверок"). Any HTTP answer means reachable; redirects are not followed, so nothing but the configured address is
 * asked.
 *
 * Names are looked up with the network's own DNS; when that gives no answer quickly, with the whitelisted DNS servers
 * of the configuration (`whitelistDns`, plain ones only) over the same network, so a network whose own DNS is broken
 * is not taken for one without internet. A whitelisted network that also filters DNS answers is still told apart: the
 * blocked addresses then fail at their connection instead of at the lookup.
 *
 * Also meant for «Починка» (batch 7), which checks the same way in the UI process.
 */
object NetworkProber {

    /** Connect and read timeout of one request (design.md D6). */
    const val TIMEOUT_MS = 4_000L

    /** How long the network's own DNS may take before the whitelisted servers are asked. */
    const val SYSTEM_DNS_WAIT_MS = 1_500L

    /** One fallback DNS question waits this long for its answer. */
    const val FALLBACK_DNS_WAIT_MS = 1_500

    /** One request, the name lookup included, ends after this at the latest. */
    const val DEADLINE_MS = 8_000L

    /** Not OkHttp's default, which names the library and its version. */
    private const val USER_AGENT = "Mozilla/5.0"

    private val counter = AtomicInteger()

    /** Lookups block; one that hangs keeps its thread until the system gives up on it, never a caller. */
    private val lookups = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "simple-probe-dns-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val baseClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            // Lets a request go on to the next address of a name when one refuses; each request still ends at
            // [DEADLINE_MS] (its call is cancelled then), and redirects stay off.
            .retryOnConnectionFailure(true)
            .build()
    }

    /** The name lookup of one request takes at most this, so the connection keeps time within [DEADLINE_MS]. */
    const val LOOKUP_BUDGET_MS = 4_000L

    /** At most this many plain `whitelistDns` servers are asked by one lookup. */
    const val MAX_FALLBACK_SERVERS = 4

    data class Result(
        val allowed: List<ProbeOutcome>,
        val blocked: List<ProbeOutcome>,
        val elapsedMs: Long,
        /** The network was gone by the end of the check (Android no longer has its link). */
        val linkLost: Boolean = false,
    ) {
        val regime: Regime get() = RegimeClassifier.classify(allowed, blocked)

        /** The network went away during the check: the answers are partial and prove nothing. */
        val inconclusive: Boolean get() = linkLost || RegimeClassifier.inconclusive(allowed, blocked)

        /** Whitelists found while some allowed address did not answer either: worth a second look before applying. */
        val partial: Boolean get() = regime == Regime.WHITELIST && allowed.any { !it.reachable }

        fun summary(): String {
            val counts = "allowed ${allowed.count { it.reachable }}/${allowed.size}, " +
                "blocked ${blocked.count { it.reachable }}/${blocked.size} reachable"
            val gone = (allowed + blocked).count { it == ProbeOutcome.NETWORK_GONE }
            return when {
                gone > 0 -> "$counts, $gone failed as the network went away"
                linkLost -> "$counts, the network was gone at the end"
                else -> counts
            }
        }
    }

    /** Checks [network] with the addresses of [config]; cancelling it aborts the requests. */
    suspend fun probe(network: Network, config: SimpleConfig): Result {
        val servers = config.whitelistDns.mapNotNull { DnsMessage.plainServer(it) }.take(MAX_FALLBACK_SERVERS)
        val dns = FallbackDns(network, servers)
        // A dispatcher of its own: calls of a cancelled check never hold the threads of a later one.
        val client = baseClient.newBuilder()
            .dispatcher(Dispatcher())
            .connectionPool(ConnectionPool())
            .socketFactory(network.socketFactory)
            .dns(dns)
            .build()
        val start = System.nanoTime()
        try {
            return coroutineScope {
                val allowed = config.probesAllowed.map { async { request(client, it) } }
                val blocked = config.probesBlocked.map { async { request(client, it) } }
                Result(
                    allowed.awaitAll(),
                    blocked.awaitAll(),
                    (System.nanoTime() - start) / 1_000_000,
                    linkLost = SagerNet.connectivity.getLinkProperties(network) == null,
                )
            }
        } finally {
            // Done or cancelled: lookups still running stop asking and their sockets close, calls are cancelled.
            dns.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun request(client: OkHttpClient, address: String): ProbeOutcome =
        withTimeoutOrNull(DEADLINE_MS) {
            suspendCancellableCoroutine { continuation ->
                val call = client.newCall(
                    Request.Builder().url(address).head().header("User-Agent", USER_AGENT).build()
                )
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                        if (continuation.isActive) continuation.resume(ProbeOutcome.REACHABLE)
                    }

                    override fun onFailure(call: Call, e: IOException) {
                        val outcome = when {
                            ProbeFailures.isNetworkGone(e) -> ProbeOutcome.NETWORK_GONE
                            e is SocketTimeoutException -> ProbeOutcome.TIMEOUT
                            else -> ProbeOutcome.UNREACHABLE
                        }
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                })
            }
        } ?: ProbeOutcome.TIMEOUT

    /**
     * The network's own DNS first, then the whitelisted plain DNS servers, all through [network]. One lookup takes at
     * most [LOOKUP_BUDGET_MS]; [close] (the check ended or was cancelled) stops every lookup still asking and closes
     * its socket.
     */
    private class FallbackDns(private val network: Network, private val servers: List<Pair<String, Int>>) : Dns {

        @Volatile
        private var closed = false
        private val sockets = mutableSetOf<DatagramSocket>()

        /** The record types to ask for, from the addresses the network has (both when it cannot be told). */
        private val families: IntArray by lazy {
            val addresses = SagerNet.connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            if (addresses == null) {
                DnsMessage.families(hasIpv4 = true, hasGlobalIpv6 = true)
            } else {
                DnsMessage.families(
                    hasIpv4 = addresses.any { it is Inet4Address },
                    hasGlobalIpv6 = addresses.any { it is Inet6Address && DnsMessage.isGlobalIpv6(it.address) },
                )
            }
        }

        fun close() {
            closed = true
            synchronized(sockets) {
                sockets.forEach { it.close() }
                sockets.clear()
            }
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val deadline = SystemClock.elapsedRealtime() + LOOKUP_BUDGET_MS
            val system = lookups.submit(Callable { network.getAllByName(hostname).toList() })
            try {
                val found = system.get(SYSTEM_DNS_WAIT_MS, TimeUnit.MILLISECONDS)
                if (found.isNotEmpty()) return found
            } catch (e: Exception) {
                system.cancel(true)
                Logs.d("simple regime: the network's DNS gave no answer for $hostname: ${e.javaClass.simpleName}")
            }
            var gone: IOException? = null
            for (server in servers) {
                // The families the network can reach (an IPv6-only network gets its AAAA answers; IPv6 that is only
                // link- or site-local is no reason to try AAAA addresses, which would hang to the deadline).
                // A timed-out A question does not keep the AAAA one from being asked (DnsMessage.askFamilies).
                val found = DnsMessage.askFamilies(
                    families,
                    left = { if (closed) 0L else deadline - SystemClock.elapsedRealtime() },
                    ask = { type, left -> ask(server, hostname, type, left) },
                    failed = { e ->
                        if (ProbeFailures.isNetworkGone(e)) gone = e
                        Logs.d("simple regime: DNS ${server.first} gave no answer for $hostname: ${e.javaClass.simpleName}")
                    },
                )
                if (found.isNotEmpty()) return found
                if (closed || SystemClock.elapsedRealtime() >= deadline) break
            }
            // Carries the reason, so the request tells a network that went away from a name that is not found.
            throw UnknownHostException(hostname).apply { gone?.let { initCause(it) } }
        }

        private fun ask(server: Pair<String, Int>, hostname: String, type: Int, left: Long): List<InetAddress> {
            DatagramSocket().use { socket ->
                synchronized(sockets) {
                    if (closed) throw IOException("the check ended")
                    sockets += socket
                }
                try {
                    network.bindSocket(socket)
                    socket.soTimeout = left.coerceIn(1L, FALLBACK_DNS_WAIT_MS.toLong()).toInt()
                    val id = Random.nextInt(0x10000)
                    val query = DnsMessage.query(id, hostname, type)
                    val target = InetSocketAddress(InetAddress.getByName(server.first), server.second)
                    socket.send(DatagramPacket(query, query.size, target))
                    val buffer = ByteArray(1500)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    return DnsMessage.addresses(buffer, packet.length, id).map { InetAddress.getByAddress(hostname, it) }
                } finally {
                    synchronized(sockets) { sockets -= socket }
                }
            }
        }
    }
}
