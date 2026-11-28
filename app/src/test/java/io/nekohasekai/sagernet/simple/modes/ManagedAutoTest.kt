package io.nekohasekai.sagernet.simple.modes

import io.nekohasekai.sagernet.outbound.types.AutoSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The managed «Авто» selectors and the one move of their check settings (TrafficModes.ensureSelector). */
class ManagedAutoTest {

    private val groups = listOf(3L, 7L)
    private val tier = "^(?!.*(wl)).*$"
    private val done = ManagedAuto.TUNING_VERSION
    private val pending = ManagedAuto.PENDING_PASSIVE

    /** A selector as the simple UI made it before the check settings moved: the AutoSelector defaults. */
    private fun oldSelector() = AutoSelector().apply {
        name = "Авто"
        gids = ArrayList(groups)
        strategy = AutoSelector.STRATEGY_PRIORITY
        tiers = arrayListOf(AutoSelector.Tier(-1, tier))
    }

    /** What the database holds after a save: the JSON parsed again (normalize included). */
    private fun stored(selector: AutoSelector) = AutoSelector().apply { parseFromJson(selector.exportToJson()) }

    private fun update(selector: AutoSelector, tuning: Long) = ManagedAuto.update(selector, groups, tier, tuning)

    @Test
    fun newSelectorTakesTheFastSettings() {
        val selector = stored(ManagedAuto.create("Авто", groups, tier))
        assertTrue(selector.passive)
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, ManagedAuto.watchIntervalSec(selector, managed = true))
        assertEquals(groups, selector.gids)
        assertEquals(listOf(AutoSelector.Tier(-1, tier)), selector.tiers)
        assertTrue(selector.prioritized)
        // Left at the defaults on purpose.
        assertEquals(AutoSelector().expected, selector.expected)
        assertEquals(AutoSelector().intervalSec, selector.intervalSec)
    }

    /** Only a managed selector gets the short watch, and never above its own interval; others keep upstream's. */
    @Test
    fun watchIntervalOfTheConfig() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, ManagedAuto.watchIntervalSec(selector, managed = true))
        assertEquals(selector.watchIntervalSec, ManagedAuto.watchIntervalSec(selector, managed = false))
        assertEquals(AutoSelector.WATCH_INTERVAL_SEC, ManagedAuto.watchIntervalSec(selector, managed = false))
        selector.intervalSec = 3
        assertEquals(3, ManagedAuto.watchIntervalSec(selector, managed = true))
    }

    @Test
    fun existingSelectorAtTheOldDefaultMoves() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = true, tuning = pending), update(selector, 0L))
        assertTrue(stored(selector).passive)
    }

    @Test
    fun handEditedSettingsStay() {
        val selector = oldSelector().apply {
            expected = 2
            intervalSec = 120
        }
        assertEquals(ManagedAuto.Update(save = true, tuning = pending), update(selector, 0L))
        assertEquals(2, selector.expected)
        assertEquals(120, selector.intervalSec)
        // passive still at the old default moves.
        assertTrue(selector.passive)
    }

    @Test
    fun nothingToMoveIsDoneAtOnce() {
        val selector = oldSelector().apply { passive = true }
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, 0L))
    }

    /** The next ensure finds the move stored: it is done, and nothing changes. */
    @Test
    fun theMoveIsConfirmed() {
        val selector = oldSelector()
        update(selector, 0L)
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(stored(selector), pending))
    }

    /** A stale save of `:bg` put the whole old JSON back after the move: the next ensure moves again, once. */
    @Test
    fun aStaleSaveIsMovedAgain() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = true, tuning = done), update(selector, pending))
        assertTrue(selector.passive)
    }

    /** A marker of the builds that also moved a stored watch interval still confirms the passive move. */
    @Test
    fun oldMarkersAreConfirmed() {
        val stale = oldSelector()
        assertEquals(ManagedAuto.Update(save = true, tuning = done), update(stale, ManagedAuto.PENDING_BOTH))
        assertTrue(stale.passive)
        // Only the interval had moved: nothing left to move, passive stays as it is.
        val intervalOnly = oldSelector()
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(intervalOnly, ManagedAuto.PENDING_WATCH))
        assertFalse(intervalOnly.passive)
    }

    /** The second move is the last: passive turned off by hand after it is not turned on again. */
    @Test
    fun afterTheSecondMoveAHandEditStays() {
        val selector = oldSelector()
        val marker = update(selector, pending).tuning
        selector.passive = false
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, marker))
        assertFalse(selector.passive)
    }

    /** Once done, a setting the user puts back to the old value in the classic editor is not moved again. */
    @Test
    fun onceDoneTheOldValuesStay() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, done))
        assertFalse(selector.passive)
    }

    @Test
    fun groupsAndTiersAreStillKeptUpToDate() {
        val selector = oldSelector().apply { passive = true }
        assertTrue(ManagedAuto.update(selector, listOf(9L), "other", done).save)
        assertEquals(listOf(9L), selector.gids)
        assertEquals(listOf(AutoSelector.Tier(-1, "other")), selector.tiers)
        assertTrue(selector.passive)
    }
}
