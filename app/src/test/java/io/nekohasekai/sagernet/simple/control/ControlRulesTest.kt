package io.nekohasekai.sagernet.simple.control

import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.simple.control.ControlRules.Command
import io.nekohasekai.sagernet.simple.control.ControlRules.Profile
import io.nekohasekai.sagernet.simple.control.ControlRules.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {

    private val existing = setOf(1L, 2L)
    private fun profile(raw: Long) = ControlRules.profile(raw) { it in existing }

    @Test
    fun profileIdIsCheckedAgainstTheDatabase() {
        assertEquals(Profile.None, profile(-1L))
        assertEquals(Profile.Existing(2L), profile(2L))
        assertEquals(Profile.Missing(999_999L), profile(999_999L))
        assertEquals(Profile.Missing(0L), profile(0L))
    }

    @Test
    fun aMissingProfileNeverChangesTheConnection() {
        // It used to select the id and stop the VPN with "profile_empty" (simple UI: «Соединение потеряно»).
        for (request in Request.entries) for (state in State.entries) {
            assertEquals("$request in $state", Command.Ignore, ControlRules.decide(request, state, profile(999_999L), 1L))
        }
    }

    @Test
    fun toggleIsTheUpstreamShortcut() {
        assertEquals(Command.Start(null), ControlRules.decide(Request.TOGGLE, State.Stopped, Profile.None, 1L))
        assertEquals(Command.Start(null), ControlRules.decide(Request.TOGGLE, State.Idle, Profile.None, 1L))
        assertEquals(Command.Start(2L), ControlRules.decide(Request.TOGGLE, State.Stopped, profile(2L), 1L))
        assertEquals(Command.Stop, ControlRules.decide(Request.TOGGLE, State.Connected, Profile.None, 1L))
        assertEquals(Command.Stop, ControlRules.decide(Request.TOGGLE, State.Connected, profile(1L), 1L))
        assertEquals(Command.Switch(2L), ControlRules.decide(Request.TOGGLE, State.Connected, profile(2L), 1L))
        assertEquals(Command.Stop, ControlRules.decide(Request.TOGGLE, State.Connecting, Profile.None, 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.TOGGLE, State.Stopping, Profile.None, 1L))
    }

    @Test
    fun connectNeverStops() {
        assertEquals(Command.Start(null), ControlRules.decide(Request.CONNECT, State.Stopped, Profile.None, 1L))
        assertEquals(Command.Start(2L), ControlRules.decide(Request.CONNECT, State.Idle, profile(2L), 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.CONNECT, State.Connected, Profile.None, 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.CONNECT, State.Connected, profile(1L), 1L))
        assertEquals(Command.Switch(2L), ControlRules.decide(Request.CONNECT, State.Connected, profile(2L), 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.CONNECT, State.Stopping, profile(2L), 1L))
    }

    @Test
    fun disconnectOnlyStops() {
        assertEquals(Command.Stop, ControlRules.decide(Request.DISCONNECT, State.Connected, Profile.None, 1L))
        assertEquals(Command.Stop, ControlRules.decide(Request.DISCONNECT, State.Connecting, profile(2L), 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.DISCONNECT, State.Stopped, Profile.None, 1L))
        assertEquals(Command.Ignore, ControlRules.decide(Request.DISCONNECT, State.Stopping, Profile.None, 1L))
    }

    @Test
    fun onlyBootBroadcastsCountAsABoot() {
        assertTrue(ControlRules.isBootAction("android.intent.action.BOOT_COMPLETED"))
        assertTrue(ControlRules.isBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(ControlRules.isBootAction("foo.bar"))
        assertFalse(ControlRules.isBootAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(ControlRules.isBootAction(null))
    }

    @Test
    fun onlyTheThreeActionsOfThisPackage() {
        val pkg = "dev.folki.crown"
        assertEquals(Request.CONNECT, ControlRules.request(pkg, "dev.folki.crown.action.CONNECT"))
        assertEquals(Request.DISCONNECT, ControlRules.request(pkg, "dev.folki.crown.action.DISCONNECT"))
        assertEquals(Request.TOGGLE, ControlRules.request(pkg, "dev.folki.crown.action.TOGGLE"))
        assertNull(ControlRules.request(pkg, "dev.folki.crown.debug.action.CONNECT"))
        assertNull(ControlRules.request(pkg, "io.nekohasekai.sagernet.CLOSE"))
        assertNull(ControlRules.request(pkg, null))
    }
}
