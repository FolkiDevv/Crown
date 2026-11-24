package io.nekohasekai.sagernet.simple.repair

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.BuildConfig
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the «Починка» screen shows. */
data class RepairUiState(
    /** The screen is open (over the section it was opened from). */
    val open: Boolean = false,
    val running: Boolean = false,
    /** null before the first run. */
    val progress: RepairProgress? = null,
    /** The outcome of a finished run; null while running and after a stop. */
    val verdict: RepairVerdict? = null,
    /** The user stopped the last run. */
    val stopped: Boolean = false,
    /** «Остановить» was pressed and the run is ending (it may first wait for a subscription update, then roll back). */
    val stopping: Boolean = false,
    /** The report archive is being built (batch 9). */
    val exporting: Boolean = false,
    /**
     * A built archive whose share sheet has not come back yet: the screen opens the sheet while it is resumed, again
     * after each return if Android did not let the sheet open (an activity start from the background is blocked
     * silently), and clears it when the sheet returns ([RepairViewModel.shareDone]).
     */
    val pendingShare: File? = null,
) {
    /** The report can be exported: a run ended, with any outcome or stopped (connection-repair "Экспорт отчёта"). */
    val canExport: Boolean get() = !running && progress != null && (verdict != null || stopped)
}

/**
 * Whether a share sheet that came back was ever shown. Android silently blocks an activity start from the background:
 * when the user presses Home just before the app starts the sheet, the sheet is created hidden and finished, cancelled,
 * when the user returns — so its result comes after the app stopped. A sheet where the user picked a target says so
 * through the chooser's callback ([targetChosen]) and was seen, however fast the pick and whatever the target returns.
 * A sheet closed by Back comes back before the app stops. So only a cancelled result, with no target picked, after a
 * stop within [BLOCKED_WINDOW_MS] of the start is taken for a sheet that was never seen. A sheet the user closed with
 * Home right after it appeared (Android delivers that result on return too) is then offered once more: harmless. Pure
 * Kotlin over [now] (elapsed ms), covered by a JVM unit test.
 */
class ShareGuard(private val now: () -> Long) {
    private var launchedAt = -1L
    private var stoppedAt = -1L
    private var chosen = false

    fun launched() {
        launchedAt = now()
        stoppedAt = -1L
        chosen = false
    }

    /** The app went to the background (a rotation is not one). */
    fun stopped() {
        if (launchedAt >= 0 && stoppedAt < 0) stoppedAt = now()
    }

    /**
     * The sheet reported that the user picked a target (the chooser's callback): it was seen, however fast the pick
     * and whatever result the target gives back.
     */
    fun targetChosen() {
        if (launchedAt >= 0) chosen = true
    }

    /** true: the sheet was there and is done with; false: it never showed, offer the archive again. */
    fun cameBack(ok: Boolean): Boolean {
        val unseen = !ok && !chosen && launchedAt >= 0 && stoppedAt >= 0 && stoppedAt - launchedAt <= BLOCKED_WINDOW_MS
        launchedAt = -1L
        stoppedAt = -1L
        chosen = false
        return !unseen
    }

    companion object {
        /** Seen on the emulators: the app stops 0.3–1.25 s after a blocked start, under load later; with a margin. */
        const val BLOCKED_WINDOW_MS = 2_500L
    }
}

/**
 * «Починка» in the UI process (design.md D8): runs [RepairEngine] over [DeviceRepairChecks] in the view model's scope,
 * so the run survives a rotation; [stop] cancels it at once. Opening the screen starts a run (connection-repair "Запуск
 * «Починки»"), with the VPN on or off.
 */
