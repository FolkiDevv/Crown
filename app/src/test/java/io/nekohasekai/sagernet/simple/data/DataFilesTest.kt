package io.nekohasekai.sagernet.simple.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** stop-wiping-database-on-open-error: the copy before a removal, the explanation of a failed open, the saved zip. */
class DataFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class Corrupt : RuntimeException("database disk image is malformed (code 11 SQLITE_CORRUPT)")
    private class Full : RuntimeException("database or disk is full (code 13 SQLITE_FULL)")

    private val isCorrupt: (Throwable) -> Boolean = { it is Corrupt }
    private val isFull: (Throwable) -> Boolean = { it is Full }

    private fun database(content: ByteArray = ByteArray(8192) { it.toByte() }, wal: ByteArray? = null): File {
        val db = File(folder.root, "sager_net.db")
        db.writeBytes(content)
        if (wal != null) File(db.path + "-wal").writeBytes(wal)
        return db
    }

    private fun header(version: Int): ByteArray {
        val bytes = ByteArray(4096)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[60] = (version ushr 24).toByte()
        bytes[61] = (version ushr 16).toByte()
        bytes[62] = (version ushr 8).toByte()
        bytes[63] = version.toByte()
        return bytes
    }

    // ------------------------------------------------------------------------------------------------ backup

    @Test
    fun backupCopiesTheDatabaseAndItsLog() {
        val db = database(wal = ByteArray(300) { 7 })
        val copy = DataFiles.backup(db, 1234)
        assertNotNull(copy)
        assertEquals("sager_net.db.bak_1234", copy!!.name)
        assertArrayEquals(db.readBytes(), copy.readBytes())
        assertArrayEquals(File(db.path + "-wal").readBytes(), File(copy.path + "-wal").readBytes())
        assertEquals(listOf(copy, File(copy.path + "-wal")), DataFiles.backupParts(copy))
        // the source is left as it was
        assertTrue(db.isFile)
    }

    @Test
    fun backupOfAMissingFileIsNoCopy() {
        assertNull(DataFiles.backup(File(folder.root, "sager_net.db"), 1))
    }

    @Test
    fun aFailedCopyLeavesNothingAndSaysSo() {
        val db = database(wal = ByteArray(300))
        // the copy of the log cannot be created: a directory holds its name
        assertTrue(File(folder.root, "sager_net.db.bak_5-wal").mkdir())
        assertNull(DataFiles.backup(db, 5))
        assertFalse(File(folder.root, "sager_net.db.bak_5").exists())
    }

    @Test
    fun anExistingCopyIsNotOverwritten() {
        val db = database()
        val earlier = File(folder.root, "sager_net.db.bak_9").apply { writeText("earlier") }
        assertNull(DataFiles.backup(db, 9))
        assertEquals("earlier", earlier.readText())
    }

    // ------------------------------------------------------------------------------------------------ header

    @Test
    fun userVersionIsReadFromTheHeader() {
        assertEquals(21, DataFiles.userVersion(database(header(21))))
        assertEquals(20, DataFiles.userVersion(database(header(20))))
    }

    @Test
    fun garbageHasNoVersion() {
        assertNull(DataFiles.userVersion(database(ByteArray(4096) { 0x55 })))
        assertNull(DataFiles.userVersion(database(ByteArray(10))))
        assertNull(DataFiles.userVersion(File(folder.root, "missing.db")))
    }

    // ------------------------------------------------------------------------------------------------ kind

    @Test
    fun aNewerFileIsADowngrade() {
        val error = IllegalStateException("anything")
        assertEquals(DataFiles.Kind.NEWER_VERSION, DataFiles.kindOf(error, 21, 20, isCorrupt, isFull))
    }

    @Test
    fun roomsMissingDowngradeIsADowngrade() {
        val error = RuntimeException(
            "wrapped", IllegalStateException("A migration from 21 to 20 was required but not found. Please provide…")
        )
        assertEquals(DataFiles.Kind.NEWER_VERSION, DataFiles.kindOf(error, null, 20, isCorrupt, isFull))
    }

    @Test
    fun aMissingUpgradeIsNoDowngrade() {
        val error = IllegalStateException("A migration from 19 to 20 was required but not found.")
        assertEquals(DataFiles.Kind.OTHER, DataFiles.kindOf(error, 19, 20, isCorrupt, isFull))
    }

    @Test
    fun aFullDiskIsNoSpace() {
        assertEquals(DataFiles.Kind.NO_SPACE, DataFiles.kindOf(RuntimeException(Full()), 20, 20, isCorrupt, isFull))
        val enospc = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(DataFiles.Kind.NO_SPACE, DataFiles.kindOf(enospc, 20, 20, isCorrupt, isFull))
    }

    @Test
    fun corruptionIsFoundInTheCauses() {
        assertEquals(DataFiles.Kind.CORRUPT, DataFiles.kindOf(RuntimeException(Corrupt()), null, 20, isCorrupt, isFull))
    }

    @Test
    fun aDeniedFileOrAFailedMigrationIsOther() {
        val denied = RuntimeException("unable to open database file (code 14 SQLITE_CANTOPEN): Permission denied")
        assertEquals(DataFiles.Kind.OTHER, DataFiles.kindOf(denied, null, 20, isCorrupt, isFull))
        val migration = RuntimeException("duplicate column name: pinned_json (code 1 SQLITE_ERROR)")
        assertEquals(DataFiles.Kind.OTHER, DataFiles.kindOf(migration, 19, 20, isCorrupt, isFull))
    }

    @Test
    fun describeNamesTheInnermostMessage() {
        val error = RuntimeException(IllegalStateException("inner reason"))
        assertEquals("IllegalStateException: inner reason", DataFiles.describe(error))
        assertEquals("IOException", DataFiles.describe(IOException()))
    }

    @Test
    fun aCauseLoopEnds() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertEquals(DataFiles.Kind.OTHER, DataFiles.kindOf(a, null, 20, isCorrupt, isFull))
    }

    // ------------------------------------------------------------------------------------------------ zip, mark

    @Test
    fun theZipHoldsEveryPart() {
        val db = database(wal = ByteArray(10) { 3 })
        val out = ByteArrayOutputStream()
        DataFiles.writeZip(DataFiles.parts(db), out)
        val names = HashMap<String, Int>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names[entry.name] = zip.readBytes().size
            }
        }
        assertEquals(mapOf("sager_net.db" to 8192, "sager_net.db-wal" to 10), names)
    }

    @Test
    fun theRebuiltMarkKeepsOnlyACopysName() {
        val dir = File(folder.root, "nobackup")
        assertNull(DataFiles.rebuilt(dir, "sager_net.db"))
        DataFiles.markRebuilt(dir, "sager_net.db.bak_1795744530361")
        assertEquals("sager_net.db.bak_1795744530361", DataFiles.rebuilt(dir, "sager_net.db"))
        DataFiles.markRebuilt(dir, "../../shared_prefs/x.xml")
        assertEquals("", DataFiles.rebuilt(dir, "sager_net.db"))
        DataFiles.clearRebuilt(dir)
        assertNull(DataFiles.rebuilt(dir, "sager_net.db"))
    }

    @Test
    fun backupNames() {
        assertTrue(DataFiles.isBackupName("sager_net.db", "sager_net.db.bak_1"))
        assertFalse(DataFiles.isBackupName("sager_net.db", "sager_net.db.bak_"))
        assertFalse(DataFiles.isBackupName("sager_net.db", "sager_net.db.bak_1/../x"))
        assertFalse(DataFiles.isBackupName("sager_net.db", "other.db.bak_1"))
    }
}
