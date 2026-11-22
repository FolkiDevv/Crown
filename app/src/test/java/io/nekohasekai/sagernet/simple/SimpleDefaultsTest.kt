package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleDefaultsTest {

    @Test
    fun freshInstallTurnsItOn() {
        assertEquals(true, SimpleDefaults.onByDefault(fresh = true, stored = null))
        // A fresh install has nothing stored of its own; whatever is there is not a choice made in this install.
        assertEquals(true, SimpleDefaults.onByDefault(fresh = true, stored = false))
        assertEquals(true, SimpleDefaults.onByDefault(fresh = true, stored = true))
    }

    @Test
    fun upgradeKeepsAStoredValue() {
        // Off may be the classic default or the user's choice: it stays either way once stored.
        assertNull(SimpleDefaults.onByDefault(fresh = false, stored = false))
        assertNull(SimpleDefaults.onByDefault(fresh = false, stored = true))
    }

    @Test
    fun upgradeThatNeverStoredItTurnsItOn() {
        assertEquals(true, SimpleDefaults.onByDefault(fresh = false, stored = null))
    }
}
