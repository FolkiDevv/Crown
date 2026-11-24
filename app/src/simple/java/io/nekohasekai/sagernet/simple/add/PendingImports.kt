package io.nekohasekai.sagernet.simple.add

import kotlinx.coroutines.CancellationException

/**
 * Subscriptions the new interface is adding (SimpleImport.subscribe). Such a group is created without automatic
 * updates, so that no automatic poll downloads it again while its first download runs; the import turns them on when
 * it ends, however it ends. When the import never got to its end (the process died, the device turned off), the entry
 * stays here, and the next start of the interface turns automatic updates on for that group: an interrupted import
 * never leaves a subscription that does not update itself. Only groups recorded here are touched (by id, or by URL
 * while the id never reached the UI process); the user's «automatic updates off» stays off.
 *
 * Kept as text in the UI process's preferences (only that process imports): one entry per line,
 * `<start, ms since epoch>\t<group id, 0 while unknown>\t<session>\t<subscription URL>`. The session names the process
 * that runs the import ([SESSION]); an entry of another session belongs to a process that is gone.
 */
object PendingImports {

    /**
     * [session]: the process running the import; empty for an import that ended without turning automatic updates
     * on (its restore is due at the next heal) and for entries written before sessions existed.
     */
    data class Entry(val since: Long, val gid: Long, val url: String, val session: String = "") {
        /** One import, before and after its group id is known. */
        fun sameImport(other: Entry) = since == other.since && url == other.url
    }

    /** Where the entries live: the UI process's preferences in the app, memory in tests. */
    interface Records {
        fun load(): List<Entry>
        fun save(entries: List<Entry>)
    }

    /** A subscription group as the restore sees it. */
    data class GroupFlag(val id: Long, val skipAutoUpdate: Boolean)

    /** What turning automatic updates back on touches: the groups, their listeners and the scheduler. */
    interface AutoUpdates {
        /** The group of an import: by its id, else by its URL. Empty when it is gone. */
        suspend fun groupsOf(entry: Entry): List<GroupFlag>

        /** Clears the group's «skip automatic updates» flag. */
        suspend fun clearFlag(id: Long)

        /** Tells the group's listeners that it changed. */
        suspend fun changed(id: Long)

        /**
         * Turns the global automatic updates on while they were never set, as a finished import does
         * (SimpleImport.updateAutomatically); an «off» the user chose stays off.
         */
        suspend fun globalOnIfNeverSet()

        /**
         * Plans the automatic updates again and confirms the result is in place: the periodic poll is registered, or
         * none is needed (nothing updates automatically). False when that cannot be confirmed.
         */
        suspend fun schedule(): Boolean
    }

    /**
     * Turns automatic updates back on for the group of [entry]: the flag cleared, the listeners told and the scheduler
     * confirmed. Every step runs on every call, also when an earlier call already cleared the flag: a call that failed
     * after the flag (listeners, scheduler) is completed by the next one. True only when all of it succeeded.
     */
    suspend fun restore(entry: Entry, updates: AutoUpdates): Boolean {
        val groups = updates.groupsOf(entry)
        for (group in groups) {
            if (group.skipAutoUpdate) updates.clearFlag(group.id)
            updates.changed(group.id)
        }
        // A first import interrupted before it switched the global setting on would leave it never set (off).
        if (groups.isNotEmpty()) updates.globalOnIfNeverSet()
        return updates.schedule()
    }

    /** The scheduler's periodic work as WorkManager reports it: its id, its generation and whether it is finished. */
    data class WorkMark(val id: String, val generation: Int, val finished: Boolean)

    /**
     * Whether the scheduler's work was registered again after [before] was read: an unfinished work that is new (another
     * id) or updated (a higher generation; ExistingPeriodicWorkPolicy.UPDATE raises it on every update, also an update
     * to the same plan). Such a registration was planned from the groups as they were when it ran, so after the flag
     * was cleared, with the restored group's interval in it. The work that was already there (an older plan, maybe
     * another period) does not count.
     */
    fun registeredAfter(before: List<WorkMark>, after: List<WorkMark>): Boolean = after.any { work ->
        !work.finished && before.none { it.id == work.id && it.generation >= work.generation }
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
        val done = entries.filter { entry ->
            try {
                restore(entry)
            } catch (e: CancellationException) {
                throw e // the entry stays for the next heal
            } catch (_: Exception) {
                false
            }
        }
        if (done.isNotEmpty()) synchronized(lock) {
            records.save(records.load().filterNot { kept -> done.any { it.sameImport(kept) } })
        }
        return done
    }

    /** This process, as recorded in the entries of the imports it runs; another value means another process. */
    val SESSION: String = java.util.UUID.randomUUID().toString()

    fun parse(text: String?): List<Entry> = text.orEmpty().lines().mapNotNull { line ->
        // Four fields; three in entries written before sessions existed (no tab in a URL: format replaces them).
        val parts = line.split('\t', limit = 4)
        if (parts.size < 3) return@mapNotNull null
        val since = parts[0].toLongOrNull() ?: return@mapNotNull null
        val gid = parts[1].toLongOrNull() ?: return@mapNotNull null
        val session = if (parts.size == 4) parts[2] else ""
        val url = parts.last().trim()
        if (url.isEmpty() || since <= 0L || gid < 0L) null else Entry(since, gid, url, session)
    }

    fun format(entries: List<Entry>): String = entries.joinToString("\n") {
        "${it.since}\t${it.gid}\t${it.session.replace('\t', ' ')}\t${it.url.replace('\t', ' ').replace('\n', ' ')}"
    }

    /** The entries whose import is not running in this process ([session]): its process is gone, or it ended. */
    fun stale(entries: List<Entry>, session: String = SESSION): List<Entry> = entries.filter { it.session != session }
}
