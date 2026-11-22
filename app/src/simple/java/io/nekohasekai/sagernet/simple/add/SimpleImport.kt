package io.nekohasekai.sagernet.simple.add

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.ui.SubscribeFlows
import io.nekohasekai.sagernet.ui.profile.ProfileTextImport
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * The universal add of the simple UI (simple-ui "Универсальное добавление серверов", design.md D4) over the import
 * machinery of the classic interface — SubscriptionClient's queue in `:bg` and ProfileTextImport's parser — without
 * its choice dialogs: one http(s) URL is a subscription, anything the parser understands is servers for «Мои серверы»,
 * the rest is not recognised. Blocking calls; run them off the main thread.
 */
object SimpleImport {

    /** What a text turned out to be, before anything is added. */
    sealed interface Plan {
        data object Empty : Plan
        data object NotRecognized : Plan

        /** A subscription at [url], with the group name its link suggests (may be empty). */
        data class Subscription(val url: String, val name: String) : Plan

        /** Servers to add: [links] are their share links (for the "already added" check), [count] how many. */
        data class Servers(val text: String, val count: Int, val links: List<String>) : Plan

        /** A routing profile link, which the classic interface imports. */
        data class Route(val text: String) : Plan
    }

    fun plan(text: String): Plan = try {
        planOf(text)
    } catch (e: Exception) {
        Logs.w("simple add: reading the text failed", e)
        Plan.NotRecognized
    }

    private fun planOf(text: String): Plan = when (ImportText.kind(text)) {
        ImportText.Kind.EMPTY -> Plan.Empty
        ImportText.Kind.SUBSCRIPTION_URL -> Plan.Subscription(text.trim(), "")
        ImportText.Kind.SUBSCRIBE_LINK -> SubscribeFlows.subscribeLink(text)
            ?.takeIf { ImportText.isHttpUrl(it.url) }
            ?.let { Plan.Subscription(it.url, it.name) }
            ?: Plan.NotRecognized

        ImportText.Kind.ROUTE_LINK -> Plan.Route(text.trim())
        ImportText.Kind.CONTENT -> {
            val outbounds = try {
                ProfileTextImport.parse(text)
            } catch (e: Exception) {
                Logs.w(e)
                emptyList()
            }
            if (outbounds.isEmpty()) {
                Plan.NotRecognized
            } else {
                val links = outbounds.mapNotNull { o -> runCatching { o.exportToLink() }.getOrNull()?.takeIf { "://" in it } }
                Plan.Servers(text, outbounds.size, links)
            }
        }
    }

    /** Whether everything [plan] would add is in the app already (simple-ui "Ссылка уже добавлена"). */
    fun isAdded(plan: Plan): Boolean = when (plan) {
        is Plan.Subscription -> ImportText.alreadyAdded(listOf(plan.url), GroupRepo.all().filter { it.isSubscription }.map { it.url })
        // Both sides come from the same exporter, so the same server has the same link.
        is Plan.Servers -> plan.links.size == plan.count &&
            ImportText.alreadyAdded(plan.links, SagerDatabase.proxyDao.getAll().mapNotNull { p ->
                runCatching { p.exportLink() }.getOrNull()?.takeIf { it.isNotEmpty() }
            })

        else -> false
    }

    // ------------------------------------------------------------------------------------------------ servers

    /** Adds the servers of [plan] to «Мои серверы»; returns how many were stored. */
    suspend fun addServers(context: Context, plan: Plan.Servers): Int {
        val gid = myServersGroup(context)
        return SubscriptionClient.importText(plan.text, gid).count
    }

    /**
     * The group of servers added by hand: the first group that is no subscription (a subscription update would remove
     * what it did not bring), else a new one.
     */
    private suspend fun myServersGroup(context: Context): Long {
        GroupRepo.all().firstOrNull { !it.isSubscription && !it.archive }?.let { return it.id }
        return GroupRepo.add(ProxyGroup(name = context.getString(R.string.simple_group_my_servers))).id
    }

    // ------------------------------------------------------------------------------------------------ subscriptions

    sealed interface SubscribeResult {
        data class Done(val gid: Long) : SubscribeResult

        /** [detail]: what the queue reported, if anything. */
        data class Failed(val detail: String?) : SubscribeResult
    }

    /** How long the first download of a subscription may take before the add gives up waiting. */
    private const val SUBSCRIBE_TIMEOUT_MS = 120_000L

