package io.nekohasekai.sagernet.simple.config

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.fetchText
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.route.RuleSets
import java.io.File
import java.io.IOException

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
    private var cache: Pair<Long, SimpleConfig>? = null

    private val storedFile: File get() = File(File(app.filesDir, DIR), FILE)

    /** The configuration in effect; read once per change of the stored copy. */
    fun current(): SimpleConfig {
        val file = storedFile
        val stamp = if (file.isFile) file.lastModified() else NO_FILE
        cache?.let { if (it.first == stamp) return it.second }
        val config = SimpleConfig.choose(bundled(), if (stamp != NO_FILE) readStored(file) else null)
        cache = stamp to config
        return config
    }

    /**
     * Downloads the configuration through the rule-set mirror and keeps it when it is valid and not older than the
     * one in effect. Returns the error, or null. Blocking: call it off the main thread.
     */
    fun refresh(): String? {
        val text = try {
            fetchText(RuleSets.mirrorLink(SOURCE_URL, DataStore.rulesetMirror)).body
        } catch (e: Exception) {
            return "simple config: ${e.readableMessage}"
        }
        val downloaded = try {
            SimpleConfig.parse(text)
        } catch (e: SimpleConfig.InvalidConfigException) {
            return "simple config refused: ${e.message}"
        }
        synchronized(lock) {
            if (downloaded.version < current().version) return null
            try {
                writeAtomically(storedFile, text)
            } catch (e: IOException) {
                return "simple config: ${e.readableMessage}"
            }
            cache = null
        }
        return null
    }

    private fun bundled(): SimpleConfig = try {
        SimpleConfig.parse(app.assets.open(ASSET).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        // A unit test keeps the bundled file valid; this only guards against a broken build.
        Logs.e("simple config: bundled copy unusable", e)
        throw IllegalStateException("bundled simple/config.json is unusable", e)
    }

    private fun readStored(file: File): SimpleConfig? = try {
        SimpleConfig.parse(file.readText())
    } catch (e: Exception) {
        Logs.w("simple config: stored copy refused: ${e.message}")
        null
    }

    private fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("cannot write ${file.name}")
        }
    }

    private const val NO_FILE = -1L
}
