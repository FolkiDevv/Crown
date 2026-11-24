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

    data class Entry(val since: Long, val gid: Long, val url: String)

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
