package io.nekohasekai.sagernet.simple.repair

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import moe.matsuri.nb4a.utils.CoreLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The «Починка» report as one archive (design.md D8, connection-repair "Экспорт отчёта"): `report.json`, `report.txt`,
 * the last part of the core log (the log export's source), the final configuration of the selected server and the
 * device, all cleaned by [ReportRedactor] with the servers and subscriptions of this device. Written to
 * cacheDir/diagnostics (shared through the FileProvider) only when the user asks for it; nothing leaves the device
 * until they pick where to send it ("Без автоматической отправки").
 */
object RepairReportExport {

    const val MIME = "application/zip"
    private const val DIR = "diagnostics"
    private const val PREFIX = "throne-repair-"
    /** Older reports go at the next export: by then the app they were shared with has its copy. */
    private const val MAX_AGE_MS = 60 * 60 * 1000L

    /** The last part of core.log: the minutes of «Починка» and a little before it. */
    private const val CORE_LOG_BYTES = 512 * 1024L

    /** The archive's files, in this order. */
    val ENTRIES = listOf("report.txt", "report.json", "core.log", "config.json")

    /** Builds the archive. Blocking (database, files): call it off the main thread. */
    fun build(context: Context, results: Map<RepairStep, StepResult>, verdict: RepairVerdict?): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (it.name.startsWith(PREFIX) && now - it.lastModified() > MAX_AGE_MS) it.delete() }

        val redactor = redactor()
        val coreLog = String(CoreLog.read(CORE_LOG_BYTES))
        val device = device(coreLog)
        val files = linkedMapOf(
            "report.txt" to RepairReport.text(results, verdict, device, now),
            "report.json" to RepairReport.json(results, verdict, device, now),
            "core.log" to coreLog,
            "config.json" to config(redactor),
        )
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val file = File(dir, "$PREFIX$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for (name in ENTRIES) {
                zip.putNextEntry(ZipEntry(name))
                // config.json is cleaned while it is built; the JSON files stay valid JSON (ReportRedactor.redactJson).
                val text = when (name) {
                    "config.json" -> files.getValue(name)
                    "report.json" -> redactor.redactJson(files.getValue(name))
                    else -> redactor.redactText(files.getValue(name))
                }
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        Logs.i("simple repair: report exported, ${file.length()} bytes")
        return file
    }

    /** The share sheet for [file], one archive. */
    fun shareIntent(context: Context, file: File, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".cache", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, title)
    }

    /** The cleaning with every server name, secret value and subscription link of this device. */
    private fun redactor(): ReportRedactor {
        val servers = LinkedHashSet<String>()
        val secrets = LinkedHashSet<String>()
        for (profile in SagerDatabase.proxyDao.getAll()) {
            val server = runCatching { profile.outbound.server }.getOrDefault("")
            servers += ReportRedactor.serverNames(server, profile.outboundJson)
            secrets += ReportRedactor.secretValues(profile.outboundJson)
        }
        val subscriptions = SagerDatabase.groupDao.allGroups().map { it.url }.filter { it.isNotBlank() }
        return ReportRedactor(servers, subscriptions, secrets)
    }

    /**
     * The final configuration of the selected server, as the classic "Export configuration" builds it: sing-box and
     * any Xray configs, in one JSON document `{"sing_box": …, "xray": […]}`, cleaned by [ReportRedactor.redactJson].
     */
    private fun config(redactor: ReportRedactor): String = try {
        val profile = ProfileManager.getProfile(DataStore.selectedProxy) ?: return "{\"error\": \"no server selected\"}\n"
        val generated = CoreConfigs.buildMain(profile)
        val xray = buildList {
            generated.xrayConfig?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(generated.xrayFullConfigs.filter { it.isNotBlank() })
        }
        val root = JSONObject().put("sing_box", JSONObject(generated.coreConfig))
        if (xray.isNotEmpty()) root.put("xray", JSONArray(xray.map { JSONObject(it) }))
        redactor.redactJson(root.toString()) + "\n"
    } catch (e: Exception) {
        // The message names no server: the generator's errors are about settings; it is cleaned all the same.
        // Only the message string is cleaned, then put in a JSON object: the patterns never see JSON syntax.
        JSONObject().put("error", redactor.redact("${e.javaClass.simpleName}: ${e.message}")).toString(2) + "\n"
    }

    private fun device(coreLog: String): ReportDevice {
        // CoreRuntime logs "core: sing-box …, xray …, go…" at each start of the core.
        val core = coreLog.lineSequence().lastOrNull { it.contains("core: sing-box") }?.substringAfter("core: ")?.trim()
        return ReportDevice(
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            app = SagerNet.appVersionNameForDisplay,
            appVersionCode = BuildConfig.VERSION_CODE.toLong(),
            build = "${BuildConfig.FLAVOR} ${if (BuildConfig.DEBUG) "debug" else "release"}",
            core = core ?: "ThroneCore ${BuildConfig.THRONE_CORE_REF} (not started since the log began)",
            network = networkType(),
            state = mapOf(
                "service" to DataStore.serviceState.name.lowercase(),
                "mode" to runCatching { TrafficModes.mode().key }.getOrDefault("unknown"),
            ),
        )
    }

    /** The kind of the physical network: wifi, cellular, ethernet…; "none" without one. */
    private fun networkType(): String {
        val connectivity = SagerNet.connectivity
        val network = SagerNet.underlyingNetwork ?: connectivity.activeNetwork ?: return "none"
        val caps = connectivity.getNetworkCapabilities(network) ?: return "unknown"
        val kinds = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }
        val metered = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "" else ", metered"
        return (kinds.joinToString("+").ifEmpty { "other" }) + metered
    }
}