    /**
     * Adds the subscription and waits for its first download (the queue in `:bg` refreshes a new group at once). A
     * download that fails leaves nothing behind: the new group is removed again, so adding it once more just works.
     */
    suspend fun subscribe(plan: Plan.Subscription): SubscribeResult = coroutineScope {
        val reports = Channel<SubscriptionClient.Report>(Channel.UNLIMITED)
        // Collecting keeps the callback registered; it lands before the download ends (that needs the network).
        val listener = launch { SubscriptionClient.reports.collect { reports.trySend(it) } }
        try {
            delay(200)
            val gid = SubscriptionClient.subscribeUrl(plan.url, plan.name, autoUpdate = true)
            if (gid <= 0L) {
                return@coroutineScope SubscribeResult.Failed(drainError(reports, -1L))
            }
            val result = withTimeoutOrNull(SUBSCRIBE_TIMEOUT_MS) { awaitFirstUpdate(gid, reports) }
                ?: SubscribeResult.Failed(null)
            if (result is SubscribeResult.Failed) {
                Logs.w("simple add: the first download of subscription $gid failed: ${result.detail}")
                if (GroupRepo.delete(gid)) SubscriptionScheduler.schedule()
            }
            result
        } finally {
            listener.cancel()
        }
    }

    /**
     * The first update stamps `sub_last_update` before it reports; a failed one reports (a popup of the first
     * download) without the stamp, or the queue goes idle without it.
     */
    private suspend fun awaitFirstUpdate(gid: Long, reports: Channel<SubscriptionClient.Report>): SubscribeResult {
        var seenQueued = false
        var detail: String? = null
        while (true) {
            val group = GroupRepo.get(gid) ?: return SubscribeResult.Failed(detail)
            if (group.subLastUpdate > 0L) return SubscribeResult.Done(gid)
            var reported = false
            while (true) {
                val report = reports.tryReceive().getOrNull() ?: break
                if (report.gid != gid || report.reload) continue
                failureDetail(report.text, group.name)?.let { detail = it }
                reported = true
            }
            val queued = SubscriptionClient.states.value.containsKey(gid)
            if (queued) seenQueued = true
            if (reported || seenQueued && !queued) {
                // The stamp of a successful update precedes its reports; read it once more to be sure.
                delay(300)
                if ((GroupRepo.get(gid)?.subLastUpdate ?: 0L) > 0L) return SubscribeResult.Done(gid)
                return SubscribeResult.Failed(detail)
            }
            delay(400)
        }
    }

    /**
     * What a failed first download reported, without the classic report's frame: the "Imported 0 profiles" count and
     * the advice to update the group or change its TLS / HTTP / User Agent settings (the simple UI removes the group).
     * What stays is the reason itself (the transport error, an empty or web-page answer, the provider's announcement).
     */
    private fun failureDetail(text: String, groupName: String): String? {
        val frame = setOf(
            vplural(R.plurals.validation_import_imported, 0).trim(),
            vtr(R.string.validation_import_first_download_failed, groupName).trim(),
        )
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() && it !in frame }
            .joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun drainError(reports: Channel<SubscriptionClient.Report>, gid: Long): String? {
        var detail: String? = null
        while (true) {
            val report = reports.tryReceive().getOrNull() ?: break
            if (report.gid == gid && report.error && report.text.isNotBlank()) detail = report.text.trim()
        }
        return detail
    }

    /** The fallback interval of automatic updates when the provider sends none (simple-ui "Готовые настройки"). */
    const val DAILY_MINUTES = 24 * 60

