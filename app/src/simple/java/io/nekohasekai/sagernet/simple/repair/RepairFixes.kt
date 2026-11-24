package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One reversible automatic fix of «Починка» (connection-repair "Безопасные автоматические исправления"): a setting of
 * this app changed to a value that may help, checked again, and put back when the check still fails
 * (connection-repair "Откат неудачного исправления") or when the user stops «Починка».
 *
 * The value to put back is read when the fix is made (before [apply]), so [restore] works even after an [apply] that
 * did not finish (stopped or timed out half way).
 */
interface RepairFix {
    val kind: FixKind

    /** The setting the fix changes, with its value before and after (for the report); null when it changes none. */
    val change: FixChange? get() = null

    /** Makes the change and lets the running VPN take it up (a reload, waited for). */
    suspend fun apply()

    /**
     * Puts the value read before [apply] back. Quick and never waiting: a running VPN takes the restored values up in
     * [RepairChecks.settle], once for everything restored together.
     */
    fun restore()

    /** A check of the fix lighter than the whole step again; null runs the step again. */
    suspend fun recheck(): StepResult? = null
}

/**
 * A setting an automatic fix changed: its name and the values before and after, as the report shows them (DNS servers
 * and MTU as they are, a server by its id, never its address).
 */
data class FixChange(val setting: String, val before: String, val after: String)

/**
 * What «Починка» changed so far in this run, in order, with how to put each change back (design.md D8: every fix
 * registers its rollback). The engine records a fix before applying it, removes it again when its recheck fails, and
 * puts back everything left when the run is stopped. [keepIf] ties a change to the check that must pass for it to stay:
 * the fix's own check, or for the VPN raised for the checks through it, «Сайты через VPN».
 */
class RepairJournal(
    /**
     * A restore that threw: the entry stays in the journal (a later rollback tries it again) and the others go on
     * being restored.
     */
    private val onRestoreFailed: (Entry, Exception) -> Unit = { _, _ -> },
) {

    class Entry(
        /** The check the change was made for. */
        val step: RepairStep,
        /** The fix shown as «исправлено»; null for a change that is no fix of its own (the VPN raised for the checks). */
        val kind: FixKind?,
        /** The check whose failure puts the change back. */
        val keepIf: RepairStep,
        val restore: () -> Unit,
    )

    private val entries = ArrayList<Entry>()

    /** Entries whose last restore threw: still in effect, to be tried again. */
    private val failed = LinkedHashSet<Entry>()

    /** The changes in effect, oldest first. */
    val applied: List<Entry> get() = synchronized(entries) { entries.toList() }

    /** The changes a rollback tried to put back and could not, oldest first (still in effect). */
    val unrestored: List<Entry> get() = synchronized(entries) { entries.filter { it in failed } }

    /** Tries the [unrestored] changes again, newest first; returns those put back now. */
    fun retryFailed(): List<Entry> = restoreWhere { it in failed }

    fun record(step: RepairStep, kind: FixKind?, keepIf: RepairStep = step, restore: () -> Unit): Entry =
        Entry(step, kind, keepIf, restore).also { synchronized(entries) { entries += it } }

    /** Puts [entry] back; true when it was in effect and is put back now (a failed restore keeps it in effect). */
    fun restore(entry: Entry): Boolean {
        if (synchronized(entries) { entry !in entries }) return false
        if (!run(entry)) return false
        synchronized(entries) { entries.remove(entry) }
        return true
    }

    /** Puts back the changes that stay only while [step] passes; returns those put back. */
    fun restoreKeptBy(step: RepairStep): List<Entry> = restoreWhere { it.keepIf == step }

    /** Puts back every change, newest first (the stop); returns those put back. */
    fun restoreAll(): List<Entry> = restoreWhere { true }

    private fun restoreWhere(predicate: (Entry) -> Boolean): List<Entry> {
        val chosen = synchronized(entries) { entries.filter(predicate) }
        // Newest first: a later change may stand on an earlier one (the server chosen for the VPN raised after it). A
        // restore that throws stops none of the others.
        val restored = chosen.asReversed().filter(::run)
        synchronized(entries) { entries.removeAll(restored.toSet()) }
        return restored
    }

    private fun run(entry: Entry): Boolean = try {
        entry.restore()
        synchronized(entries) { failed -= entry }
        true
    } catch (e: Exception) {
        synchronized(entries) { failed += entry }
        onRestoreFailed(entry, e)
        false
    }
}

/**
 * Runs of «Починка» one after another: each starts only after the run before it has ended — a stopped run ends only
 * after its rollback (not cancellable), so the next run never reads a value about to be put back. The wait itself is
 * not cancellable either: a run stopped while it still waits keeps the dependency, and the run after it waits for the
 * same rollback. Such a run's [launch] block still runs after the wait, already cancelled: it ends at its first
 * suspension, so it can publish its own stopped state.
 */
class SerialRuns(private val scope: CoroutineScope) {

    private var last: Job? = null

    @Synchronized
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = last
        return scope.launch {
            if (previous != null) withContext(NonCancellable) { previous.join() }
            block()
        }.also { last = it }
    }
}

/**
 * The changes of earlier runs of «Починка» that could not be put back ([RepairJournal.unrestored]): kept with the
 * checks that made them (whose [RepairChecks.settle] lets the VPN take a restored value up) and tried again before the
 * next run reads or changes any setting. Pure, like the engine.
 */
class UnrestoredChanges {

    private val left = ArrayList<Pair<RepairJournal, RepairChecks>>()

    /** Whether some change is still waiting to be put back. */
    val pending: Boolean get() = synchronized(left) { left.isNotEmpty() }

    /** After a run: keeps its journal when a change of it could not be put back. */
    fun keep(journal: RepairJournal, checks: RepairChecks) {
        if (journal.unrestored.isNotEmpty()) synchronized(left) { left += journal to checks }
    }

    /** Before a run: tries every kept change again, the newest run first; the VPN takes what was put back up. */
    suspend fun retry() {
        val runs = synchronized(left) { left.toList() }.asReversed()
        for ((journal, checks) in runs) {
            if (journal.retryFailed().isNotEmpty()) checks.settle(true)
            if (journal.unrestored.isEmpty()) synchronized(left) { left.remove(journal to checks) }
        }
    }
}
