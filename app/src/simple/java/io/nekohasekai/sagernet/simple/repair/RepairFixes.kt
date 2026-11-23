package io.nekohasekai.sagernet.simple.repair

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
class RepairJournal {

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

    /** The changes in effect, oldest first. */
    val applied: List<Entry> get() = synchronized(entries) { entries.toList() }

    fun record(step: RepairStep, kind: FixKind?, keepIf: RepairStep = step, restore: () -> Unit): Entry =
        Entry(step, kind, keepIf, restore).also { synchronized(entries) { entries += it } }

    /** Puts [entry] back; true when it was still in effect. */
    fun restore(entry: Entry): Boolean {
        val removed = synchronized(entries) { entries.remove(entry) }
        if (removed) entry.restore()
        return removed
    }

    /** Puts back the changes that stay only while [step] passes; returns them. */
    fun restoreKeptBy(step: RepairStep): List<Entry> = restoreWhere { it.keepIf == step }

    /** Puts back every change, newest first (the stop); returns them. */
    fun restoreAll(): List<Entry> = restoreWhere { true }

    private fun restoreWhere(predicate: (Entry) -> Boolean): List<Entry> {
        val chosen = synchronized(entries) {
            entries.filter(predicate).also { entries.removeAll(it.toSet()) }
        }
        // Newest first: a later change may stand on an earlier one (the server chosen for the VPN raised after it).
        for (entry in chosen.asReversed()) entry.restore()
        return chosen
    }
}