class RepairViewModel(application: Application) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow(RepairUiState())
    val state: StateFlow<RepairUiState> = mutableState.asStateFlow()

    private var job: Job? = null

    /** Opens the screen and starts a run, unless one runs already. */
    fun open(environment: RepairEnvironment) {
        mutableState.update { it.copy(open = true) }
        if (job?.isActive != true) start(environment)
    }

    /** «Проверить снова». */
    fun start(environment: RepairEnvironment) {
        if (job?.isActive == true) return
        val run = ++runs
        mutableState.update {
            it.copy(running = true, progress = RepairProgress.initial(), verdict = null, stopped = false, stopping = false)
        }
        Logs.i("simple repair: started")
        // A stopped run still finishing must not overwrite the state of the next one.
        fun publish(change: (RepairUiState) -> RepairUiState) {
            if (run == runs) mutableState.update(change)
        }
        // A stopped run is no longer active while it still puts its changes back (not cancellable): the new run reads
        // and changes the settings only after that, so it never takes a value about to be restored as its "before" —
        // also when it is itself stopped while it waits ([SerialRuns]).
        job = serial.launch {
            // A change an earlier run could not put back is tried again before this run reads any setting — unless this
            // run was stopped while it waited (it ends at once, as stopped; the next run retries).
            unrestored.retryUnlessCancelled()
            if (unrestored.pending) Logs.w("simple repair: a change of an earlier run is still not put back")
            // What this run changes, to be put back when a fix does not help or the run is stopped.
            val journal = RepairJournal(
                onRestoreFailed = RepairLog::restoreFailed,
                onSuperseded = RepairLog::superseded,
                atomically = RepairLog::inSettingsTransaction,
            )
            val checks = DeviceRepairChecks(getApplication(), environment, journal)
            val engine = RepairEngine(checks, journal)
            try {
                val progress = engine.run { progress -> publish { it.copy(progress = progress) } }
                val verdict = RepairVerdict.of(progress.results)
                // Every check, the skipped ones too (those never reach the device checks, which log the rest).
                Logs.i("simple repair: results ${summary(progress.results)}")
                Logs.i("simple repair: finished, verdict ${describe(verdict)}")
                publish { it.copy(running = false, stopping = false, progress = progress, verdict = verdict) }
            } catch (e: CancellationException) {
                journal.unrestored.takeIf { it.isNotEmpty() }?.let {
                    Logs.w("simple repair: ${it.size} change(s) could not be put back; tried again before the next run")
                }
                Logs.i("simple repair: stopped by the user")
                publish { it.copy(running = false, stopping = false, stopped = true) }
                throw e
            } finally {
                unrestored.keep(journal, checks)
            }
        }
    }

    private var runs = 0

    /** Changes earlier runs could not put back, retried before the next run: process-wide, they outlive any screen. */
    private val unrestored get() = RepairLog.unrestored

    /** The runs, one after another (each after the previous one's rollback). */
    private val serial = SerialRuns(viewModelScope)

    private val consentChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * Requests of the system's VPN consent dialog (the checks through the VPN, connection-repair "Согласие на VPN и
     * чужое VPN при проверках через VPN"); the activity shows it and answers with [consentAnswered].
     */
    val consentRequests: Flow<Unit> = consentChannel.receiveAsFlow()

    @Volatile
    private var consent: CompletableDeferred<Boolean>? = null

    /**
     * `SystemClock.elapsedRealtime()` when the consent dialog was shown and not answered yet; 0 when none is open.
     * One dialog at a time: its answer goes to the request waiting when it comes (the user answers the question every
     * request asks — this app's VPN consent); a request while it is open waits for that answer instead of a second
     * dialog, whose result could be taken for the first one's.
     */
    @Volatile
    private var dialogShownAt = 0L

    /** Asks the activity for the VPN consent and waits for the answer; kept here, so a rotation does not lose it. */
    suspend fun requestConsent(): Boolean {
        val waiter = CompletableDeferred<Boolean>()
        consent?.complete(false)
        consent = waiter
        // A dialog left open longer than this is taken as gone (the activity finished without a result).
        val open = dialogShownAt != 0L && SystemClock.elapsedRealtime() - dialogShownAt < CONSENT_DIALOG_STALE_MS
        if (!open) consentChannel.trySend(Unit)
        return try {
            waiter.await()
        } finally {
            if (consent === waiter) consent = null
        }
    }

    /** The activity shows the consent dialog now. */
    fun consentDialogShown() {
        dialogShownAt = SystemClock.elapsedRealtime()
    }

    /** The user's answer to the consent dialog (false also when the dialog could not open). */
    fun consentAnswered(granted: Boolean) {
        dialogShownAt = 0L
        consent?.complete(granted)
    }

    /** «Остановить»: the check in flight and those after it are «не проверено: остановлено». */
    fun stop() {
        val running = job ?: return
        // Shown at once: the run may first wait for a subscription update it started before it can roll back.
        if (running.isActive) mutableState.update { if (it.running) it.copy(stopping = true) else it }
        running.cancel()
    }

    private val failures = Channel<Unit>(Channel.BUFFERED)

    /** An archive could not be built (the screen says so). */
    val exportFailures: Flow<Unit> = failures.receiveAsFlow()

    /**
     * «Экспортировать отчёт»: builds the archive of the last run off the main thread and makes it [RepairUiState.pendingShare].
     * Only on the user's press: the report never leaves the device by itself (connection-repair "Без автоматической
     * отправки").
     */
    fun export() {
        val current = state.value
        if (!current.canExport || current.exporting || current.pendingShare != null) return
        val results = current.progress?.results ?: return
        mutableState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val file = try {
                withContext(Dispatchers.IO) { RepairReportExport.build(getApplication(), results, current.verdict) }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(exporting = false) }
                throw e
            } catch (e: Exception) {
                Logs.w("simple repair: report export failed", e)
                null
            } catch (e: LinkageError) {
                // A pattern the platform's regex engine rejects fails the redactor's class init with an Error.
                Logs.w("simple repair: report redaction unavailable", e)
                null
            }
            mutableState.update { it.copy(exporting = false, pendingShare = file) }
            if (file == null) failures.send(Unit)
        }
    }

    private val shareGuard = ShareGuard(SystemClock::elapsedRealtime)

    /** The chooser's callback when the user picks a target ([chosenTarget]); not exported, sent as this app. */
    private val chosenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = shareGuard.targetChosen()
    }

    init {
        ContextCompat.registerReceiver(
            application, chosenReceiver, IntentFilter(ACTION_TARGET_CHOSEN), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onCleared() {
        getApplication<Application>().unregisterReceiver(chosenReceiver)
    }

    /** For Intent.createChooser: the system sends it when the user picks a target in the share sheet. */
    fun chosenTarget(): IntentSender {
        val app = getApplication<Application>()
        // Mutable: the system adds the chosen component to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(app, 0, Intent(ACTION_TARGET_CHOSEN).setPackage(app.packageName), flags)
            .intentSender
    }

    /** The screen started the share sheet of [RepairUiState.pendingShare]. */
    fun shareLaunched() = shareGuard.launched()

    /** The activity went to the background (not for a rotation). */
    fun appStopped() {
        if (state.value.pendingShare != null) shareGuard.stopped()
    }

    /**
     * The share sheet came back with [ok] (a target was chosen) or not. A sheet Android did not let open — the user left
     * the app as it started — comes back cancelled too, when the user returns: then the archive stays pending and the
     * screen offers it again ([ShareGuard]).
     */
    fun shareResult(ok: Boolean) {
        if (shareGuard.cameBack(ok)) {
            shareDone()
        } else {
            Logs.i("simple repair: the share sheet did not open (the app was left as it started), offered again")
        }
    }

    /** The share sheet of [RepairUiState.pendingShare] came back (sent or dismissed), or could not be opened at all. */
    fun shareDone() {
        mutableState.update { it.copy(pendingShare = null) }
    }

    /** Leaving the screen stops a run in progress; the last results stay for the next opening's new run. */
    fun close() {
        stop()
        mutableState.update { it.copy(open = false) }
    }

    private fun summary(results: Map<RepairStep, StepResult>): String = RepairPlan.ORDER.joinToString { step ->
        val result = results[step] ?: return@joinToString "${step.name.lowercase()} -"
        buildString {
            append(step.name.lowercase()).append(' ').append(result.status.name.lowercase())
            result.skip?.let { append('(').append(it.name.lowercase()).append(')') }
            result.fix?.let { append('(').append(it.name.lowercase()).append(')') }
        }
    }

    private fun describe(verdict: RepairVerdict): String = when (verdict) {
        is RepairVerdict.Fixed -> "fixed ${verdict.fixes.map { it.second.name.lowercase() }}"
        is RepairVerdict.CauseFound -> "cause ${verdict.cause.name.lowercase()}"
        is RepairVerdict.Unknown -> if (verdict.nothingFound) "unknown, no problem found" else "unknown"
    }

    private companion object {
        const val ACTION_TARGET_CHOSEN = BuildConfig.APPLICATION_ID + ".REPAIR_SHARE_TARGET_CHOSEN"
    }
}

/** How long an unanswered VPN consent dialog counts as open; after that a request shows a new one. */
private const val CONSENT_DIALOG_STALE_MS = 120_000L

/** What every journal of «Починка» shares on the device, and the changes carried from run to run. */
private object RepairLog {

    private fun name(entry: RepairJournal.Entry) = entry.kind?.name?.lowercase() ?: "the VPN started for the checks"

    fun restoreFailed(entry: RepairJournal.Entry, e: Exception) =
        Logs.w("simple repair: could not put back ${name(entry)}", e)

    fun superseded(entry: RepairJournal.Entry) =
        Logs.i("simple repair: ${name(entry)} was changed since; the newer value is kept, not put back")

    /** The settings live in this database: a write transaction excludes every other writer (`:bg` too). */
    fun inSettingsTransaction(block: () -> Unit) = SagerDatabase.instance.runInTransaction(Runnable { block() })

    /** One carry for the process: the chain rule of a setting holds across runs. */
    val unrestored = UnrestoredChanges(::inSettingsTransaction, ::restoreFailed, ::superseded)
}
