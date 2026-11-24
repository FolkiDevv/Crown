package io.nekohasekai.sagernet.simple.add

/**
 * Subscriptions the new interface is adding (SimpleImport.subscribe). Such a group is created without automatic
 * updates, so that no automatic poll downloads it again while its first download runs; the import turns them on when
 * it ends, however it ends. When the import never got to its end (the process died, the device turned off), the entry
 * stays here, and the next start of the interface turns automatic updates on for that group: an interrupted import
 * never leaves a subscription that does not update itself. The groups a user excluded from automatic updates in the
 * classic interface are never touched, since only groups recorded here are.
 *
 * Kept as text in the UI process's preferences (only that process imports): one entry per line,
 * `<start, ms since epoch>\t<group id, 0 while unknown>\t<subscription URL>`.
 */
object PendingImports {

    data class Entry(val since: Long, val gid: Long, val url: String) {
        /** One import, before and after its group id is known. */
        fun sameImport(other: Entry) = since == other.since && url == other.url
    }

    /** Where the entries live: the UI process's preferences in the app, memory in tests. */
    interface Records {
        fun load(): List<Entry>
        fun save(entries: List<Entry>)
    }

    /** Adds or replaces (the group id became known) the entry of one import. */
    fun record(records: Records, lock: Any, entry: Entry) = synchronized(lock) {
        records.save(records.load().filterNot { it.sameImport(entry) } + entry)
    }

    /**
     * Turns automatic updates back on for the groups of [entries] ([restore]: true when done, also when nothing was
     * left to do; false or a throw when not) and only then forgets those entries. A failed restore keeps its entry,
     * and so does a process that dies between the two steps: the next heal restores again, which finds the flag
     * already cleared, does nothing and forgets the entry. Entries recorded meanwhile are kept. Returns the forgotten.
     */
    suspend fun settle(records: Records, lock: Any, entries: List<Entry>, restore: suspend (Entry) -> Boolean): List<Entry> {
        val done = entries.filter { entry -> runCatching { restore(entry) }.getOrDefault(false) }
        if (done.isNotEmpty()) synchronized(lock) {
            records.save(records.load().filterNot { kept -> done.any { it.sameImport(kept) } })
        }
        return done
    }

    /**
     * An import still in progress has no reason to last longer than this: the first download gives up after
     * two minutes. Older entries come from an import that was interrupted.
     */
    const val STALE_AFTER_MS = 5 * 60_000L

    fun parse(text: String?): List<Entry> = text.orEmpty().lines().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return@mapNotNull null
        val since = parts[0].toLongOrNull() ?: return@mapNotNull null
        val gid = parts[1].toLongOrNull() ?: return@mapNotNull null
        val url = parts[2].trim()
        if (url.isEmpty() || since <= 0L || gid < 0L) null else Entry(since, gid, url)
    }

    fun format(entries: List<Entry>): String =
        entries.joinToString("\n") { "${it.since}\t${it.gid}\t${it.url.replace('\t', ' ').replace('\n', ' ')}" }

    /**
     * The entries whose import is not running any more: begun before this process started (the process that ran it
     * is gone) or longer ago than [staleAfterMs].
     */
    fun stale(entries: List<Entry>, now: Long, processStart: Long, staleAfterMs: Long = STALE_AFTER_MS): List<Entry> =
        entries.filter { it.since < processStart || now - it.since > staleAfterMs }
}
