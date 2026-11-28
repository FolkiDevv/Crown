package io.nekohasekai.sagernet.simple.modes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningCoreTest {

    @Test
    fun theCoreOfTheChoiceNeedsNoReload() {
        assertNull(RunningCore.mismatch(runningProfile = 8, runningRoute = 3, selected = 8, expectedRoute = 3))
    }

    @Test
    fun anotherServerOrRoutingIsADifference() {
        assertEquals(RunningCore.Mismatch.SERVER, RunningCore.mismatch(7, 3, 8, 3))
        assertEquals(RunningCore.Mismatch.ROUTE, RunningCore.mismatch(8, 3, 8, 4))
    }

    @Test
    fun whatAReloadCouldNotChangeIsNoDifference() {
        // A custom full config runs no routing profile of the app.
        assertNull(RunningCore.mismatch(8, 0, 8, 4))
        // The selected server is gone: a reload would stop the service, not bring it.
        assertNull(RunningCore.mismatch(8, 3, 8, 0))
        assertNull(RunningCore.mismatch(7, 3, 8, 0))
        // Nothing selected: a reload would stop the service.
        assertNull(RunningCore.mismatch(8, 3, 0, 3))
    }

    @Test
    fun aCheckReloadsOncePerChoice() {
        val guard = RunningCore.ReloadGuard()
        val choice = RunningCore.Target(8, 3)
        assertTrue(guard.admit(choice))
        // The core still differs after that reload: it is not sent again.
        assertFalse(guard.admit(choice))
        assertFalse(guard.admit(choice))
        // Another choice is a new difference.
        assertTrue(guard.admit(RunningCore.Target(9, 3)))
    }

    @Test
    fun aCoreFoundRightClearsTheGuard() {
        val guard = RunningCore.ReloadGuard()
        val choice = RunningCore.Target(8, 3)
        assertTrue(guard.admit(choice))
        guard.settled()
        assertTrue(guard.admit(choice))
    }
}
