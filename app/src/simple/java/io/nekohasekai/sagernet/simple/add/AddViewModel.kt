package io.nekohasekai.sagernet.simple.add

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleDefaults
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.simple.modes.TrafficModeApplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** A text for the snackbar: a string, or a plural for [count]. */
data class Notice(@StringRes val text: Int = 0, @PluralsRes val plural: Int = 0, val count: Int = 0)

/** What the add flow is waiting for; the screen shows a progress dialog meanwhile. */
enum class AddBusy { READING, SUBSCRIPTION, SERVERS }

/** The outcome the add flow shows in a dialog. */
sealed interface AddDialog {
    data class Summary(val summary: ImportSummary) : AddDialog
    data object NotRecognized : AddDialog
    data class SubscriptionFailed(val detail: String?) : AddDialog
    data object Failed : AddDialog
    data class FileUnreadable(val name: String, val tooLarge: Boolean) : AddDialog
}

/** The clipboard holds something new to add (simple-ui "Предложение импорта из буфера обмена"). */
data class ClipboardOffer(
    /** The host of the subscription link, null for servers. */
    val subscriptionHost: String?,
    val servers: Int,
)

/**
 * A link opened from a browser or another app asks to add something (simple-ui "Подтверждение ссылки из другого
 * приложения"): a web page must not add a subscription or servers unasked.
 */
data class LinkConfirm(
    /** The host of the subscription, null for servers. */
    val subscriptionHost: String?,
    /** The group name the subscription link suggests (may be empty). */
    val name: String,
    val servers: Int,
)

data class AddUiState(
    val busy: AddBusy? = null,
    val dialog: AddDialog? = null,
    val offer: ClipboardOffer? = null,
    val confirm: LinkConfirm? = null,
)

/** One-off requests to the activity. */
sealed interface AddEvent {
    /** Servers or a subscription were added: the home screen reads the servers again. */
    data object Added : AddEvent

    /** A routing profile link: the classic interface imports it. */
    data class OpenClassic(val text: String) : AddEvent

    /** A Throne backup: the classic interface restores it (Tools › Backup through BackupEntry). */
    data class OpenBackup(val uri: Uri) : AddEvent
}

/**
 * The universal add of the simple UI: the clipboard, a QR code, a link typed in, a file, a link or file opened from
 * another app. Formats are told apart by [SimpleImport] without the classic choice dialogs; nothing is added without
 * the user's action (the clipboard is only offered).
 */
class AddViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = mutableState.asStateFlow()

    private val noticeChannel = Channel<Notice>(Channel.BUFFERED)

    /** Snackbar texts. */
    val notices: Flow<Notice> = noticeChannel.receiveAsFlow()

    private val eventChannel = Channel<AddEvent>(Channel.BUFFERED)
    val events: Flow<AddEvent> = eventChannel.receiveAsFlow()

    /** One import at a time; a second request waits for the first. */
    private val imports = Mutex()

    /** The plan behind [AddUiState.offer] and the fingerprint of its text. */
    private var offered: Pair<SimpleImport.Plan, String>? = null

    /** The plan behind [AddUiState.confirm]. */
    private var confirming: SimpleImport.Plan? = null

    private val prefs get() = getApplication<Application>().getSharedPreferences(SimpleUi.PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------------------------------------ requests

    /**
     * Text from the clipboard button, the link dialog, a share or a link opened from another app. [external] text
     * (a link opened from a browser or another app, a share) is added only after [confirmLink] when it would add a
     * subscription or servers that are not in the app yet.
     */
    fun importText(text: String?, fromClipboard: Boolean = false, external: Boolean = false) {
        viewModelScope.launch {
            imports.withLock {
                val plan = reading { SimpleImport.plan(text.orEmpty()) }
                if (external && asks(plan)) ask(plan) else perform(plan, fromClipboard)
            }
        }
    }

    /** «Добавить» on [AddUiState.confirm]. */
    fun confirmLink() {
        val plan = confirming ?: return
        confirming = null
        mutableState.update { it.copy(confirm = null) }
        viewModelScope.launch { imports.withLock { perform(plan, fromClipboard = false) } }
    }

    /** «Отмена» on [AddUiState.confirm]: nothing is added. */
    fun cancelLink() {
        confirming = null
        mutableState.update { it.copy(confirm = null) }
    }

    /** The QR scanner's texts (one per code): share links together, subscriptions and documents one by one. */
    fun importTexts(texts: List<String>) {
        val payloads = texts.map { CrownLinks.toThrone(it).trim() }.filter { it.isNotEmpty() }
        if (payloads.size <= 1) return importText(payloads.firstOrNull())
        viewModelScope.launch {
            imports.withLock {
                for (unit in ImportText.importUnits(payloads)) {
                    perform(reading { SimpleImport.plan(unit) }, fromClipboard = false)
                }
            }
        }
    }

    /**
     * A file chosen here or opened from another app (design.md D4): a Throne backup goes to the stock restore
     * ([AddEvent.OpenBackup]); anything else is read as text and imported. The check runs here, not in the activity,
     * so a slow cloud file survives the activity being recreated meanwhile.
     */
    fun openFile(uri: Uri) {
        viewModelScope.launch {
            imports.withLock {
                val app = getApplication<Application>()
                if (reading { SimpleImport.isBackup(app, uri) }) {
                    mutableState.update { it.copy(busy = null) }
                    eventChannel.send(AddEvent.OpenBackup(uri))
                    return@withLock
                }
                val text = try {
                    reading { SimpleImport.readFile(app, uri) }
                } catch (e: SimpleImport.FileError) {
                    show(AddDialog.FileUnreadable(e.name, e.tooLarge))
                    return@withLock
                }
                if (text.isNullOrBlank()) {
                    show(AddDialog.FileUnreadable(withContext(Dispatchers.IO) { SimpleImport.displayName(app, uri) }, false))
                    return@withLock
                }
                perform(reading { SimpleImport.plan(text) }, fromClipboard = false)
            }
        }
    }

    /**
     * The clipboard when the simple UI opens: offered only when it is something to add, not in the app yet and not
     * turned down before. Nothing is added here.
     */
    fun checkClipboard(text: String?) {
        if (text.isNullOrBlank()) return
        viewModelScope.launch {
            if (imports.isLocked || mutableState.value.offer != null) return@launch
            val offer = withContext(Dispatchers.IO) {
                try {
                    // The exact text, not the fingerprint, which drops what follows a '#'.
                    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
                    synchronized(passedLock) {
                        if (prefs.getString(KEY_PASSED, null) == digest) return@withContext null
                        // Written before the read: a text whose read takes the process down is not read again on every start.
                        prefs.edit().putString(KEY_PASSED, digest).commit()
                    }
                    val plan = SimpleImport.plan(text)
                    if (plan !is SimpleImport.Plan.Subscription && plan !is SimpleImport.Plan.Servers) return@withContext null
                    // Only this check's mark: another check of a newer clip may have written its own meanwhile.
                    synchronized(passedLock) {
                        if (prefs.getString(KEY_PASSED, null) == digest) prefs.edit().remove(KEY_PASSED).commit()
                    }
                    val fingerprint = ImportText.fingerprint(text)
                    if (prefs.getString(KEY_DECLINED, null) == fingerprint) return@withContext null
                    if (SimpleImport.isAdded(plan)) return@withContext null
                    plan to fingerprint
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            } ?: return@launch
            offered = offer
            val plan = offer.first
            mutableState.update {
                it.copy(
                    offer = ClipboardOffer(
                        subscriptionHost = (plan as? SimpleImport.Plan.Subscription)?.let { sub -> hostOf(sub.url) },
                        servers = (plan as? SimpleImport.Plan.Servers)?.count ?: 0,
                    )
                )
            }
        }
    }

    fun acceptOffer() {
        val (plan, _) = offered ?: return
        offered = null
        mutableState.update { it.copy(offer = null) }
        viewModelScope.launch { imports.withLock { perform(plan, fromClipboard = true) } }
    }

    /** «Не сейчас»: the same clipboard content is not offered again. */
    fun declineOffer() {
        val fingerprint = offered?.second
        offered = null
        mutableState.update { it.copy(offer = null) }
        if (fingerprint != null) prefs.edit().putString(KEY_DECLINED, fingerprint).apply()
    }

    fun dismissDialog() = mutableState.update { it.copy(dialog = null) }

    // ------------------------------------------------------------------------------------------------ import

    private suspend fun perform(plan: SimpleImport.Plan, fromClipboard: Boolean) {
        try {
            when (plan) {
                SimpleImport.Plan.Empty ->
                    if (fromClipboard) notice(Notice(R.string.simple_add_clipboard_empty)) else show(AddDialog.NotRecognized)

                SimpleImport.Plan.NotRecognized -> show(AddDialog.NotRecognized)
                is SimpleImport.Plan.Route -> eventChannel.send(AddEvent.OpenClassic(plan.text))
                is SimpleImport.Plan.Subscription -> subscribe(plan)
                is SimpleImport.Plan.Servers -> addServers(plan)
            }
        } catch (e: CancellationException) {
            // The screen went away; the import scope finishes the import itself (SimpleImport.scope).
            throw e
        } catch (e: Exception) {
            Logs.w("simple add", e)
            show(AddDialog.Failed)
        } finally {
            mutableState.update { it.copy(busy = null) }
        }
    }

    private suspend fun subscribe(plan: SimpleImport.Plan.Subscription) {
        if (withContext(Dispatchers.IO) { SimpleImport.isAdded(plan) }) {
            notice(Notice(R.string.simple_add_subscription_exists))
            return
        }
        mutableState.update { it.copy(busy = AddBusy.SUBSCRIPTION) }
        // The download and its finishing touches (the update interval, or removing a group that never loaded) run in
        // the import scope, not in this ViewModel's: leaving the screen must not strand a half-added subscription.
        val outcome = SimpleImport.scope.async {
            // «Send HWID» and the provider's interval are on before the first download (SimpleDefaults).
            SimpleDefaults.apply(getApplication())
            val subscribed = SimpleImport.subscribe(plan)
            if (subscribed !is SimpleImport.SubscribeResult.Done) return@async Triple(subscribed, 0, false)
            val hours = SimpleImport.updateAutomatically(subscribed.gid, plan)
            // The ready settings come with the first subscription added here (simple-ui "Готовые настройки"); the
            // flag is written only once they are in effect, so a failed setup is tried again with the next one. The
            // check, the setup and the flag go under one process-wide lock: an import of an earlier screen may still
            // be running in the import scope while a new screen starts another one.
            val auto = SimpleImport.firstSetup.withLock {
                !prefs.getBoolean(KEY_FIRST_SUBSCRIPTION, false) &&
                    runCatching { TrafficModeApplier.setUpAuto(getApplication()) }
                        .onFailure { Logs.w("simple add: the ready settings", it) }.getOrDefault(false) &&
                    prefs.edit().putBoolean(KEY_FIRST_SUBSCRIPTION, true).commit()
            }
            Triple(subscribed, hours, auto)
        }.await()
        val (result, updateHours, auto) = outcome
        when (result) {
            is SimpleImport.SubscribeResult.Failed -> show(AddDialog.SubscriptionFailed(result.detail))
            is SimpleImport.SubscribeResult.Done -> {
                val enabled = ArrayList<Enabled>()
                if (auto) enabled.add(Enabled.AUTO_SERVER)
                if (updateHours > 0) enabled.add(Enabled.AUTO_UPDATE)
                if (auto) enabled.add(Enabled.AUTO_MODE)
                eventChannel.send(AddEvent.Added)
                val summary = withContext(Dispatchers.IO) { SimpleImport.summary(result.gid, enabled, updateHours) }
                if (summary != null) show(AddDialog.Summary(summary))
            }
        }
    }

    private suspend fun addServers(plan: SimpleImport.Plan.Servers) {
        if (withContext(Dispatchers.IO) { SimpleImport.isAdded(plan) }) {
            notice(Notice(R.string.simple_add_servers_exist))
            return
        }
        mutableState.update { it.copy(busy = AddBusy.SERVERS) }
        val count = withContext(Dispatchers.IO) { SimpleImport.addServers(getApplication(), plan) }
        if (count > 0) {
            eventChannel.send(AddEvent.Added)
            notice(Notice(plural = R.plurals.simple_add_servers_added, count = count))
        } else {
            show(AddDialog.Failed)
        }
    }

    /** Whether an external [plan] needs [confirmLink]: it adds something new (what is in the app only says so). */
    private suspend fun asks(plan: SimpleImport.Plan): Boolean =
        (plan is SimpleImport.Plan.Subscription || plan is SimpleImport.Plan.Servers) &&
            !withContext(Dispatchers.IO) { SimpleImport.isAdded(plan) }

    private fun ask(plan: SimpleImport.Plan) {
        confirming = plan
        val confirm = LinkConfirm(
            subscriptionHost = (plan as? SimpleImport.Plan.Subscription)?.let { hostOf(it.url) },
            name = (plan as? SimpleImport.Plan.Subscription)?.name.orEmpty().trim(),
            servers = (plan as? SimpleImport.Plan.Servers)?.count ?: 0,
        )
        mutableState.update { it.copy(busy = null, confirm = confirm) }
    }

    private suspend fun <T> reading(block: () -> T): T {
        mutableState.update { it.copy(busy = AddBusy.READING) }
        return withContext(Dispatchers.IO) { block() }
    }

    private fun show(dialog: AddDialog) = mutableState.update { it.copy(dialog = dialog, busy = null) }

    private fun notice(notice: Notice) {
        noticeChannel.trySend(notice)
    }

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')

    private companion object {
        /** The fingerprint of the clipboard content last turned down (ImportText.fingerprint, no text kept). */
        const val KEY_DECLINED = "clipboard_declined"

        /** The SHA-256 of the clipboard text found to be nothing to add, or whose read did not finish (no text kept). */
        const val KEY_PASSED = "clipboard_passed"

        /** [KEY_PASSED] is read and written under it: clipboard checks of this process, also of an earlier screen, overlap. */
        val passedLock = Any()

        /** The ready settings of the first subscription were turned on (UI process only, design.md D3). */
        const val KEY_FIRST_SUBSCRIPTION = "first_subscription_setup"
    }
}
