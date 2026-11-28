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
    private val pending = ManagedAuto.PENDING_BOTH

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
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, selector.watchIntervalSec)
        assertEquals(groups, selector.gids)
        assertEquals(listOf(AutoSelector.Tier(-1, tier)), selector.tiers)
        assertTrue(selector.prioritized)
        // Left at the defaults on purpose.
        assertEquals(AutoSelector().dialRetries, selector.dialRetries)
        assertEquals(AutoSelector().expected, selector.expected)
        assertEquals(AutoSelector().intervalSec, selector.intervalSec)
    }

    @Test
    fun existingSelectorAtTheOldDefaultsMoves() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = true, tuning = pending), update(selector, 0L))
        val saved = stored(selector)
        assertTrue(saved.passive)
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, saved.watchIntervalSec)
    }

    @Test
    fun handEditedSettingsStay() {
        val selector = oldSelector().apply {
            watchIntervalSec = 30
            dialRetries = 4
            expected = 2
            intervalSec = 120
        }
        assertEquals(ManagedAuto.Update(save = true, tuning = ManagedAuto.PENDING_PASSIVE), update(selector, 0L))
        assertEquals(30, selector.watchIntervalSec)
        assertEquals(4, selector.dialRetries)
        assertEquals(2, selector.expected)
        assertEquals(120, selector.intervalSec)
        // passive still at the old default moves with the rest.
        assertTrue(selector.passive)
    }

    @Test
    fun nothingToMoveIsDoneAtOnce() {
        val selector = oldSelector().apply {
            passive = true
            watchIntervalSec = 10
        }
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, 0L))
        assertEquals(10, selector.watchIntervalSec)
    }

    /** The next ensure finds the move stored: it is done, and nothing changes. */
    @Test
    fun theMoveIsConfirmed() {
        val selector = oldSelector()
        update(selector, 0L)
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(stored(selector), pending))
    }

    /** A stale save of `:bg` put the whole old JSON back after the move: the next ensure moves again. */
    @Test
    fun aStaleSaveIsMovedAgain() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = true, tuning = pending), update(selector, pending))
        assertTrue(selector.passive)
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, selector.watchIntervalSec)
    }

    /** Only passive moved (the interval was set by hand); a stale save put it back: it moves again. */
    @Test
    fun aStaleSaveOfAPartMoveIsMovedAgain() {
        val selector = oldSelector().apply { watchIntervalSec = 30 }
        val moved = ManagedAuto.Update(save = true, tuning = ManagedAuto.PENDING_PASSIVE)
        assertEquals(moved, update(selector, ManagedAuto.PENDING_PASSIVE))
        assertTrue(selector.passive)
        assertEquals(30, selector.watchIntervalSec)
        val confirmed = ManagedAuto.Update(save = false, tuning = done)
        assertEquals(confirmed, update(stored(selector), ManagedAuto.PENDING_PASSIVE))
    }

    /** Only the interval moved (passive was on already); a stale save put 15 s back: it moves again. */
    @Test
    fun aStaleSaveOfTheIntervalAloneIsMovedAgain() {
        val selector = oldSelector().apply { passive = true }
        assertEquals(ManagedAuto.Update(save = true, tuning = ManagedAuto.PENDING_WATCH), update(selector, 0L))
        val stale = oldSelector().apply { passive = true }
        val again = ManagedAuto.Update(save = true, tuning = ManagedAuto.PENDING_WATCH)
        assertEquals(again, update(stale, ManagedAuto.PENDING_WATCH))
        assertEquals(ManagedAuto.WATCH_INTERVAL_SEC, stale.watchIntervalSec)
    }

    /** Between the move and its confirmation the user turned passive judging off: that stays. */
    @Test
    fun aHandEditBeforeTheConfirmationStays() {
        val selector = oldSelector().apply { watchIntervalSec = ManagedAuto.WATCH_INTERVAL_SEC }
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, pending))
        assertFalse(selector.passive)
    }

    /** Once done, a setting the user puts back to the old value in the classic editor is not moved again. */
    @Test
    fun onceDoneTheOldValuesStay() {
        val selector = oldSelector()
        assertEquals(ManagedAuto.Update(save = false, tuning = done), update(selector, done))
        assertFalse(selector.passive)
        assertEquals(15, selector.watchIntervalSec)
    }

    @Test
    fun groupsAndTiersAreStillKeptUpToDate() {
        val selector = oldSelector().apply { passive = true; watchIntervalSec = 5 }
        assertTrue(ManagedAuto.update(selector, listOf(9L), "other", done).save)
        assertEquals(listOf(9L), selector.gids)
        assertEquals(listOf(AutoSelector.Tier(-1, "other")), selector.tiers)
        assertTrue(selector.passive)
        assertEquals(5, selector.watchIntervalSec)
    }
}
