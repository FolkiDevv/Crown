package io.nekohasekai.sagernet.simple.config

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.fetchText
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.DnsServers
import io.nekohasekai.sagernet.route.RuleSets
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Where [SimpleConfig] comes from (design.md D7), modelled on RouteRepo: the bundled `assets/simple/config.json`,
 * and a downloaded copy in `filesDir/simple/` that wins only when its `version` is newer. [refresh] runs next to the
 * route profile update (touch point 7 in RemoteRouteUpdater) through the same rule-set mirror; a damaged or incomplete
 * download is refused and the previous copy stays.
 */
object SimpleConfigRepo {

    private const val ASSET = "simple/config.json"
    private const val DIR = "simple"
    private const val FILE = "config.json"

    /** This repository's copy on its default branch, so the fork owner can update the lists without a release. */
    const val SOURCE_URL =
        "https://raw.githubusercontent.com/FolkiDevv/ThroneForAndroid/main/app/src/simple/assets/simple/config.json"

    private val lock = Any()

    @Volatile
    private var cache: Pair<Pair<Long, Long>, SimpleConfig>? = null

    private val storedFile: File get() = File(File(app.filesDir, DIR), FILE)

    /** The configuration in effect; read once per change of the stored copy. */
    fun current(): SimpleConfig {
        val file = storedFile
        val stamp = stampOf(file)
        cache?.let { if (it.first == stamp) return it.second }
        val config = SimpleConfig.choose(bundled(), if (stamp != NO_FILE) readStored(file) else null)
        cache = stamp to config
        return config
    }

    /**
     * Downloads the configuration through the rule-set mirror and keeps it when it is valid and not older than the
     * one in effect. Returns the error, or null. Blocking: call it off the main thread.
     *
     * Both processes can refresh (the UI process and the `:bg` route updater, each with its own throttle), so the
     * version check and the replacement run under a file lock in `filesDir/simple/` that spans processes, and each
     * writer uses its own temporary file: a slower, older download can never replace a newer one.
     */
    fun refresh(): String? {
        val text = try {
            fetchText(RuleSets.mirrorLink(SOURCE_URL, DataStore.rulesetMirror)).body
        } catch (e: Exception) {
            return "simple config: ${e.readableMessage}"
        }
        val downloaded = try {
            SimpleConfig.parse(text, ::dnsAccepted)
        } catch (e: SimpleConfig.InvalidConfigException) {
            return "simple config refused: ${e.message}"
        }
        val file = storedFile
        return try {
            file.parentFile?.mkdirs()
            synchronized(lock) {
                RandomAccessFile(File(file.parentFile, LOCK_FILE), "rw").use { raf ->
                    raf.channel.lock().use {
                        // Read the stored copy itself, not the cache: the other process may have just replaced it.
                        val inEffect = SimpleConfig.choose(bundled(), if (file.isFile) readStored(file) else null)
                        if (downloaded.version >= inEffect.version) {
                            writeAtomically(file, text)
                            cache = null
                        }
                    }
                }
            }
            null
        } catch (e: IOException) {
            "simple config: ${e.readableMessage}"
        }
    }

    /** What the route DNS settings accept: the whitelisted DNS servers end up as a routing profile's direct DNS. */
    private fun dnsAccepted(address: String): Boolean = DnsServers.parseDnsAddress(address).address != null

    private fun bundled(): SimpleConfig = try {
        SimpleConfig.parse(app.assets.open(ASSET).bufferedReader().use { it.readText() }, ::dnsAccepted)
    } catch (e: Exception) {
        // A unit test keeps the bundled file valid; this only guards against a broken build.
        Logs.e("simple config: bundled copy unusable", e)
        throw IllegalStateException("bundled simple/config.json is unusable", e)
    }

    private fun readStored(file: File): SimpleConfig? = try {
        SimpleConfig.parse(file.readText(), ::dnsAccepted)
    } catch (e: Exception) {
        Logs.w("simple config: stored copy refused: ${e.message}")
        null
    }

    /** A temporary file of this writer only, renamed over [file]; callers hold the file lock. */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) throw IOException("cannot write ${file.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Modification time and size, so a replacement within the file system's time granularity is still noticed. */
    private fun stampOf(file: File): Pair<Long, Long> =
        if (file.isFile) file.lastModified() to file.length() else NO_FILE

    private const val LOCK_FILE = "config.lock"
    private val NO_FILE = -1L to -1L
}
