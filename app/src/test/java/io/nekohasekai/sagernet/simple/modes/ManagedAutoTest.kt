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

    /** A selector as the simple UI made it before the check settings moved: the AutoSelector defaults. */
    private fun oldSelector() = AutoSelector().apply {
        name = "Авто"
        gids = ArrayList(groups)
        strategy = AutoSelector.STRATEGY_PRIORITY
        tiers = arrayListOf(AutoSelector.Tier(-1, tier))
    }

    /** What the database holds after a save: the JSON parsed again (normalize included). */
    private fun stored(selector: AutoSelector) = AutoSelector().apply { parseFromJson(selector.exportToJson()) }

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
        assertTrue(ManagedAuto.update(selector, groups, tier, tune = true))
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
        assertTrue(ManagedAuto.update(selector, groups, tier, tune = true))
        assertEquals(30, selector.watchIntervalSec)
        assertEquals(4, selector.dialRetries)
        assertEquals(2, selector.expected)
        assertEquals(120, selector.intervalSec)
        // passive still at the old default moves with the rest.
        assertTrue(selector.passive)
    }

    @Test
    fun nothingToMoveNothingToSave() {
        val selector = oldSelector().apply {
            passive = true
            watchIntervalSec = 10
        }
        assertFalse(ManagedAuto.update(selector, groups, tier, tune = true))
        assertEquals(10, selector.watchIntervalSec)
    }

    /** After the move a setting the user puts back to the old value in the classic editor is not moved again. */
    @Test
    fun onceMovedTheOldValuesStay() {
        val selector = oldSelector()
        assertFalse(ManagedAuto.update(selector, groups, tier, tune = false))
        assertFalse(selector.passive)
        assertEquals(15, selector.watchIntervalSec)
    }

    @Test
    fun groupsAndTiersAreStillKeptUpToDate() {
        val selector = oldSelector().apply { passive = true; watchIntervalSec = 5 }
        assertTrue(ManagedAuto.update(selector, listOf(9L), "other", tune = false))
        assertEquals(listOf(9L), selector.gids)
        assertEquals(listOf(AutoSelector.Tier(-1, "other")), selector.tiers)
        assertTrue(selector.passive)
        assertEquals(5, selector.watchIntervalSec)
    }
}