    /**
     * The subscription [gid] updates itself: as often as its provider asks (`profile-update-interval`, when «respect
     * the server's interval» is on — SimpleDefaults turns it on, the group follows the global switch), otherwise once a
     * day (its own interval). Only `sub_options_json` and the name are written, so the row's other columns, which
     * `:bg` writes, stay as they are. The global switch must be on too, or nothing is automatic; there is no per-group
     * switch for that, so turning it on also lets the user's other subscriptions that do not skip automatic updates
     * update (with the interval they follow). Returns the interval in effect, in hours, 0 when none.
     */
    suspend fun updateAutomatically(gid: Long, plan: Plan.Subscription): Int {
        val group = GroupRepo.get(gid) ?: return 0
        val options = group.subOptions.copy(updateInterval = DAILY_MINUTES)
        val db = SagerDatabase.instance.openHelper.writableDatabase
        db.execSQL("UPDATE `groups` SET `sub_options_json` = ? WHERE `id` = ?", arrayOf<Any>(options.toJsonString(), gid))
        // A link without a name of its own: the provider's Profile-Title names the group, as the summary does,
        // rather than the host of the URL.
        val title = group.subMetadata.title.trim()
        if (plan.name.isBlank() && title.isNotEmpty() && title != group.name) {
            db.execSQL("UPDATE `groups` SET `name` = ? WHERE `id` = ?", arrayOf<Any>(title, gid))
        }
        if (DataStore.subAutoUpdate < SubscriptionScheduler.MIN_INTERVAL_MINUTES) DataStore.subAutoUpdate = DAILY_MINUTES
        GroupRepo.postUpdate(gid)
        SubscriptionScheduler.schedule()
        val seconds = GroupRepo.get(gid)?.let { SubscriptionScheduler.interval(it) } ?: 0L
        Logs.i("simple add: subscription $gid updates every ${seconds / 60} min")
        return ((seconds + 1800) / 3600).toInt()
    }

    /** What the summary of an added subscription shows (simple-ui "Сводка импорта"). */
    fun summary(gid: Long, enabled: List<Enabled>, updateHours: Int): ImportSummary? {
        val group = GroupRepo.get(gid) ?: return null
        val members = SagerDatabase.proxyDao.getByGroup(gid).filter { !TrafficModes.isManagedSelector(it.id) }
        val countries = members.mapNotNull { p -> runCatching { CountryFlags.countryOf(p.displayName()) }.getOrNull() }
            .toSet()
        val info: SubUserInfo = group.subMetadata
        return ImportSummary(
            name = info.title.takeIf { it.isNotBlank() } ?: group.displayName(),
            servers = members.size,
            countries = countries.size,
            remaining = if (info.valid && info.hasQuota && info.total > 0) info.remaining else null,
            unlimited = info.valid && info.hasQuota && info.total <= 0L,
            expire = info.expire.takeIf { info.valid && it > 0L },
            enabled = enabled,
            updateHours = updateHours,
        )
    }

    // ------------------------------------------------------------------------------------------------ files

    private const val MAX_FILE_SIZE = 50L * 1024 * 1024

    class FileError(val name: String, val tooLarge: Boolean) : Exception()

    /**
     * The text of a file that is no backup, read the way the classic import reads it (SubscribeFlows.importUri: at
     * most 50 MB, a BOM names the encoding, binary data is no text); null when it is not text.
     */
    fun readFile(context: Context, uri: Uri): String? {
        val name = displayName(context, uri)
        val bytes = try {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FILE_SIZE) throw FileError(name, tooLarge = true)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: FileError) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            throw FileError(name, tooLarge = false)
        }
        return decodeText(bytes)
    }

    fun displayName(context: Context, uri: Uri): String {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    private fun decodeText(bytes: ByteArray): String? {
        fun startsWith(vararg prefix: Int) =
            bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
        val bom = when {
            startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
            startsWith(0xFF, 0xFE, 0x00, 0x00) -> Charset.forName("UTF-32LE") to 4
            startsWith(0x00, 0x00, 0xFE, 0xFF) -> Charset.forName("UTF-32BE") to 4
            startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
            startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
            else -> null
        }
        if (bom != null) return String(bytes, bom.second, bytes.size - bom.second, bom.first)
        val sample = minOf(bytes.size, 8192)
        var control = 0
        for (i in 0 until sample) {
            val c = bytes[i].toInt() and 0xFF
            if (c == 0) return null
            if (c < 0x20 && c != '\t'.code && c != '\n'.code && c != '\r'.code && c != 0x0B && c != 0x0C && c != 0x1B) control++
        }
        if (control * 20 > sample) return null
        return String(bytes, Charsets.UTF_8)
    }
}

/** A ready setting the first subscription turned on, listed in its summary. */
enum class Enabled { AUTO_SERVER, AUTO_UPDATE, AUTO_MODE }

/** The summary of an added subscription. */
data class ImportSummary(
    val name: String,
    val servers: Int,
    /** Distinct countries recognised by the flags in the server names. */
    val countries: Int,
    /** Traffic left in bytes, null when the provider sends no limit. */
    val remaining: Long?,
    val unlimited: Boolean,
    /** Epoch seconds, null when the provider sends none. */
    val expire: Long?,
    val enabled: List<Enabled>,
    /** The interval of automatic updates in hours ([Enabled.AUTO_UPDATE]), 0 when none. */
    val updateHours: Int,
)
