package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.add.PendingImports.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImportsTest {

    private val url = "https://sub.example/path?token=a&b=c"

    private val here = "this-process"

    @Test
    fun entriesRoundTrip() {
        val entries = listOf(Entry(1_000L, 0L, url, here), Entry(2_000L, 42L, "https://other.example/s", ""))
        assertEquals(entries, PendingImports.parse(PendingImports.format(entries)))
    }

    @Test
    fun entriesWithoutASessionAreStillRead() {
        // Written before sessions existed: no session, so never taken for an import running now.
        assertEquals(listOf(Entry(2_000L, 9L, "https://ok", "")), PendingImports.parse("2000\t9\thttps://ok"))
    }

    @Test
    fun damagedLinesAreIgnored() {
        val text = "garbage\n1000\t7\n\tx\turl\n-5\t1\thttps://a\n1000\t-1\thttps://b\n1000\t3\t \n2000\t9\ts\thttps://ok"
        assertEquals(listOf(Entry(2_000L, 9L, "https://ok", "s")), PendingImports.parse(text))
        assertTrue(PendingImports.parse(null).isEmpty())
        assertTrue(PendingImports.parse("").isEmpty())
    }

    @Test
    fun anImportStillRunningInThisProcessIsLeftAlone() {
        val running = Entry(since = 20_000L, gid = 5L, url = url, session = here)
        assertTrue(PendingImports.stale(listOf(running), session = here).isEmpty())
    }

    @Test
    fun anImportOfAProcessThatDiedIsHealed() {
        // The process was killed during the first download; the new process has another session, whatever the clock says.
        val interrupted = Entry(since = 99_000L, gid = 5L, url = url, session = "gone")
        assertEquals(listOf(interrupted), PendingImports.stale(listOf(interrupted), session = here))
    }

    @Test
    fun anImportKilledBeforeItKnewItsGroupIsHealedToo() {
        // subscribeUrl created the group in :bg, but the UI process died before it heard the id.
        val noGroupYet = Entry(since = 9_000L, gid = 0L, url = url, session = "gone")
        assertEquals(listOf(noGroupYet), PendingImports.stale(listOf(noGroupYet), session = here))
    }

    @Test
    fun anImportThatEndedWithoutRestoringIsHealedInThisProcessToo() {
        // Its own restore failed: the import cleared its session, so the next heal of this process retries it.
        val ended = Entry(since = 9_000L, gid = 5L, url = url, session = "")
        assertEquals(listOf(ended), PendingImports.stale(listOf(ended), session = here))
    }

    /** The preferences of the app, in memory. */
    private class Memory(var entries: List<Entry> = emptyList()) : PendingImports.Records {
        override fun load() = entries
        override fun save(entries: List<Entry>) { this.entries = entries }
    }

    private val lock = Any()

    /** A group's «skip automatic updates» flag, as the database keeps it. */
    private class Group(var skipAutoUpdate: Boolean = true)

    @Test
    fun theRecordIsForgottenOnlyAfterTheFlagIsCleared() = runBlocking {
        val group = Group()
        val records = Memory()
        val entry = Entry(1_000L, 5L, url)
        PendingImports.record(records, lock, entry)
        val done = PendingImports.settle(records, lock, listOf(entry)) {
            // At the moment of the restore the record is still there: a death here loses nothing.
            assertEquals(listOf(entry), records.entries)
            group.skipAutoUpdate = false
            true
        }
        assertEquals(listOf(entry), done)
        assertFalse(group.skipAutoUpdate)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun aFailedRestoreKeepsTheRecord() = runBlocking {
        val records = Memory(listOf(Entry(1_000L, 5L, url)))
        assertTrue(PendingImports.settle(records, lock, records.entries) { false }.isEmpty())
        assertEquals(1, records.entries.size)
        assertTrue(PendingImports.settle(records, lock, records.entries) { error("database is locked") }.isEmpty())
        assertEquals(1, records.entries.size)
    }

    @Test
    fun aDeathBetweenClearAndForgetIsHealedByAHarmlessSecondRun() = runBlocking {
        val group = Group()
        val entry = Entry(1_000L, 5L, url)
        // First run: the flag is cleared, then the process dies before the record is forgotten.
        group.skipAutoUpdate = false
        val records = Memory(listOf(entry))
        // Next start: the restore finds nothing to do (idempotent) and the record goes.
        var writes = 0
        val done = PendingImports.settle(records, lock, listOf(entry)) {
            if (group.skipAutoUpdate) { group.skipAutoUpdate = false; writes++ }
            true
        }
        assertEquals(listOf(entry), done)
        assertEquals(0, writes)
        assertFalse(group.skipAutoUpdate)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun settlingKeepsTheOtherImportsAndTheOnesRecordedMeanwhile() = runBlocking {
        val interrupted = Entry(1_000L, 5L, url)
        val running = Entry(50_000L, 0L, "https://running.example")
        val records = Memory(listOf(interrupted, running))
        val meanwhile = Entry(60_000L, 0L, "https://new.example")
        PendingImports.settle(records, lock, listOf(interrupted)) {
            PendingImports.record(records, lock, meanwhile)
            true
        }
        assertEquals(listOf(running, meanwhile), records.entries)
    }

    @Test
    fun theGroupIdReplacesTheEntryOfTheSameImport() {
        val records = Memory()
        val begun = Entry(1_000L, 0L, url)
        PendingImports.record(records, lock, begun)
        PendingImports.record(records, lock, begun.copy(gid = 7L))
        assertEquals(listOf(begun.copy(gid = 7L)), records.entries)
        // Settling the entry without its id (the finally of an import that never heard it) still forgets it.
        runBlocking { PendingImports.settle(records, lock, listOf(begun)) { true } }
        assertTrue(records.entries.isEmpty())
    }

    /** The database, the group listeners and the scheduler, with failures to switch on. */
    private class FakeAutoUpdates(var skipAutoUpdate: Boolean = true, var exists: Boolean = true) : PendingImports.AutoUpdates {
        var failChanged = false
        var scheduleResult: () -> Boolean = { true }
        var clears = 0
        var notified = 0
        var schedules = 0

        override suspend fun groupsOf(entry: Entry) =
            if (exists) listOf(PendingImports.GroupFlag(entry.gid, skipAutoUpdate)) else emptyList()

        override suspend fun clearFlag(id: Long) {
            skipAutoUpdate = false
            clears++
        }

        override suspend fun changed(id: Long) {
            if (failChanged) error("the listener failed")
            notified++
        }

        /** The global switch: null = never set (a fresh install), false = off by the user, true = on. */
        var global: Boolean? = true

        override suspend fun globalOnIfNeverSet() {
            if (global == null) global = true
        }

        /** Like SubscriptionScheduler: a poll is needed only while the global switch is on. */
        override suspend fun schedule(): Boolean {
            schedules++
            return if (global == true) scheduleResult() else true
        }
    }

    @Test
    fun anInterruptedFirstImportOnAFreshInstallTurnsTheGlobalSwitchOn() = runBlocking {
        // The first import died before updateAutomatically turned the never-set global switch on.
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates().apply { global = null; scheduleResult = { false } }
        // The poll it now needs is not confirmed yet: the record stays, the switch is on.
        assertTrue(settleWithRestore(records, updates, entry).isEmpty())
        assertEquals(true, updates.global)
        assertEquals(listOf(entry), records.entries)
        updates.scheduleResult = { true }
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun theUsersOffStaysOff() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates().apply { global = false }
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertEquals(false, updates.global)
        assertFalse(updates.skipAutoUpdate)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun aGoneGroupDoesNotTouchTheGlobalSwitch() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val updates = FakeAutoUpdates(exists = false).apply { global = null }
        assertEquals(listOf(entry), settleWithRestore(Memory(listOf(entry)), updates, entry))
        assertEquals(null, updates.global)
    }

    private suspend fun settleWithRestore(records: Memory, updates: FakeAutoUpdates, entry: Entry) =
        PendingImports.settle(records, lock, listOf(entry)) { PendingImports.restore(it, updates) }

    @Test
    fun aRestoreClearsTellsAndSchedulesBeforeTheRecordGoes() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates()
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertFalse(updates.skipAutoUpdate)
        assertEquals(listOf(1, 1, 1), listOf(updates.clears, updates.notified, updates.schedules))
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun aListenerFailureAfterTheFlagIsClearedIsCompletedByTheNextRun() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates().apply { failChanged = true }
        // The flag is cleared, then postUpdate throws: nothing scheduled yet, so the record stays.
        assertTrue(settleWithRestore(records, updates, entry).isEmpty())
        assertFalse(updates.skipAutoUpdate)
        assertEquals(0, updates.schedules)
        assertEquals(listOf(entry), records.entries)
        // Next start: the flag is already cleared, yet the listeners and the scheduler still run, then the record goes.
        updates.failChanged = false
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertEquals(1, updates.clears)
        assertEquals(1, updates.notified)
        assertEquals(1, updates.schedules)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun anUnconfirmedOrFailedRegistrationKeepsTheRecord() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates().apply { scheduleResult = { false } }
        assertTrue(settleWithRestore(records, updates, entry).isEmpty())
        assertEquals(listOf(entry), records.entries)
        updates.scheduleResult = { error("WorkManager is not available") }
        assertTrue(settleWithRestore(records, updates, entry).isEmpty())
        assertEquals(listOf(entry), records.entries)
        // Once the registration is confirmed, the record goes; the flag was cleared only once.
        updates.scheduleResult = { true }
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertEquals(3, updates.schedules)
        assertEquals(1, updates.clears)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun aGroupThatIsGoneStillGetsTheSchedulerPlannedAgain() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        val updates = FakeAutoUpdates(exists = false)
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertEquals(0, updates.clears)
        assertEquals(1, updates.schedules)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun theWorkAlreadyThereDoesNotConfirmANewRegistration() {
        val old = PendingImports.WorkMark("a", generation = 0, finished = false)
        // The new scheduling failed: WorkManager still shows the old work only.
        assertFalse(PendingImports.registeredAfter(listOf(old), listOf(old)))
        // Updated in place (ExistingPeriodicWorkPolicy.UPDATE raises the generation).
        assertTrue(PendingImports.registeredAfter(listOf(old), listOf(old.copy(generation = 1))))
        // Registered anew (another id), or for the first time.
        assertTrue(PendingImports.registeredAfter(listOf(old), listOf(PendingImports.WorkMark("b", 0, false))))
        assertTrue(PendingImports.registeredAfter(emptyList(), listOf(PendingImports.WorkMark("b", 0, false))))
        // A finished (cancelled) work confirms nothing; neither does nothing at all.
        assertFalse(PendingImports.registeredAfter(listOf(old), listOf(old.copy(generation = 1, finished = true))))
        assertFalse(PendingImports.registeredAfter(listOf(old), emptyList()))
    }

    /** WorkManager's unique work of the scheduler, with the period of its plan (hours), and a scheduler that can fail. */
    private class FakeScheduler(var work: Pair<PendingImports.WorkMark, Int>?) {
        var fails = false

        /** SubscriptionScheduler.schedule: registers the plan of the current groups (UPDATE), unless it fails. */
        fun schedule(periodHours: Int) {
            if (fails) return // logged by the scheduler, never thrown
            val current = work?.first
            work = (current?.copy(generation = current.generation + 1) ?: PendingImports.WorkMark("w", 0, false)) to periodHours
        }

        fun marks() = listOfNotNull(work?.first)
    }

    /** The app's schedule step over [scheduler]: read before, schedule, confirm a registration after the call. */
    private fun confirmingUpdates(scheduler: FakeScheduler, periodHours: Int) = FakeAutoUpdates().apply {
        scheduleResult = {
            val before = scheduler.marks()
            scheduler.schedule(periodHours)
            PendingImports.registeredAfter(before, scheduler.marks())
        }
    }

    @Test
    fun anOldJobOfAnotherPlanKeepsTheRecordUntilTheNewPlanIsRegistered() = runBlocking {
        val entry = Entry(1_000L, 5L, url)
        val records = Memory(listOf(entry))
        // A 24 h job from before; the restored subscription needs 1 h.
        val scheduler = FakeScheduler(PendingImports.WorkMark("w", 3, false) to 24)
        scheduler.fails = true
        val updates = confirmingUpdates(scheduler, periodHours = 1)
        assertTrue(settleWithRestore(records, updates, entry).isEmpty())
        assertEquals(24, scheduler.work!!.second)
        assertEquals(listOf(entry), records.entries)
        // Next start: the 1 h plan is registered (generation 4) and confirmed, then the record goes.
        scheduler.fails = false
        assertEquals(listOf(entry), settleWithRestore(records, updates, entry))
        assertEquals(PendingImports.WorkMark("w", 4, false) to 1, scheduler.work)
        assertTrue(records.entries.isEmpty())
    }

    @Test
    fun onlyTheStaleOnesAreReturned() {
        val old = Entry(1_000L, 1L, "https://a", "gone")
        val running = Entry(50_000L, 2L, "https://b", here)
        assertEquals(listOf(old), PendingImports.stale(listOf(old, running), session = here))
    }
}
