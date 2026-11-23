package io.nekohasekai.sagernet.simple.repair

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
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
    /** The report archive is being built (batch 9). */
    val exporting: Boolean = false,
) {
    /** The report can be exported: a run ended, with any outcome or stopped (connection-repair "Экспорт отчёта"). */
    val canExport: Boolean get() = !running && progress != null && (verdict != null || stopped)
}

/** The report archive is ready to share, or could not be built. */
sealed interface RepairExport {
    class Ready(val file: File) : RepairExport
    data object Failed : RepairExport
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
        mutableState.update { it.copy(running = true, progress = RepairProgress.initial(), verdict = null, stopped = false) }
        Logs.i("simple repair: started")
        // A stopped run still finishing must not overwrite the state of the next one.
        fun publish(change: (RepairUiState) -> RepairUiState) {
            if (run == runs) mutableState.update(change)
        }
        job = viewModelScope.launch {
            val engine = RepairEngine(DeviceRepairChecks(getApplication(), environment))
            try {
                val progress = engine.run { progress -> publish { it.copy(progress = progress) } }
                val verdict = RepairVerdict.of(progress.results)
                Logs.i("simple repair: finished, verdict ${describe(verdict)}")
                publish { it.copy(running = false, progress = progress, verdict = verdict) }
            } catch (e: CancellationException) {
                Logs.i("simple repair: stopped by the user")
                publish { it.copy(running = false, stopped = true) }
                throw e
            }
        }
    }

    private var runs = 0

    /** «Остановить»: the check in flight and those after it are «не проверено: остановлено». */
    fun stop() {
        job?.cancel()
    }

    private val exports = Channel<RepairExport>(Channel.BUFFERED)

    /** The archives built by [export], for the screen to open the share sheet. */
    val exportEvents: Flow<RepairExport> = exports.receiveAsFlow()

    /**
     * «Экспортировать отчёт»: builds the archive of the last run off the main thread. Only on the user's press: the
     * report never leaves the device by itself (connection-repair "Без автоматической отправки").
     */
    fun export() {
        val current = state.value
        if (!current.canExport || current.exporting) return
        val results = current.progress?.results ?: return
        mutableState.update { it.copy(exporting = true) }
        viewModelScope.launch {
            val event = try {
                RepairExport.Ready(withContext(Dispatchers.IO) {
                    RepairReportExport.build(getApplication(), results, current.verdict)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("simple repair: report export failed", e)
                RepairExport.Failed
            } catch (e: LinkageError) {
                // A pattern the platform's regex engine rejects fails the redactor's class init with an Error.
                Logs.w("simple repair: report redaction unavailable", e)
                RepairExport.Failed
            } finally {
                mutableState.update { it.copy(exporting = false) }
            }
            exports.send(event)
        }
    }

    /** Leaving the screen stops a run in progress; the last results stay for the next opening's new run. */
    fun close() {
        stop()
        mutableState.update { it.copy(open = false) }
    }

    private fun describe(verdict: RepairVerdict): String = when (verdict) {
        is RepairVerdict.Fixed -> "fixed ${verdict.fixes.map { it.second.name.lowercase() }}"
        is RepairVerdict.CauseFound -> "cause ${verdict.cause.name.lowercase()}"
        is RepairVerdict.Unknown -> if (verdict.nothingFound) "unknown, no problem found" else "unknown"
    }
}
