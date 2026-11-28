package io.nekohasekai.sagernet.simple

import io.nekohasekai.sagernet.utils.CrashHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val app = "dev.folki.crown"
    private val bg = "dev.folki.crown:bg"

    private val handled = mutableListOf<Throwable>()
    private var exits = 0
    private lateinit var savedExit: () -> Unit

    @Before
    fun setUp() {
        savedExit = CrashHandler.exit
        CrashHandler.exit = { exits++ }
        CrashHandler.reporting = false
    }

    @After
    fun tearDown() {
        CrashHandler.exit = savedExit
        CrashHandler.chainTo(null)
        CrashHandler.reporting = false
    }

    private fun marker(): CrashMarker? = CrashRules.decode(File(folder.root, "crash_report").takeIf { it.isFile }?.readText())

    @Test
    fun handlerWritesTheMarkerThenPassesTheCrashOnAndEnds() {
        CrashReport.install(folder.root, bg)
        CrashHandler.chainTo { _, e -> handled += e }
        val crash = IllegalStateException("boom")

        CrashHandler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf<Throwable>(crash), handled)
        // The fallback runs too: Android's handler never returns, any other must not leave the process alive.
        assertEquals(1, exits)
        val written = assertNotNullAndGet(marker())
        assertEquals(bg, written.process)
        assertEquals(IllegalStateException::class.java.name, written.exception)
        assertEquals(0, written.repeats)
    }

    @Test
    fun handlerEndsTheProcessWithoutAPreviousHandlerOrWhenItThrows() {
        CrashReport.install(folder.root, app)
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, exits)

        CrashHandler.chainTo { _, _ -> throw IllegalStateException("handler failed") }
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(2, exits)
    }

    @Test
    fun anErrorWhileLoggingStillPassesTheCrashOn() {
        CrashReport.install(folder.root, bg)
        CrashHandler.chainTo { _, e -> handled += e }
        // Its stack trace cannot be printed, like an out-of-memory crash.
        val crash = object : RuntimeException() {
            override fun printStackTrace(s: java.io.PrintWriter) = throw OutOfMemoryError()
        }

        CrashHandler.uncaughtException(Thread.currentThread(), crash)

        assertEquals(listOf<Throwable>(crash), handled)
        assertEquals(1, exits)
        assertNotNull(marker())
    }

    @Test
    fun handlerNeverChainsToItself() {
        CrashReport.install(folder.root, app)
        CrashHandler.chainTo { _, e -> handled += e }
        CrashHandler.chainTo(CrashHandler)
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, handled.size)
        assertEquals(1, exits)
    }

    @Test
    fun aCrashWhileTheLogIsSharedLeavesNoMarker() {
        CrashReport.install(folder.root, app)
        CrashHandler.chainTo { _, e -> handled += e }
        CrashHandler.reporting = true
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertNull(marker())
        assertEquals(1, handled.size)
        assertEquals(1, exits)
    }

    @Test
    fun aRepeatedCrashOfTheAppIsCounted() {
        CrashReport.install(folder.root, app)
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        val written = assertNotNullAndGet(marker())
        assertEquals(1, written.repeats)
        assertFalse(CrashRules.prompts(written))
    }

    @Test
    fun aMarkerMovedAsideStillCountsForARepeat() {
        CrashReport.install(folder.root, app)
        val now = System.currentTimeMillis()
        File(folder.root, "crash_report.seen").writeText(CrashRules.encode(CrashMarker(app, now - 1_000, "X", 0)))
        CrashHandler.uncaughtException(Thread.currentThread(), RuntimeException())
        assertEquals(1, assertNotNullAndGet(marker()).repeats)
    }

    @Test
    fun repeatsOnlyForTheAppWithinTheWindow() {
        val first = CrashRules.next(null, app, 1_000_000, "A")
        assertEquals(0, first.repeats)
        assertTrue(CrashRules.prompts(first))

        val second = CrashRules.next(first, app, 1_000_000 + CrashRules.REPEAT_MS - 1, "A")
        assertEquals(1, second.repeats)
        assertFalse(CrashRules.prompts(second))
        assertEquals(2, CrashRules.next(second, app, 1_000_000 + CrashRules.REPEAT_MS, "A").repeats)

        // Long after, or with the clock set back: a new crash, asked about again.
        assertEquals(0, CrashRules.next(first, app, 1_000_000 + CrashRules.REPEAT_MS, "A").repeats)
        assertEquals(0, CrashRules.next(first, app, 999_999, "A").repeats)

        // The service process never asks: its crash is a notification.
        val service = CrashRules.next(first, bg, 1_000_001, "A")
        assertEquals(0, service.repeats)
        assertFalse(CrashRules.prompts(service))
        assertEquals(0, CrashRules.next(service, app, 1_000_002, "A").repeats)
    }

    @Test
    fun theWatchdogProcessIsAServiceProcess() {
        val watch = "dev.folki.crown:watch"
        assertTrue(CrashRules.isApp(app))
        assertFalse(CrashRules.isApp(watch))
        assertFalse(CrashRules.isApp(bg))
        // Its crash is not a crash of the interface: a notification, never the dialog, and no repeat of the app.
        val crash = CrashRules.next(null, watch, 1_000_000, "A")
        assertFalse(CrashRules.prompts(crash))
        assertEquals(0, CrashRules.next(crash, app, 1_000_001, "A").repeats)
        assertEquals(0, CrashRules.next(CrashRules.next(null, app, 1_000_000, "A"), watch, 1_000_001, "A").repeats)
    }

    @Test
    fun markerRoundTripAndGarbage() {
        val crash = CrashMarker(bg, 1234L, "a.b\nc", 2)
        val decoded = assertNotNullAndGet(CrashRules.decode(CrashRules.encode(crash)))
        assertEquals(crash.copy(exception = "a.b c"), decoded)
        assertTrue(CrashRules.isBackground(decoded.process))

        assertNull(CrashRules.decode(null))
        assertNull(CrashRules.decode(""))
        assertNull(CrashRules.decode("1\n$bg\n12"))
        assertNull(CrashRules.decode("2\n$bg\n12\nX\n0"))
        assertNull(CrashRules.decode("1\n$bg\nnot a time\nX\n0"))
        assertNull(CrashRules.decode("1\n \n12\nX\n0"))
        assertEquals(0, assertNotNullAndGet(CrashRules.decode("1\n$app\n12\nX\n-4")).repeats)
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
