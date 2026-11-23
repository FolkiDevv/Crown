package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Runs one check on the device (DeviceRepairChecks); [done] holds the results of the checks before it. */
fun interface RepairChecks {
    suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult

    /**
     * The automatic fixes worth trying, in order, for [result] of [step] (a failure or a warning); each is tried until
     * one makes the check pass. Only the reversible fixes of the spec; none by default.
     */
    suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>): List<RepairFix> =
        emptyList()

    /**
     * After values were put back ([RepairFix.restore], [RepairJournal.Entry.restore]): lets a running VPN take them up.
     * [wait]: until it is connected again (the run goes on and checks through it), else only asks for it (the stop).
     */
    suspend fun settle(wait: Boolean) = Unit
}

/** Where a check is while «Починка» runs. */
sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data class Done(val result: StepResult) : StepState
}

/**
 * The progress «Починка» shows: every check in [RepairPlan.ORDER] with its state; [undoing] while the changes of a
 * stopped run are being put back.
 */
data class RepairProgress(val steps: Map<RepairStep, StepState>, val undoing: Boolean = false) {

    val results: Map<RepairStep, StepResult>
        get() = steps.mapNotNull { (step, state) -> (state as? StepState.Done)?.let { step to it.result } }.toMap()

    val current: RepairStep? get() = steps.entries.firstOrNull { it.value == StepState.Running }?.key

    val finished: Boolean get() = steps.values.all { it is StepState.Done }

    companion object {
        fun initial() = RepairProgress(RepairPlan.ORDER.associateWith { StepState.Pending })
    }
}

/**
 * «Починка» (design.md D8): the checks one after another in [RepairPlan.ORDER]. A check whose prerequisite did not
 * pass is «не проверено» with the reason ([RepairPlan.skipReason]) without running; one that throws or takes longer
 * than its limit is «не проверено» too, never an error.
 *
 * A check that fails (or warns) gets the automatic fixes [RepairChecks.fixesFor] offers, one after another: each is
 * recorded in the [journal] before it is applied, then the check runs again; when it passes, the check is «исправлено»
 * and the change stays, otherwise the change is put back at once and the next fix is tried. A change kept only while a
 * later check passes ([RepairJournal.Entry.keepIf]: the VPN raised for the checks through it) is put back when that
 * check ends without passing.
 *
 * Cancelling the coroutine stops it at once: the check in flight and those after it are marked «не проверено:
 * остановлено», every change of the run is put back (connection-repair "Остановка"), then the cancellation goes on.
 *
 * No Android types: the device side is [RepairChecks] and [RepairFix], so JVM unit tests cover the order, the
 * dependencies, the fixes with their rollback, and the stop.
 */
class RepairEngine(
    private val checks: RepairChecks,
    private val journal: RepairJournal = RepairJournal(),
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: (RepairStep) -> Long = ::defaultTimeout,
    private val fixTimeoutMs: Long = FIX_TIMEOUT_MS,
) {

    suspend fun run(onProgress: (RepairProgress) -> Unit): RepairProgress {
        val steps = LinkedHashMap<RepairStep, StepState>()
        RepairPlan.ORDER.forEach { steps[it] = StepState.Pending }
        fun publish(undoing: Boolean = false) = RepairProgress(LinkedHashMap(steps), undoing).also(onProgress)
        publish()
        val done = LinkedHashMap<RepairStep, StepResult>()
        try {
            for (step in RepairPlan.ORDER) {
                val started = now()
                val skip = RepairPlan.skipReason(step, done)
                var result = if (skip != null) {
                    StepResult.skipped(skip)
                } else {
                    steps[step] = StepState.Running
                    publish()
                    runOne(step, done)
                }
                if (result.status == StepStatus.FAIL || result.status == StepStatus.WARN) {
                    result = tryFixes(step, result, done)
                }
                // A change kept only while this check passes (the VPN raised for it) goes when it does not.
                if (!result.status.passed && journal.restoreKeptBy(step).isNotEmpty()) settle(wait = true)
                result = result.copy(startedAt = started, elapsedMs = (now() - started).coerceAtLeast(0L))
                done[step] = result
                steps[step] = StepState.Done(result)
                publish()
            }
        } catch (e: CancellationException) {
            for (step in RepairPlan.ORDER) {
                if (steps[step] !is StepState.Done) steps[step] = StepState.Done(StepResult.skipped(SkipReason.STOPPED))
            }
            // The stop puts back every change of the run, the kept fixes too, before the cancellation goes on.
            withContext(NonCancellable) {
                if (journal.applied.isNotEmpty()) {
                    publish(undoing = true)
                    journal.restoreAll()
                    settle(wait = false)
                }
                publish()
            }
            throw e
        }
        return RepairProgress(LinkedHashMap(steps))
    }

    /** The fixes for the [failed] result of [step], one after another until one makes the check pass. */
    private suspend fun tryFixes(step: RepairStep, failed: StepResult, done: Map<RepairStep, StepResult>): StepResult {
        val fixes = try {
            checks.fixesFor(step, failed, done)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        for (fix in fixes) {
            val entry = journal.record(step, fix.kind, restore = fix::restore)
            val recheck = try {
                withTimeout(fixTimeoutMs) {
                    fix.apply()
                    fix.recheck() ?: checks.run(step, done)
                }
            } catch (e: TimeoutCancellationException) {
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (recheck != null && recheck.status.passed) {
                return failed.copy(status = StepStatus.FIXED, fix = fix.kind, values = failed.values + recheck.values)
            }
            // connection-repair "Откат неудачного исправления": the setting goes back before anything else is tried.
            if (journal.restore(entry)) settle(wait = true)
        }
        return failed
    }

    private suspend fun settle(wait: Boolean) {
        try {
            if (wait) withTimeout(fixTimeoutMs) { checks.settle(true) } else checks.settle(false)
        } catch (e: TimeoutCancellationException) {
            Unit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    private suspend fun runOne(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult = try {
        withTimeout(timeoutMs(step)) { checks.run(step, done) }
    } catch (e: TimeoutCancellationException) {
        StepResult.skipped(SkipReason.TIMED_OUT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StepResult.skipped(SkipReason.ERROR, note = "${e.javaClass.simpleName}: ${e.message}")
    }

    companion object {
        /** A fix with its reload and its recheck: a VPN reload may take a while on a slow device. */
        const val FIX_TIMEOUT_MS = 120_000L

        /**
         * The URL test of every server takes longest; the checks through the VPN may first ask for the VPN consent and
         * start the VPN.
         */
        fun defaultTimeout(step: RepairStep): Long = when (step) {
            RepairStep.SERVERS -> 150_000L
            RepairStep.DNS_VPN -> 150_000L
            RepairStep.SITES_VPN -> 120_000L
            else -> 30_000L
        }
    }
}
