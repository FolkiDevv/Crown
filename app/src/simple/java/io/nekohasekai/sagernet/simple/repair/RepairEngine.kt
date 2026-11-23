package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Runs one check on the device (DeviceRepairChecks); [done] holds the results of the checks before it. */
fun interface RepairChecks {
    suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult
}

/** Where a check is while «Починка» runs. */
sealed interface StepState {
    data object Pending : StepState
    data object Running : StepState
    data class Done(val result: StepResult) : StepState
}

/** The progress «Починка» shows: every check in [RepairPlan.ORDER] with its state. */
data class RepairProgress(val steps: Map<RepairStep, StepState>) {

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
 * than its limit is «не проверено» too, never an error. Cancelling the coroutine stops it at once: the check in flight
 * and those after it are marked «не проверено: остановлено» in the last progress, then the cancellation goes on.
 *
 * No Android types: the device side is [RepairChecks], so JVM unit tests cover the order, the dependencies and the
 * stop. No automatic fixes and no VPN start here (batch 8).
 */
class RepairEngine(
    private val checks: RepairChecks,
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: (RepairStep) -> Long = ::defaultTimeout,
) {

    suspend fun run(onProgress: (RepairProgress) -> Unit): RepairProgress {
        val steps = LinkedHashMap<RepairStep, StepState>()
        RepairPlan.ORDER.forEach { steps[it] = StepState.Pending }
        fun publish() = RepairProgress(LinkedHashMap(steps)).also(onProgress)
        publish()
        val done = LinkedHashMap<RepairStep, StepResult>()
        try {
            for (step in RepairPlan.ORDER) {
                val started = now()
                val skip = RepairPlan.skipReason(step, done)
                val result = if (skip != null) {
                    StepResult.skipped(skip)
                } else {
                    steps[step] = StepState.Running
                    publish()
                    runOne(step, done)
                }.copy(startedAt = started, elapsedMs = (now() - started).coerceAtLeast(0L))
                done[step] = result
                steps[step] = StepState.Done(result)
                publish()
            }
        } catch (e: CancellationException) {
            for (step in RepairPlan.ORDER) {
                if (steps[step] !is StepState.Done) steps[step] = StepState.Done(StepResult.skipped(SkipReason.STOPPED))
            }
            publish()
            throw e
        }
        return RepairProgress(LinkedHashMap(steps))
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
        /** The URL test of every server takes longest; the rest are a few requests each. */
        fun defaultTimeout(step: RepairStep): Long = when (step) {
            RepairStep.SERVERS -> 150_000L
            RepairStep.SITES_VPN -> 45_000L
            else -> 30_000L
        }
    }
}
