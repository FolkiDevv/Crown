package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.add.PendingImports.Entry
import org.junit.Assert.assertEquals
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

    @Test
    fun onlyTheStaleOnesAreReturned() {
        val old = Entry(1_000L, 1L, "https://a")
        val running = Entry(50_000L, 2L, "https://b")
        assertEquals(listOf(old), PendingImports.stale(listOf(old, running), now = 60_000L, processStart = 40_000L))
    }
}
