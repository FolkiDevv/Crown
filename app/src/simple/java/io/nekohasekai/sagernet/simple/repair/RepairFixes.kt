package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

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

    /**
     * Whether the setting still holds the value this fix wrote. A rollback puts the old value back only then (compare
     * and set): when something else changed the setting since — a later run, the user — the change is superseded and
     * the newer value is kept. True for a fix that writes nothing a later change could supersede.
     */
    fun stillApplied(): Boolean = true

    /**
     * Whether [restore] reverses the fix's effect. False for provider content that stays after a rollback (updated
     * routing profiles, an updated subscription): a stop then does not report the fix as undone.
     */
    val reversible: Boolean get() = true
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
    /** A change whose setting was changed since by something else: dropped without a write. */
    private val onSuperseded: (Entry) -> Unit = { },
    /**
     * Runs one compare-and-set (the check whether the change is still in place, and the restore) so that no other
     * writer can come between them: on the device a write transaction of the settings database, which excludes every
     * other writer, the other processes too.
     */
    private val atomically: (() -> Unit) -> Unit = { it() },
) {

    class Entry(
        /** The check the change was made for. */
        val step: RepairStep,
        /** The fix shown as «исправлено»; null for a change that is no fix of its own (the VPN raised for the checks). */
        val kind: FixKind?,
        /** The check whose failure puts the change back. */
        val keepIf: RepairStep,
        /** Whether the value this change wrote is still in place ([RepairFix.stillApplied]). */
        val stillOurs: () -> Boolean,
        /** Whether a restore reverses the change ([RepairFix.reversible]). */
        val reversible: Boolean,
        /**
         * Whether a later run may retry a failed restore ([UnrestoredChanges]). False for a change bound to this run,
         * such as the VPN it started: a retry later could stop a VPN the user started since.
         */
        val retriable: Boolean,
        /**
         * The setting the change wrote, when one is known. Changes of one setting form a chain (A → B → C): they are
         * put back newest first, and an older one waits while a newer one of the same setting is not put back.
         */
        val key: String?,
        val restore: () -> Unit,
    ) {
        /** The change was put back by a restore that reversed it (not superseded, not provider content). */
        @Volatile
        var reversed: Boolean = false
            internal set
    }

    private val entries = ArrayList<Entry>()

    /** Entries whose last restore threw: still in effect, to be tried again. */
    private val failed = LinkedHashSet<Entry>()

    /** The changes in effect, oldest first. */
    val applied: List<Entry> get() = synchronized(entries) { entries.toList() }

    /** The changes a rollback tried to put back and could not, oldest first (still in effect). */
    val unrestored: List<Entry> get() = synchronized(entries) { entries.filter { it in failed } }

    /** The [unrestored] changes a later run may try again ([Entry.retriable]). */
    val retriable: List<Entry> get() = unrestored.filter { it.retriable }

    /** Tries the [retriable] changes again, newest first; returns those put back now. */
    fun retryFailed(): List<Entry> = restoreWhere { it in failed && it.retriable }

    /** Hands the [retriable] changes over (oldest first) to another journal ([adopt]); they leave this one. */
    fun takeRetriable(): List<Entry> = synchronized(entries) {
        entries.filter { it in failed && it.retriable }.also {
            entries.removeAll(it.toSet())
            failed.removeAll(it.toSet())
        }
    }

    /** Takes over changes still to be put back, after the ones it holds (they are newer). */
    fun adopt(changes: List<Entry>) = synchronized(entries) {
        entries += changes
        failed += changes
    }

    fun record(
        step: RepairStep,
        kind: FixKind?,
        keepIf: RepairStep = step,
        stillOurs: () -> Boolean = { true },
        reversible: Boolean = true,
        retriable: Boolean = true,
        key: String? = null,
        restore: () -> Unit,
    ): Entry = Entry(step, kind, keepIf, stillOurs, reversible, retriable, key, restore)
        .also { synchronized(entries) { entries += it } }

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
        // restore that throws stops none of the others — but an older change of the same setting waits for it (its
        // compare would see the newer value and drop it as superseded); it is kept as not put back, for a retry.
        val blocked = HashSet<String>()
        val restored = ArrayList<Entry>()
        for (entry in chosen.asReversed()) {
            val key = entry.key
            if (key != null && key in blocked) {
                synchronized(entries) { failed += entry }
                continue
            }
            if (run(entry)) restored += entry else if (key != null) blocked += key
        }
        synchronized(entries) { entries.removeAll(restored.toSet()) }
        return restored
    }

    /**
     * Puts one change back, compare and set: only while the value it wrote is still in place; a superseded change is
     * done with, without a write (the newer value stays). True when the change is no longer in effect.
     */
    private fun run(entry: Entry): Boolean = try {
        var superseded = false
        atomically {
            if (entry.stillOurs()) {
                entry.restore()
                entry.reversed = entry.reversible
            } else {
                superseded = true
            }
        }
        if (superseded) onSuperseded(entry)
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
 * after its rollback (not cancellable), so the next run never reads a value about to be put back.
 *
 * The order does not depend on any run's body: each run has a «done» signal that completes only when the run itself
 * has completed AND the «done» of the run before it has — set when the run is launched. A run cancelled before it even
 * started, or while it waits, therefore still holds the next run back until every earlier rollback is through. Runs
 * start atomically (a cancellation before the start does not skip the body) and wait without cancellation, so a
 * stopped run's body still runs after the wait — already cancelled, it ends at its first suspension and can publish
 * its own stopped state.
 */
class SerialRuns(private val scope: CoroutineScope) {

    /**
     * The scope of the runs and the stop gate of the newest one, closed together in the right order: the gate first
     * (the run's calls to `:bg` are given up), then the scope (the runs are cancelled). The view model registers it
     * with `addCloseable` and launches the runs in [scope], not in `viewModelScope`, which a view model cancels before
     * anything else of its own when it is cleared.
     */
    class Owner(context: CoroutineContext) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + context)

        @Volatile
        var gate: RepairStopGate? = null

        override fun close() {
            gate?.close()
            scope.cancel()
        }
    }

    /** The «done» of the newest run: complete when it and every run before it are complete. */
    private var lastDone: Job? = null

    @Synchronized
    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val previous = lastDone
        val done = Job()
        lastDone = done
        val run = scope.launch(start = CoroutineStart.ATOMIC) {
            if (previous != null) withContext(NonCancellable) { previous.join() }
            block()
        }
        run.invokeOnCompletion {
            if (previous == null) done.complete() else previous.invokeOnCompletion { done.complete() }
        }
        return run
    }
}

