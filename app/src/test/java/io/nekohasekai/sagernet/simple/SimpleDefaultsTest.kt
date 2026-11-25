package io.nekohasekai.sagernet.simple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleDefaultsTest {

    @Test
    fun neverStoredTurnsItOn() {
        // A fresh install, or an upgrade where the setting was never written.
        assertEquals(true, SimpleDefaults.onByDefault(stored = null))
    }

    @Test
    fun aStoredValueStays() {
        // Off may be the classic default or the user's choice: once stored it stays, also across an upgrade from a
        // build with the simple UI (which already recorded the install as "fresh").
        assertNull(SimpleDefaults.onByDefault(stored = false))
        assertNull(SimpleDefaults.onByDefault(stored = true))
    }
}
