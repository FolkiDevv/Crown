package io.nekohasekai.sagernet.database

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * stop-wiping-database-on-open-error: Room no longer falls back to a destructive migration for a missing step, so the
 * hand-written and generated migrations together must lead from every version since [SagerDatabase.FIRST_MIGRATED] to
 * [SagerDatabase.VERSION]. A version bump without its migration fails here, not on the users' data.
 */
class MigrationChainTest {

    private fun autoMigrations(): List<Migration> {
        val found = ArrayList<Migration>()
        for (from in 1 until SagerDatabase.VERSION) for (to in from + 1..SagerDatabase.VERSION) {
            val name = "io.nekohasekai.sagernet.database.SagerDatabase_AutoMigration_${from}_${to}_Impl"
            val type = try {
                Class.forName(name)
            } catch (_: ClassNotFoundException) {
                continue
            }
            found += type.getDeclaredConstructor().newInstance() as Migration
        }
        return found
    }

    private fun all(): List<Migration> = SagerDatabase.MIGRATIONS.toList() + autoMigrations()

    @Test
    fun everyVersionReachesTheCurrentOne() {
        val steps = all().groupBy { it.startVersion }
        for (start in SagerDatabase.FIRST_MIGRATED until SagerDatabase.VERSION) {
            var version = start
            var hops = 0
            while (version < SagerDatabase.VERSION && hops++ < 100) {
                val next = steps[version]?.maxOf { it.endVersion }
                    ?: throw AssertionError("no migration from version $version (reached from $start)")
                assertTrue("migration from $version goes back", next > version)
                version = next
            }
            assertEquals("chain from $start", SagerDatabase.VERSION, version)
        }
    }

    @Test
    fun noMigrationGoesBeyondTheCurrentVersion() {
        for (migration in all()) {
            assertTrue(
                "${migration.startVersion} -> ${migration.endVersion}",
                migration.endVersion <= SagerDatabase.VERSION && migration.startVersion >= SagerDatabase.FIRST_MIGRATED,
            )
        }
    }

    @Test
    fun theGeneratedAutoMigrationsAreFound() {
        // @Database lists 8->9, 12->13 ... 16->17: if their class names change, the chain test would see gaps.
        assertTrue(autoMigrations().any { it.startVersion == 8 && it.endVersion == 9 })
    }
}