/**
 * The changes of earlier runs of «Починка» that could not be put back ([RepairJournal.unrestored]): kept with the
 * checks that made them (whose [RepairChecks.settle] lets the VPN take a restored value up) and tried again before the
 * next run reads or changes any setting. Pure, like the engine.
 */
class UnrestoredChanges(
    /** The compare-and-set of every carried change, as in the journal of its run. */
    atomically: (() -> Unit) -> Unit = { it() },
    onRestoreFailed: (RepairJournal.Entry, Exception) -> Unit = { _, _ -> },
    onSuperseded: (RepairJournal.Entry) -> Unit = { },
) {

    /**
     * One journal for the changes of every run, oldest first: the chain rule of a setting (an older change waits while
     * a newer one of the same setting is not put back) holds across runs too.
     */
    private val carry = RepairJournal(onRestoreFailed, onSuperseded, atomically)

    /** The checks of the run that made each carried change: their [RepairChecks.settle] lets the VPN take it up. */
    private val owners = HashMap<RepairJournal.Entry, RepairChecks>()

    /** Whether some change is still waiting to be put back. */
    val pending: Boolean get() = carry.retriable.isNotEmpty()

    /** After a run: takes over its changes that a later run may retry and that could not be put back. */
    fun keep(journal: RepairJournal, checks: RepairChecks) {
        val changes = journal.takeRetriable()
        if (changes.isEmpty()) return
        synchronized(owners) { changes.forEach { owners[it] = checks } }
        carry.adopt(changes)
    }

    /** Before a run: tries every carried change again, the newest first; the VPN takes what was put back up. */
    suspend fun retry() {
        val restored = carry.retryFailed()
        val settle = synchronized(owners) { restored.mapNotNull { owners.remove(it) }.distinct() }
        for (checks in settle) settle(checks)
    }

    /**
     * [retry] at the start of a run — unless that run was stopped before it got there (it waited for the run before
     * it): then it only ends, and the next run retries. Once started, the retry is not cut short.
     */
    suspend fun retryUnlessCancelled() {
        if (!currentCoroutineContext().isActive || !pending) return
        withContext(NonCancellable) { retry() }
    }

    /**
     * As the engine settles: limited in time, and nothing it throws escapes into the run that retries (the reload a
     * restore owes is armed in the restore itself and survives a failed wait).
     */
    private suspend fun settle(checks: RepairChecks) {
        try {
            withTimeoutOrNull(SETTLE_MS) { checks.settle(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    private companion object {
        const val SETTLE_MS = 120_000L
    }
}
