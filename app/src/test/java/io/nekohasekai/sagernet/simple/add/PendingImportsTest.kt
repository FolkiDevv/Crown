package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.add.PendingImports.Entry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingImportsTest {

    private val url = "https://sub.example/path?token=a&b=c"

    @Test
    fun entriesRoundTrip() {
        val entries = listOf(Entry(1_000L, 0L, url), Entry(2_000L, 42L, "https://other.example/s"))
        assertEquals(entries, PendingImports.parse(PendingImports.format(entries)))
    }

    @Test
    fun damagedLinesAreIgnored() {
        val text = "garbage\n1000\t7\n\tx\turl\n-5\t1\thttps://a\n1000\t-1\thttps://b\n1000\t3\t \n2000\t9\thttps://ok"
        assertEquals(listOf(Entry(2_000L, 9L, "https://ok")), PendingImports.parse(text))
        assertTrue(PendingImports.parse(null).isEmpty())
        assertTrue(PendingImports.parse("").isEmpty())
    }

    @Test
    fun anImportStillRunningIsLeftAlone() {
        val processStart = 10_000L
        val running = Entry(since = 20_000L, gid = 5L, url = url)
        assertTrue(PendingImports.stale(listOf(running), now = 30_000L, processStart = processStart).isEmpty())
    }

    @Test
    fun anImportOfAProcessThatDiedIsHealed() {
        // The process was killed during the first download; a new process starts a few seconds later.
        val interrupted = Entry(since = 9_000L, gid = 5L, url = url)
        assertEquals(listOf(interrupted), PendingImports.stale(listOf(interrupted), now = 12_000L, processStart = 10_000L))
    }

    @Test
    fun anImportKilledBeforeItKnewItsGroupIsHealedToo() {
        // subscribeUrl created the group in :bg, but the UI process died before it heard the id.
        val noGroupYet = Entry(since = 9_000L, gid = 0L, url = url)
        assertEquals(listOf(noGroupYet), PendingImports.stale(listOf(noGroupYet), now = 12_000L, processStart = 10_000L))
    }

    @Test
    fun anImportThatNeverEndedInThisProcessIsHealedAfterTheTimeout() {
        val processStart = 0L
        val since = 1_000_000L
        val entry = Entry(since, 5L, url)
        val justInTime = since + PendingImports.STALE_AFTER_MS
        assertTrue(PendingImports.stale(listOf(entry), now = justInTime, processStart = processStart).isEmpty())
        assertEquals(listOf(entry), PendingImports.stale(listOf(entry), now = justInTime + 1, processStart = processStart))
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

        override suspend fun schedule(): Boolean {
            schedules++
            return scheduleResult()
        }
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
    fun onlyTheStaleOnesAreReturned() {
        val old = Entry(1_000L, 1L, "https://a")
        val running = Entry(50_000L, 2L, "https://b")
        assertEquals(listOf(old), PendingImports.stale(listOf(old, running), now = 60_000L, processStart = 40_000L))
    }
}
