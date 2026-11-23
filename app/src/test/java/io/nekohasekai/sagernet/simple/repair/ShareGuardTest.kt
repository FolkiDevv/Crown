package io.nekohasekai.sagernet.simple.repair

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether a share sheet that came back was ever shown (the background-start race of review #10). */
class ShareGuardTest {

    private var clock = 0L
    private val guard = ShareGuard { clock }

    @Test
    fun dismissedSheetIsDone() {
        guard.launched()
        clock += 4_000
        assertTrue(guard.cameBack(ok = false))
    }

    @Test
    fun chosenTargetIsDone() {
        guard.launched()
        clock += 300
        guard.stopped()
        assertTrue(guard.cameBack(ok = true))
    }

    /** Home pressed just before the start: Android blocked it, the app stopped right after, the hidden sheet came back. */
    @Test
    fun blockedStartIsOfferedAgain() {
        guard.launched()
        clock += 350
        guard.stopped()
        clock += 60_000
        assertFalse(guard.cameBack(ok = false))
        // The next start (on return) is judged afresh.
        guard.launched()
        clock += 5_000
        assertTrue(guard.cameBack(ok = false))
    }

    /** The sheet was up for a while, then the user left: Android closes it; that sheet was seen. */
    @Test
    fun leftLaterIsDone() {
        guard.launched()
        clock += ShareGuard.BLOCKED_WINDOW_MS + 500
        guard.stopped()
        assertTrue(guard.cameBack(ok = false))
    }

    /** A stop before the start (the archive got ready in the background) does not count for it. */
    @Test
    fun stopBeforeTheStartDoesNotCount() {
        guard.stopped()
        clock += 2_000
        guard.launched()
        clock += 3_000
        assertTrue(guard.cameBack(ok = false))
    }
}
