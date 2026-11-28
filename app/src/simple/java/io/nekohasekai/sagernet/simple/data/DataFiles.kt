package io.nekohasekai.sagernet.simple.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The file of the settings and profiles database (`sager_net.db`) seen from outside SQLite: the copy kept before a
 * corrupted file is removed, the header a failed open is explained by, the zip the error screen saves, the mark that
 * tells the UI the data was rebuilt (`stop-wiping-database-on-open-error`). No Android classes: JVM-tested.
 */
object DataFiles {

    /** `<database>.bak_<epoch ms>`: the copy of a corrupted database, next to it (as upstream named it). */
    const val BACKUP_INFIX = ".bak_"

    /** The file in the no-backup directory that says the database was rebuilt; it holds the copy's name. */
    const val REBUILT_MARK = "crown_data_rebuilt"

    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    /** The files that hold the data: the database and its write-ahead log (the -shm index is rebuilt from both). */
    fun parts(db: File): List<File> = listOf(db, File(db.path + "-wal")).filter { it.isFile }

    /**
     * Copies [db] and its write-ahead log next to it as `<name>.bak_<now>` (and `-wal`) and checks that each copy has
     * the length of its source. Returns the copy of [db]; null when [db] is not a file or a copy could not be made
     * whole, and then no partial copy is left.
     */
    fun backup(db: File, now: Long): File? {
        if (!db.isFile) return null
        val target = File(db.parentFile, db.name + BACKUP_INFIX + now)
        val made = ArrayList<File>()
        return try {
            for (part in parts(db)) {
                val copy = File(target.path + part.name.removePrefix(db.name))
                if (!copy.createNewFile()) throw IOException("${copy.name} exists")
                made += copy
                part.inputStream().use { input ->
                    FileOutputStream(copy).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                if (copy.length() != part.length()) throw IOException("short copy of ${part.name}")
            }
            target
        } catch (e: Exception) {
            made.forEach { it.delete() }
            null
        }
    }

    /** The parts of a copy [backup] made: the copy of the database and its write-ahead log. */
    fun backupParts(copy: File): List<File> = listOf(copy, File(copy.path + "-wal")).filter { it.isFile }

    /** A copy's name as [markRebuilt] stores it: `<database>.bak_<digits>`, nothing that leads out of the folder. */
    fun isBackupName(database: String, name: String): Boolean =
        name.startsWith(database + BACKUP_INFIX) &&
            name.length > database.length + BACKUP_INFIX.length &&
            name.substring(database.length + BACKUP_INFIX.length).all { it in '0'..'9' }

    /** Writes [files] into one zip on [output], each under its own name. */
    fun writeZip(files: List<File>, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** `PRAGMA user_version` from the header of [db]; null when the file is unreadable or not an SQLite database. */
    fun userVersion(db: File): Int? = try {
        RandomAccessFile(db, "r").use { file ->
            if (file.length() < 100) return null
            val header = ByteArray(100)
            file.readFully(header)
            if (!header.copyOfRange(0, SQLITE_MAGIC.size).contentEquals(SQLITE_MAGIC)) return null
            ((header[60].toInt() and 0xff) shl 24) or ((header[61].toInt() and 0xff) shl 16) or
                ((header[62].toInt() and 0xff) shl 8) or (header[63].toInt() and 0xff)
        }
    } catch (e: Exception) {
        null
    }

    /** Why the database did not open, as the error screen explains it. */
    enum class Kind {
        /** Written by a newer Crown: this version has no way down (a downgrade). */
        NEWER_VERSION,

        /** No space left on the device. */
        NO_SPACE,

        /** SQLite reports the file damaged, and no copy of it could be made (it is kept). */
        CORRUPT,

        /** Anything else: no access to the file, an input/output error, a failed migration. */
        OTHER,
    }

    fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause?.takeIf { c -> c !== it } }
        .take(16)

    /** Room's "A migration from 21 to 20 was required but not found": the versions, or null for other messages. */
    private val MISSING_MIGRATION = Regex("migration from (\\d+) to (\\d+)", RegexOption.IGNORE_CASE)

    /**
     * Classifies [error], what opening the database threw. [fileVersion]: [userVersion] of the file, [appVersion]: the
     * schema version of this build; [isCorrupt] and [isFull] recognise SQLite's corruption and disk-full exceptions.
     */
    fun kindOf(
        error: Throwable,
        fileVersion: Int?,
        appVersion: Int,
        isCorrupt: (Throwable) -> Boolean,
        isFull: (Throwable) -> Boolean,
    ): Kind {
        val chain = causes(error).toList()
        if (fileVersion != null && fileVersion > appVersion) return Kind.NEWER_VERSION
        val downgrade = chain.any { cause ->
            val match = cause.message?.let { MISSING_MIGRATION.find(it) } ?: return@any false
            val (from, to) = match.destructured
            (from.toIntOrNull() ?: 0) > (to.toIntOrNull() ?: 0)
        }
        if (downgrade) return Kind.NEWER_VERSION
        if (chain.any(isFull) || chain.any { it.message?.contains("ENOSPC") == true }) return Kind.NO_SPACE
        if (chain.any(isCorrupt)) return Kind.CORRUPT
        return Kind.OTHER
    }

    /** One line for the error screen: the exception's class and message, the innermost one that has a message. */
    fun describe(error: Throwable): String {
        val cause = causes(error).lastOrNull { !it.message.isNullOrBlank() } ?: error
        return "${cause.javaClass.simpleName}: ${cause.message.orEmpty()}".trim().trimEnd(':').take(400)
    }

    // ------------------------------------------------------------------------------------------------ rebuilt mark

    /** Records in [dir] that the database was rebuilt and [copyName] is the copy of the damaged one. */
    fun markRebuilt(dir: File, copyName: String) {
        runCatching {
            dir.mkdirs()
            File(dir, REBUILT_MARK).writeText(copyName)
        }
    }

    /** The copy's name [markRebuilt] stored in [dir], "" when the copy has no valid name, null when there is no mark. */
    fun rebuilt(dir: File, database: String): String? {
        val mark = File(dir, REBUILT_MARK)
        if (!mark.isFile) return null
        val name = runCatching { mark.readText().trim() }.getOrDefault("")
        return if (isBackupName(database, name)) name else ""
    }

    fun clearRebuilt(dir: File) {
        File(dir, REBUILT_MARK).delete()
    }
}
