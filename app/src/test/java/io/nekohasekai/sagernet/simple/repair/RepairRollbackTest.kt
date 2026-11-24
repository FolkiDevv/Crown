package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext

/**
 * The automatic fixes and their rollback (design.md D8, connection-repair "Безопасные автоматические исправления",
 * "Откат неудачного исправления", "Остановка") on fake checks and fake settings.
 */
class RepairRollbackTest {

    /** A fake app setting; [log] records every change and rollback in order. */
    private class Settings {
        val values = mutableMapOf("dns" to "local", "mtu" to "9000", "server" to "a", "vpn" to "off")
        val log = mutableListOf<String>()
    }

    /** A fix that sets [key] to [value]; [apply] may hang or throw to play a fix that does not finish. */
    private class FakeFix(
        val settings: Settings,
        override val kind: FixKind,
        val key: String,
        val value: String,
        /** How many restores throw before one works. */
        var failingRestores: Int = 0,
        val onApply: suspend () -> Unit = {},
    ) : RepairFix {
        private val before = settings.values.getValue(key)

        override val change get() = FixChange(key, before, value)

        override suspend fun apply() {
            settings.values[key] = value
            settings.log += "apply $key=$value"
            onApply()
        }

        // Compare and set, as the device fixes do: only while the setting still holds what this fix wrote.
        override fun stillApplied() = settings.values[key] == value

        override fun restore() {
            if (failingRestores > 0) {
                failingRestores--
                settings.log += "restore $key failed"
                throw IllegalStateException("cannot write $key")
            }
            settings.values[key] = before
            settings.log += "restore $key=$before"
        }
    }

    /**
     * Checks whose result follows the settings: [pass] says whether a check passes with the current values; the fixes
     * offered for a failure come from [fixes].
     */
    private class FakeChecks(
        val settings: Settings,
        val pass: (RepairStep, Map<String, String>) -> Boolean = { _, _ -> true },
        val fixes: (RepairStep) -> List<RepairFix> = { emptyList() },
        val before: suspend (RepairStep) -> Unit = {},
    ) : RepairChecks {
        val settles = mutableListOf<Boolean>()
        val ran = mutableListOf<RepairStep>()

        override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult {
            ran += step
            before(step)
            return if (pass(step, settings.values)) StepResult.ok() else StepResult.fail(failure(step))
        }

        override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>) =
            fixes(step)

        override suspend fun settle(wait: Boolean) {
            settles += wait
        }

        private fun failure(step: RepairStep) = when (step) {
            RepairStep.DNS_DIRECT -> Finding.DNS_NO_ANSWER
            RepairStep.DNS_VPN -> Finding.VPN_DNS_NO_ANSWER
            RepairStep.SITES_VPN -> Finding.VPN_SITES_DOWN
            else -> Finding.NOTHING_ANSWERS
        }
    }

    private fun engine(checks: RepairChecks, journal: RepairJournal = RepairJournal(), fixTimeout: Long = 5_000L) =
        RepairEngine(checks, journal, timeoutMs = { 60_000L }, fixTimeoutMs = fixTimeout)

    @Test
    fun aFixThatHelpsMarksTheCheckFixedAndStays() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val checks = FakeChecks(
            settings,
            pass = { step, values -> step != RepairStep.DNS_DIRECT || values["dns"] == "77.88.8.8" },
            fixes = { step ->
                if (step == RepairStep.DNS_DIRECT) listOf(FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8"))
                else emptyList()
            },
        )
        val results = engine(checks, journal).run { }.results
        val dns = results.getValue(RepairStep.DNS_DIRECT)
        assertEquals(StepStatus.FIXED, dns.status)
        assertEquals(FixKind.DIRECT_DNS_WHITELISTED, dns.fix)
        // The report shows the setting before and after.
        assertEquals(FixChange("dns", "local", "77.88.8.8"), dns.fixChange)
        // The problem found stays in the result: the explanation says what was wrong and what was done.
        assertTrue(dns.has(Finding.DNS_NO_ANSWER))
        assertEquals("77.88.8.8", settings.values["dns"])
        assertEquals(listOf("apply dns=77.88.8.8"), settings.log)
        assertEquals(1, journal.applied.size)
        // The check ran twice: the failure, then the recheck after the fix.
        assertEquals(2, checks.ran.count { it == RepairStep.DNS_DIRECT })
        val verdict = RepairVerdict.of(results)
        assertEquals(RepairVerdict.Fixed(listOf(RepairStep.DNS_DIRECT to FixKind.DIRECT_DNS_WHITELISTED)), verdict)
    }

    @Test
    fun aFixThatDoesNotHelpIsPutBackBeforeTheNextOneIsTried() = runBlocking {
        val settings = Settings()
        val checks = FakeChecks(
            settings,
            pass = { step, values -> step != RepairStep.SITES_VPN || values["mtu"] == "1280" },
            fixes = { step ->
                if (step == RepairStep.SITES_VPN) listOf(
                    FakeFix(settings, FixKind.VPN_DNS_ALTERNATIVE, "dns", "https://1.1.1.1/dns-query"),
                    FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280"),
                ) else emptyList()
            },
        )
        val sites = engine(checks).run { }.results.getValue(RepairStep.SITES_VPN)
        assertEquals(StepStatus.FIXED, sites.status)
        assertEquals(FixKind.MTU_LOWERED, sites.fix)
        assertEquals(
            listOf("apply dns=https://1.1.1.1/dns-query", "restore dns=local", "apply mtu=1280"),
            settings.log,
        )
        assertEquals("local", settings.values["dns"])
        assertEquals("1280", settings.values["mtu"])
        // The VPN took the restored value up (and was waited for) before the next fix.
        assertEquals(listOf(true), checks.settles)
    }

    @Test
    fun whenNoFixHelpsEverySettingIsBackAndTheCheckStaysFailed() = runBlocking {
        // connection-repair "Снижение MTU не помогло": the MTU goes back to what it was.
        val settings = Settings()
        val journal = RepairJournal()
        val checks = FakeChecks(
            settings,
            pass = { step, _ -> step != RepairStep.SITES_VPN },
            fixes = { step ->
                if (step == RepairStep.SITES_VPN) listOf(FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280"))
                else emptyList()
            },
        )
        val results = engine(checks, journal).run { }.results
        val sites = results.getValue(RepairStep.SITES_VPN)
        assertEquals(StepStatus.FAIL, sites.status)
        assertNull(sites.fix)
        assertEquals("9000", settings.values["mtu"])
        assertEquals(listOf("apply mtu=1280", "restore mtu=9000"), settings.log)
        assertTrue(journal.applied.isEmpty())
        assertTrue(RepairVerdict.of(results).fixes.isEmpty())
    }

    @Test
    fun aFixThatThrowsOrTakesTooLongIsPutBack() = runBlocking {
        val settings = Settings()
        val checks = FakeChecks(
            settings,
            pass = { step, values ->
                when (step) {
                    RepairStep.DNS_DIRECT -> values["dns"] == "77.88.8.8"
                    RepairStep.SITES_VPN -> values["mtu"] == "1280"
                    else -> true
                }
            },
            fixes = { step ->
                when (step) {
                    RepairStep.DNS_DIRECT -> listOf(
                        FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8") { error("no reload") },
                    )

                    RepairStep.SITES_VPN -> listOf(
                        FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280") { awaitCancellation() },
                    )

                    else -> emptyList()
                }
            },
        )
        val results = engine(checks, fixTimeout = 50L).run { }.results
        assertEquals(StepStatus.FAIL, results.getValue(RepairStep.DNS_DIRECT).status)
        assertEquals(StepStatus.FAIL, results.getValue(RepairStep.SITES_VPN).status)
        assertEquals("local", settings.values["dns"])
        assertEquals("9000", settings.values["mtu"])
        assertEquals(StepStatus.OK, results.getValue(RepairStep.BACKGROUND).status)
    }

    @Test
    fun stopPutsBackEveryChangeNewestFirstIncludingTheFixesThatHelped() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val inFix = CompletableDeferred<Unit>()
        val checks = FakeChecks(
            settings,
            pass = { step, values ->
                when (step) {
                    RepairStep.DNS_DIRECT -> values["dns"] == "77.88.8.8"
                    RepairStep.SITES_VPN -> false
                    else -> true
                }
            },
            fixes = { step ->
                when (step) {
                    RepairStep.DNS_DIRECT -> listOf(FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8"))
                    // The user stops «Починка» while this fix waits for the VPN to reload.
                    RepairStep.SITES_VPN -> listOf(
                        FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280") {
                            inFix.complete(Unit)
                            awaitCancellation()
                        },
                    )

                    else -> emptyList()
                }
            },
        )
        val published = mutableListOf<RepairProgress>()
        val run = async(Dispatchers.Default) { engine(checks, journal, fixTimeout = 60_000L).run { published += it } }
        inFix.await()
        run.cancel()
        val stopped = try {
            run.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        assertEquals(
            listOf("apply dns=77.88.8.8", "apply mtu=1280", "restore mtu=9000", "restore dns=local"),
            settings.log,
        )
        assertEquals(mapOf("dns" to "local", "mtu" to "9000", "server" to "a", "vpn" to "off"), settings.values)
        assertTrue(journal.applied.isEmpty())
        // The VPN is asked to take the restored values up, without waiting (the run is over).
        assertEquals(listOf(false), checks.settles)
        // The screen saw the rollback, then the stopped run.
        assertTrue(published.any { it.undoing })
        assertFalse(published.last().undoing)
        assertEquals(SkipReason.STOPPED, published.last().results.getValue(RepairStep.SITES_VPN).skip)
        // The check fixed before the stop no longer claims the fix: its own result, and that the fix was undone.
        val dns = published.last().results.getValue(RepairStep.DNS_DIRECT)
        assertEquals(StepStatus.FAIL, dns.status)
        assertNull(dns.fix)
        assertEquals(FixKind.DIRECT_DNS_WHITELISTED, dns.undoneFix)
        assertTrue(dns.has(Finding.DNS_NO_ANSWER))
    }

    @Test
    fun aStopWithNothingChangedHasNoRollback() = runBlocking {
        val settings = Settings()
        val reached = CompletableDeferred<Unit>()
        val checks = FakeChecks(settings, before = { step ->
            if (step == RepairStep.SERVERS) {
                reached.complete(Unit)
                awaitCancellation()
            }
        })
        val published = mutableListOf<RepairProgress>()
        val run = async(Dispatchers.Default) { engine(checks).run { published += it } }
        reached.await()
        run.cancel()
        try {
            run.await()
        } catch (e: CancellationException) {
            Unit
        }
        assertTrue(settings.log.isEmpty())
        assertTrue(checks.settles.isEmpty())
        assertTrue(published.none { it.undoing })
    }

    /** The VPN started for the checks through it: kept only while «Сайты через VPN» passes. */
    private fun vpnRaisingChecks(settings: Settings, journal: RepairJournal, sitesWork: Boolean) = FakeChecks(
        settings,
        pass = { step, _ -> step != RepairStep.SITES_VPN || sitesWork },
        before = { step ->
            if (step == RepairStep.DNS_VPN && settings.values["vpn"] == "off") {
                journal.record(step, kind = null, keepIf = RepairStep.SITES_VPN) {
                    settings.values["vpn"] = "off"
                    settings.log += "stop vpn"
                }
                settings.values["vpn"] = "on"
                settings.log += "start vpn"
            }
        },
    )

    @Test
    fun theVpnStartedForTheChecksStaysWhenTheSitesOpen() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val results = engine(vpnRaisingChecks(settings, journal, sitesWork = true), journal).run { }.results
        assertEquals(StepStatus.OK, results.getValue(RepairStep.SITES_VPN).status)
        assertEquals("on", settings.values["vpn"])
        assertEquals(listOf("start vpn"), settings.log)
        // Not a fix of its own: the verdict lists none.
        assertTrue(RepairVerdict.of(results).fixes.isEmpty())
    }

    @Test
    fun theVpnStartedForTheChecksIsStoppedWhenTheSitesStillFail() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val checks = vpnRaisingChecks(settings, journal, sitesWork = false)
        val results = engine(checks, journal).run { }.results
        assertEquals(StepStatus.FAIL, results.getValue(RepairStep.SITES_VPN).status)
        assertEquals("off", settings.values["vpn"])
        assertEquals(listOf("start vpn", "stop vpn"), settings.log)
        assertTrue(journal.applied.isEmpty())
    }

    @Test
    fun theJournalPutsEachChangeBackOnce() {
        val journal = RepairJournal()
        val log = mutableListOf<String>()
        val first = journal.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED) { log += "dns" }
        journal.record(RepairStep.SERVERS, FixKind.SWITCHED_SERVER) { log += "server" }
        journal.record(RepairStep.DNS_VPN, null, keepIf = RepairStep.SITES_VPN) { log += "vpn" }
        assertTrue(journal.restore(first))
        assertFalse(journal.restore(first))
        assertEquals(listOf("vpn"), journal.restoreKeptBy(RepairStep.SITES_VPN).map { "vpn" })
        assertEquals(1, journal.restoreAll().size)
        assertTrue(journal.restoreAll().isEmpty())
        assertEquals(listOf("dns", "vpn", "server"), log)
    }

    @Test
    fun aRestoreThatThrowsStopsNoneOfTheOthersAndIsTriedAgainLater() {
        val failed = mutableListOf<RepairStep>()
        val journal = RepairJournal(onRestoreFailed = { entry, _ -> failed += entry.step })
        val log = mutableListOf<String>()
        var serverFails = 1
        journal.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED) { log += "dns" }
        journal.record(RepairStep.SERVERS, FixKind.SWITCHED_SERVER) {
            if (serverFails-- > 0) error("cannot write the selection")
            log += "server"
        }
        journal.record(RepairStep.SITES_VPN, FixKind.MTU_LOWERED) { log += "mtu" }
        assertEquals(listOf(RepairStep.SITES_VPN, RepairStep.DNS_DIRECT), journal.restoreAll().map { it.step })
        assertEquals(listOf("mtu", "dns"), log)
        assertEquals(listOf(RepairStep.SERVERS), failed)
        // The failed one is still in effect, so the next rollback tries it again.
        assertEquals(listOf(RepairStep.SERVERS), journal.applied.map { it.step })
        assertEquals(listOf(RepairStep.SERVERS), journal.restoreAll().map { it.step })
        assertTrue(journal.applied.isEmpty())
        assertEquals(listOf("mtu", "dns", "server"), log)
    }

    @Test
    fun aFailedRestoreDuringTheStopStillSettlesThePutBackValuesAndEndsTheRun() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val inFix = CompletableDeferred<Unit>()
        val checks = FakeChecks(
            settings,
            pass = { step, values ->
                when (step) {
                    RepairStep.DNS_DIRECT -> values["dns"] == "77.88.8.8"
                    RepairStep.SITES_VPN -> false
                    else -> true
                }
            },
            fixes = { step ->
                when (step) {
                    RepairStep.DNS_DIRECT -> listOf(
                        FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8", failingRestores = 1),
                    )

                    RepairStep.SITES_VPN -> listOf(
                        FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280") {
                            inFix.complete(Unit)
                            awaitCancellation()
                        },
                    )

                    else -> emptyList()
                }
            },
        )
        val published = mutableListOf<RepairProgress>()
        val run = async(Dispatchers.Default) { engine(checks, journal, fixTimeout = 60_000L).run { published += it } }
        inFix.await()
        run.cancel()
        val stopped = try {
            run.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(stopped)
        // The MTU went back although the DNS restore threw after it; the VPN was asked to take it up.
        assertEquals("9000", settings.values["mtu"])
        assertEquals(listOf(false), checks.settles)
        assertFalse(published.last().undoing)
        // The DNS change is still in effect and recorded, so it can be put back by a later rollback.
        assertEquals(listOf(RepairStep.DNS_DIRECT), journal.applied.map { it.step })
        assertEquals(listOf(RepairStep.DNS_DIRECT), journal.unrestored.map { it.step })
        // The screen and the report do not claim it undone: it is still «исправлено»; the MTU fix (put back) is gone.
        val dns = published.last().results.getValue(RepairStep.DNS_DIRECT)
        assertEquals(StepStatus.FIXED, dns.status)
        assertEquals(FixKind.DIRECT_DNS_WHITELISTED, dns.fix)
        assertNull(dns.undoneFix)
        journal.restoreAll()
        assertEquals("local", settings.values["dns"])
    }

    @Test
    fun aChangeAStoppedRunCouldNotPutBackIsPutBackBeforeTheNextRun() = runBlocking {
        // The view model's lifecycle: a run stops, one restore throws, the run ends; the next run first retries it.
        val settings = Settings()
        val unrestored = UnrestoredChanges()
        val inFix = CompletableDeferred<Unit>()
        val firstChecks = FakeChecks(
            settings,
            pass = { step, values ->
                when (step) {
                    RepairStep.DNS_DIRECT -> values["dns"] == "77.88.8.8"
                    RepairStep.SITES_VPN -> false
                    else -> true
                }
            },
            fixes = { step ->
                when (step) {
                    RepairStep.DNS_DIRECT -> listOf(
                        FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8", failingRestores = 1),
                    )

                    RepairStep.SITES_VPN -> listOf(
                        FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280") {
                            inFix.complete(Unit)
                            awaitCancellation()
                        },
                    )

                    else -> emptyList()
                }
            },
        )
        val firstJournal = RepairJournal()
        val first = async(Dispatchers.Default) {
            try {
                engine(firstChecks, firstJournal, fixTimeout = 60_000L).run { }
            } finally {
                unrestored.keep(firstJournal, firstChecks)
            }
        }
        inFix.await()
        first.cancel()
        try {
            first.await()
        } catch (e: CancellationException) {
            Unit
        }
        assertTrue(unrestored.pending)
        assertEquals("77.88.8.8", settings.values["dns"])

        // The next run: the retry comes first, the values reach the VPN through the checks that made the change.
        unrestored.retry()
        assertTrue(!unrestored.pending)
        assertEquals("local", settings.values["dns"])
        assertEquals(listOf(false, true), firstChecks.settles)
        // Nothing left: a second retry does nothing.
        unrestored.retry()
        assertEquals(listOf(false, true), firstChecks.settles)
    }

    /** Runs dispatched coroutines only when the test says so: deterministic queued starts, no real time. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(tasks) { tasks.addLast(block) }
        }

        fun runAll() {
            while (true) {
                val task = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return
                task.run()
            }
        }
    }

    /** [stopB] runs right after B is launched; it may let B reach its wait first ([ManualDispatcher.runAll]). */
    private fun serialRunsOrder(stopB: (Job, ManualDispatcher) -> Unit): List<String> {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val serial = SerialRuns(scope)
        val events = mutableListOf<String>()
        val rollback = CompletableDeferred<Unit>()
        // Run A is stopped and puts its changes back (not cancellable), until the test lets the rollback end.
        val a = serial.launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    rollback.await()
                    events += "A rolled back"
                }
            }
        }
        dispatcher.runAll()
        a.cancel()
        dispatcher.runAll()
        // B's body runs once its wait is over (already cancelled, it ends as stopped — the view model's run then
        // publishes «Проверка остановлена»); it must not run before A's rollback.
        val b = serial.launch { events += if (isActive) "B started" else "B ended stopped" }
        stopB(b, dispatcher)
        val c = serial.launch { events += "C started" }
        dispatcher.runAll()
        assertTrue("nothing may start while A rolls back: $events", events.isEmpty())
        rollback.complete(Unit)
        dispatcher.runAll()
        assertTrue(b.isCompleted && c.isCompleted)
        scope.cancel()
        return events
    }

    @Test
    fun aRunCancelledBeforeItStartedStillHoldsTheNextRunBackUntilTheRollback() {
        // B is cancelled while queued, before its body could start: C must still wait for A's rollback.
        assertEquals(listOf("A rolled back", "B ended stopped", "C started"), serialRunsOrder { b, _ -> b.cancel() })
    }

    @Test
    fun aRunStoppedWhileItWaitsKeepsTheRollbackItWaitedFor() {
        // B is let run until it waits for A (A's rollback held), and is stopped there.
        val order = serialRunsOrder { b, dispatcher ->
            dispatcher.runAll()
            assertTrue(b.isActive)
            b.cancel()
        }
        assertEquals(listOf("A rolled back", "B ended stopped", "C started"), order)
    }

    @Test
    fun aRunNotStoppedStartsAfterTheRollbackToo() {
        assertEquals(listOf("A rolled back", "B started", "C started"), serialRunsOrder { _, _ -> })
    }

    @Test
    fun aSupersededChangeIsNotPutBackOverANewerValue() = runBlocking {
        // The stop's restore and the first retry both fail; the next run then sets the same setting; the later retry
        // must not write the old value over the newer one (compare and set).
        val settings = Settings()
        val fix = FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8", failingRestores = 2)
        fix.apply()
        val superseded = mutableListOf<RepairStep>()
        val journal = RepairJournal()
        journal.record(RepairStep.DNS_DIRECT, fix.kind, stillOurs = fix::stillApplied, restore = fix::restore)
        assertTrue(journal.restoreAll().isEmpty())
        val checks = FakeChecks(settings)
        // The carry reports the superseded change (the run journal handed it over).
        val unrestored = UnrestoredChanges(onSuperseded = { superseded += it.step })
        unrestored.keep(journal, checks)
        unrestored.retry()
        assertTrue(unrestored.pending)
        settings.values["dns"] = "9.9.9.9"
        unrestored.retry()
        assertEquals("9.9.9.9", settings.values["dns"])
        assertTrue(!unrestored.pending)
        assertEquals(listOf(RepairStep.DNS_DIRECT), superseded)
        assertTrue(settings.log.none { it.startsWith("restore dns=") })
    }

    @Test
    fun aChangeStillInPlaceIsPutBackByTheRetry() = runBlocking {
        val settings = Settings()
        val fix = FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8", failingRestores = 2)
        fix.apply()
        val journal = RepairJournal()
        journal.record(RepairStep.DNS_DIRECT, fix.kind, stillOurs = fix::stillApplied, restore = fix::restore)
        journal.restoreAll()
        val unrestored = UnrestoredChanges()
        unrestored.keep(journal, FakeChecks(settings))
        unrestored.retry()
        unrestored.retry()
        assertEquals("local", settings.values["dns"])
        assertTrue(!unrestored.pending)
    }

    @Test
    fun aFinishedRunKeepsItsFixesAndCarriesNothingOver() = runBlocking {
        val settings = Settings()
        val journal = RepairJournal()
        val checks = FakeChecks(
            settings,
            pass = { step, values -> step != RepairStep.DNS_DIRECT || values["dns"] == "77.88.8.8" },
            fixes = { step ->
                if (step == RepairStep.DNS_DIRECT) listOf(FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8"))
                else emptyList()
            },
        )
        engine(checks, journal).run { }
        val unrestored = UnrestoredChanges()
        unrestored.keep(journal, checks)
        // A kept fix is no failed restore: nothing to retry, the fix stays.
        assertTrue(!unrestored.pending)
        unrestored.retry()
        assertEquals("77.88.8.8", settings.values["dns"])
    }

    @Test
    fun aFixedCheckCarriesTheServersOfItsRecheck() = runBlocking {
        // No server answered, the subscription update made them answer: the checks through the VPN get the server the
        // recheck chose, not the «none» of the first look.
        val settings = Settings()
        val none = ServersSummary(total = 5, answering = 0, whitelistTotal = 0, whitelistAnswering = 0)
        val refreshed = ServersSummary(total = 6, answering = 4, whitelistTotal = 0, whitelistAnswering = 0, chosenId = 7L, currentAnswers = true)
        var seenByVpnCheck: Long? = null
        val checks = object : RepairChecks {
            var updated = false

            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult = when (step) {
                RepairStep.SERVERS -> if (updated) {
                    StepResult(StepStatus.OK, servers = refreshed)
                } else {
                    StepResult(StepStatus.FAIL, setOf(Finding.NO_SERVER_ANSWERS), servers = none)
                }

                RepairStep.DNS_VPN -> {
                    seenByVpnCheck = done[RepairStep.SERVERS]?.servers?.chosenId
                    StepResult.ok()
                }

                else -> StepResult.ok()
            }

            override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>) =
                if (step == RepairStep.SERVERS && !updated) listOf(object : RepairFix {
                    override val kind = FixKind.SUBSCRIPTION_UPDATED
                    override suspend fun apply() {
                        updated = true
                    }

                    override fun restore() = Unit
                }) else emptyList()
        }
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.FIXED, servers.status)
        assertEquals(FixKind.SUBSCRIPTION_UPDATED, servers.fix)
        assertEquals(refreshed, servers.servers)
        assertEquals(7L, seenByVpnCheck)
    }

    @Test
    fun aLightRecheckKeepsTheServersFound() = runBlocking {
        val found = ServersSummary(total = 5, answering = 3, whitelistTotal = 0, whitelistAnswering = 0, chosenId = 2L)
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult =
                if (step == RepairStep.SERVERS) StepResult(StepStatus.WARN, setOf(Finding.CURRENT_SERVER_DOWN), servers = found)
                else StepResult.ok()

            override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>) =
                if (step == RepairStep.SERVERS && result.status == StepStatus.WARN) listOf(object : RepairFix {
                    override val kind = FixKind.SWITCHED_SERVER
                    override suspend fun apply() = Unit
                    override fun restore() = Unit
                    override suspend fun recheck() = StepResult.ok()
                }) else emptyList()
        }
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.FIXED, servers.status)
        assertEquals(found, servers.servers)
    }

    @Test
    fun aSwitchPublishesTheNewCurrentServerAsAnswering() = runBlocking {
        // The switch's light recheck keeps the URL test's counts and says the new current server answers.
        val found = ServersSummary(total = 5, answering = 3, whitelistTotal = 0, whitelistAnswering = 0, chosenId = 2L)
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult =
                if (step == RepairStep.SERVERS) StepResult(StepStatus.WARN, setOf(Finding.CURRENT_SERVER_DOWN), servers = found)
                else StepResult.ok()

            override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>) =
                if (step == RepairStep.SERVERS && result.status == StepStatus.WARN) listOf(object : RepairFix {
                    override val kind = FixKind.SWITCHED_SERVER
                    override suspend fun apply() = Unit
                    override fun restore() = Unit
                    override suspend fun recheck() =
                        StepResult(StepStatus.OK, servers = found.copy(currentAnswers = true, chosenId = 2L))
                }) else emptyList()
        }
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.FIXED, servers.status)
        assertTrue(servers.servers!!.currentAnswers)
        assertEquals(3, servers.servers!!.answering)
        assertEquals(5, servers.servers!!.total)
    }

    /**
     * Servers: none answers; the subscription update makes B answer but not the selected A ([switchWorks]: whether
     * the switch to B then answers).
     */
    private class PartialServers(val switchWorks: Boolean, val blockAt: RepairStep? = null) : RepairChecks {
        val reached = CompletableDeferred<Unit>()
        var updated = false
        var switched = false
        val restored = mutableListOf<FixKind>()
        val none = ServersSummary(total = 2, answering = 0, whitelistTotal = 0, whitelistAnswering = 0)
        val onlyB = ServersSummary(total = 2, answering = 1, whitelistTotal = 0, whitelistAnswering = 0, chosenId = 2L)
        var seenByVpnCheck: ServersSummary? = null

        override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>): StepResult = when (step) {
            blockAt -> {
                reached.complete(Unit)
                awaitCancellation()
            }

            RepairStep.SERVERS -> if (updated) {
                StepResult(StepStatus.WARN, setOf(Finding.CURRENT_SERVER_DOWN), servers = onlyB)
            } else {
                StepResult(StepStatus.FAIL, setOf(Finding.NO_SERVER_ANSWERS), servers = none)
            }

            RepairStep.DNS_VPN -> {
                seenByVpnCheck = done[RepairStep.SERVERS]?.servers
                StepResult.ok()
            }

            else -> StepResult.ok()
        }

        override suspend fun fixesFor(step: RepairStep, result: StepResult, done: Map<RepairStep, StepResult>): List<RepairFix> {
            if (step != RepairStep.SERVERS) return emptyList()
            return if (result.has(Finding.NO_SERVER_ANSWERS)) listOf(object : RepairFix {
                override val kind = FixKind.SUBSCRIPTION_UPDATED
                override val reversible get() = false
                override suspend fun apply() {
                    updated = true
                }

                override fun restore() {
                    restored += kind
                }
            }) else listOf(object : RepairFix {
                override val kind = FixKind.SWITCHED_SERVER
                override suspend fun apply() {
                    switched = true
                }

                override fun restore() {
                    switched = false
                    restored += kind
                }

                override suspend fun recheck() = if (switchWorks) {
                    StepResult(StepStatus.OK, servers = onlyB.copy(currentAnswers = true))
                } else {
                    StepResult.fail(Finding.CURRENT_SERVER_DOWN)
                }
            })
        }
    }

    @Test
    fun anUpdateThatLeavesTheSelectedServerDownIsFollowedByTheSwitch() = runBlocking {
        val checks = PartialServers(switchWorks = true)
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.FIXED, servers.status)
        assertEquals(FixKind.SWITCHED_SERVER, servers.fix)
        assertTrue(checks.switched)
        // The checks through the VPN go on the server that answers, as the current one.
        assertEquals(2L, checks.seenByVpnCheck?.chosenId)
        assertTrue(checks.seenByVpnCheck!!.currentAnswers)
    }

    @Test
    fun anUpdateThatLeavesTheSelectedServerDownIsNoFixWithoutTheSwitch() = runBlocking {
        // Partial progress only: other servers answer now, the selected one does not and the switch did not help.
        val checks = PartialServers(switchWorks = false)
        val results = engine(checks).run { }.results
        val servers = results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.WARN, servers.status)
        assertTrue(servers.has(Finding.CURRENT_SERVER_DOWN))
        assertNull(servers.fix)
        assertEquals(listOf(FixKind.SWITCHED_SERVER), checks.restored)
        assertEquals(RepairVerdict.Cause.CURRENT_SERVER_DOWN, (RepairVerdict.of(results) as RepairVerdict.CauseFound).cause)
    }

    @Test
    fun anUndoneSwitchShowsTheStateItActedOnAgain() = runBlocking {
        // The update made B answer, the switch to B fixed the check; a stop puts the switch back: the row shows the
        // partial state again — the selected server does not answer (not «текущий тоже» from the switch's recheck).
        val checks = PartialServers(switchWorks = true, blockAt = RepairStep.DNS_VPN)
        val published = mutableListOf<RepairProgress>()
        val run = async(Dispatchers.Default) { engine(checks).run { published += it } }
        checks.reached.await()
        run.cancel()
        try {
            run.await()
        } catch (e: CancellationException) {
            Unit
        }
        val servers = published.last().results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.WARN, servers.status)
        assertEquals(FixKind.SWITCHED_SERVER, servers.undoneFix)
        assertTrue(servers.has(Finding.CURRENT_SERVER_DOWN))
        assertFalse(servers.servers!!.currentAnswers)
        assertEquals(1, servers.servers!!.answering)
        assertEquals(listOf(FixKind.SWITCHED_SERVER, FixKind.SUBSCRIPTION_UPDATED), checks.restored)
    }

    @Test
    fun aStopUndoesOnlyFixesItReversedNotProviderContent() = runBlocking {
        // Routes refreshed (provider content, stays) fixed «Сайты через VPN»; the MTU fixed «DNS через VPN». A stop
        // during a later check reverses the MTU only: that row reads «отменено», the routes row stays «исправлено».
        val settings = Settings()
        val reached = CompletableDeferred<Unit>()
        val checks = FakeChecks(
            settings,
            pass = { step, values ->
                when (step) {
                    RepairStep.DNS_VPN -> values["mtu"] == "1280"
                    RepairStep.SITES_VPN -> values["routes"] == "new"
                    else -> true
                }
            },
            fixes = { step ->
                when (step) {
                    RepairStep.DNS_VPN -> listOf(FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280"))
                    RepairStep.SITES_VPN -> listOf(object : RepairFix {
                        override val kind = FixKind.ROUTES_UPDATED
                        override val reversible get() = false
                        override suspend fun apply() {
                            settings.values["routes"] = "new"
                        }

                        override fun restore() = Unit
                    })

                    else -> emptyList()
                }
            },
            before = { step ->
                if (step == RepairStep.RU_DIRECT) {
                    reached.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        settings.values["routes"] = "old"
        val published = mutableListOf<RepairProgress>()
        val run = async(Dispatchers.Default) { engine(checks).run { published += it } }
        reached.await()
        run.cancel()
        try {
            run.await()
        } catch (e: CancellationException) {
            Unit
        }
        val results = published.last().results
        assertEquals(FixKind.MTU_LOWERED, results.getValue(RepairStep.DNS_VPN).undoneFix)
        assertEquals(StepStatus.FIXED, results.getValue(RepairStep.SITES_VPN).status)
        assertNull(results.getValue(RepairStep.SITES_VPN).undoneFix)
        assertEquals("9000", settings.values["mtu"])
        assertEquals("new", settings.values["routes"])
    }

    @Test
    fun aChangeThatCannotBePutBackStopsFurtherFixesOfTheCheck() = runBlocking {
        // The first fix does not help and its restore fails (twice in the fix loop, once more when the check ends
        // failed): the second fix is not tried on top of it, and the change stays recorded as not put back.
        val settings = Settings()
        val checks = FakeChecks(
            settings,
            pass = { step, _ -> step != RepairStep.SITES_VPN },
            fixes = { step ->
                if (step == RepairStep.SITES_VPN) listOf(
                    FakeFix(settings, FixKind.VPN_DNS_ALTERNATIVE, "dns", "https://1.1.1.1/dns-query", failingRestores = 3),
                    FakeFix(settings, FixKind.MTU_LOWERED, "mtu", "1280"),
                ) else emptyList()
            },
        )
        val journal = RepairJournal()
        val sites = engine(checks, journal).run { }.results.getValue(RepairStep.SITES_VPN)
        assertEquals(StepStatus.FAIL, sites.status)
        assertTrue(sites.note!!.contains("could not be put back"))
        assertEquals("9000", settings.values["mtu"])
        assertTrue(settings.log.none { it.startsWith("apply mtu") })
        assertEquals(listOf(RepairStep.SITES_VPN), journal.unrestored.map { it.step })
    }

    @Test
    fun aFailedLookupOfTheFollowUpFixesLeavesTheWarning() = runBlocking {
        // The update makes B answer, the selected A still does not; looking up the follow-up fixes throws: the step
        // stays a warning (the current server is down), it is not «исправлено».
        val inner = PartialServers(switchWorks = true)
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>) = inner.run(step, done)
            override suspend fun fixesFor(
                step: RepairStep,
                result: StepResult,
                done: Map<RepairStep, StepResult>,
            ): List<RepairFix> =
                if (step == RepairStep.SERVERS && result.status == StepStatus.WARN) error("lookup failed")
                else inner.fixesFor(step, result, done)
        }
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.WARN, servers.status)
        assertTrue(servers.has(Finding.CURRENT_SERVER_DOWN))
        assertNull(servers.fix)
    }

    /** A setting another writer may change; the lock plays the settings database's write transaction. */
    private class LockedSetting(@Volatile var value: String) {
        val lock = java.util.concurrent.locks.ReentrantLock()
        fun atomically(block: () -> Unit) {
            lock.lock()
            try {
                block()
            } finally {
                lock.unlock()
            }
        }
    }

    @Test
    fun aWriterBeforeTheCompareAndSetSupersedesTheRestore() {
        val setting = LockedSetting("77.88.8.8")
        val journal = RepairJournal(atomically = setting::atomically)
        journal.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED, stillOurs = { setting.value == "77.88.8.8" }) {
            setting.value = "local"
        }
        // The other writer gets there first: the restore sees its value and keeps it.
        setting.value = "9.9.9.9"
        journal.restoreAll()
        assertEquals("9.9.9.9", setting.value)
    }

    @Test
    fun aWriterDuringTheCompareAndSetWaitsAndKeepsItsValue() {
        // The other writer tries while the check and the restore run: it waits for the transaction and writes after
        // it, so the newer value is the one that stays — never the old one over it.
        val setting = LockedSetting("77.88.8.8")
        val inside = java.util.concurrent.CountDownLatch(1)
        val tried = java.util.concurrent.CountDownLatch(1)
        var gotInWhileRestoring = true
        val writer = Thread {
            inside.await()
            // While the restore holds the transaction the writer cannot get in…
            gotInWhileRestoring = setting.lock.tryLock().also { if (it) setting.lock.unlock() }
            tried.countDown()
            // …and writes once it is over.
            setting.atomically { setting.value = "9.9.9.9" }
        }
        writer.start()
        val journal = RepairJournal(atomically = setting::atomically)
        journal.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED, stillOurs = { setting.value == "77.88.8.8" }) {
            inside.countDown()
            // The restore goes on only once the writer has tried (and failed) to get in.
            tried.await()
            setting.value = "local"
        }
        journal.restoreAll()
        writer.join(2_000L)
        assertFalse("the writer got in during the compare-and-set", gotInWhileRestoring)
        assertEquals("9.9.9.9", setting.value)
    }

    @Test
    fun anOlderChangeOfTheSameSettingWaitsForTheNewerRestore() {
        // A → B (first fix) and B → C (second fix) on one setting; the C → B restore fails: the A → B change must not
        // be dropped as superseded (it would see C) — it stays pending, and once C → B works, B → A follows.
        var value = "A"
        var newerFails = true
        val journal = RepairJournal()
        value = "B"
        journal.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED, stillOurs = { value == "B" }, key = "dns") {
            value = "A"
        }
        value = "C"
        journal.record(RepairStep.DNS_VPN, FixKind.VPN_DNS_ALTERNATIVE, stillOurs = { value == "C" }, key = "dns") {
            if (newerFails) error("cannot write") else value = "B"
        }
        assertTrue(journal.restoreAll().isEmpty())
        assertEquals("C", value)
        assertEquals(2, journal.retriable.size)
        newerFails = false
        assertEquals(2, journal.retryFailed().size)
        assertEquals("A", value)
        assertTrue(journal.applied.isEmpty())
    }

    @Test
    fun aWarningWithoutAFollowUpThatStillSaysTheProblemIsNoFix() = runBlocking {
        // The update makes B answer, A stays down, and no switch is offered (the selection already changed): the
        // step keeps the warning instead of falling through to «исправлено».
        val inner = PartialServers(switchWorks = true)
        val checks = object : RepairChecks {
            override suspend fun run(step: RepairStep, done: Map<RepairStep, StepResult>) = inner.run(step, done)
            override suspend fun fixesFor(
                step: RepairStep,
                result: StepResult,
                done: Map<RepairStep, StepResult>,
            ): List<RepairFix> = if (result.status == StepStatus.WARN) emptyList() else inner.fixesFor(step, result, done)
        }
        val servers = engine(checks).run { }.results.getValue(RepairStep.SERVERS)
        assertEquals(StepStatus.WARN, servers.status)
        assertTrue(servers.has(Finding.CURRENT_SERVER_DOWN))
        assertNull(servers.fix)
    }

    @Test
    fun aChangeBoundToItsRunIsNotRetriedByALaterRun() = runBlocking {
        // The VPN started for the checks could not be stopped by the stop: a later run must not stop the VPN the
        // user may have started since.
        val journal = RepairJournal()
        val stops = mutableListOf<String>()
        var fails = true
        journal.record(RepairStep.DNS_VPN, kind = null, keepIf = RepairStep.SITES_VPN, retriable = false) {
            if (fails) error("cannot stop") else stops += "stop"
        }
        journal.restoreAll()
        assertEquals(1, journal.unrestored.size)
        fails = false
        val unrestored = UnrestoredChanges()
        unrestored.keep(journal, FakeChecks(Settings()))
        assertFalse(unrestored.pending)
        unrestored.retry()
        assertTrue(stops.isEmpty())
    }

    @Test
    fun aRunStoppedBeforeItsStartDoesNotRetryAnEarlierChange() = runBlocking {
        val settings = Settings()
        val fix = FakeFix(settings, FixKind.DIRECT_DNS_WHITELISTED, "dns", "77.88.8.8", failingRestores = 1)
        fix.apply()
        val journal = RepairJournal()
        journal.record(RepairStep.DNS_DIRECT, fix.kind, stillOurs = fix::stillApplied, restore = fix::restore)
        journal.restoreAll()
        val unrestored = UnrestoredChanges()
        unrestored.keep(journal, FakeChecks(settings))
        // The run was stopped while it waited: it ends, the change stays for the next run.
        val stopped = launch {
            cancel()
            unrestored.retryUnlessCancelled()
        }
        stopped.join()
        assertTrue(unrestored.pending)
        assertEquals("77.88.8.8", settings.values["dns"])
        // The next run retries it.
        unrestored.retryUnlessCancelled()
        assertFalse(unrestored.pending)
        assertEquals("local", settings.values["dns"])
    }

    @Test
    fun aChainOfOneSettingAcrossRunsIsPutBackInOrder() = runBlocking {
        // Run 1 wrote A → B and could not put it back; run 2 then wrote B → C and could not either. The older change
        // must not be dropped as superseded while the newer one is still in place: both are carried in one journal.
        var value = "A"
        var firstFails = true
        var secondFails = true
        val carry = UnrestoredChanges()
        val first = RepairJournal()
        value = "B"
        first.record(RepairStep.DNS_DIRECT, FixKind.DIRECT_DNS_WHITELISTED, stillOurs = { value == "B" }, key = "dns") {
            if (firstFails) error("cannot write") else value = "A"
        }
        first.restoreAll()
        carry.keep(first, FakeChecks(Settings()))
        val second = RepairJournal()
        value = "C"
        second.record(RepairStep.DNS_VPN, FixKind.VPN_DNS_ALTERNATIVE, stillOurs = { value == "C" }, key = "dns") {
            if (secondFails) error("cannot write") else value = "B"
        }
        second.restoreAll()
        carry.keep(second, FakeChecks(Settings()))
        // Run 1's change could be put back now, run 2's still not: run 1's waits (it would see C and be dropped).
        firstFails = false
        carry.retry()
        assertEquals("C", value)
        assertTrue(carry.pending)
        secondFails = false
        carry.retry()
        assertEquals("A", value)
        assertFalse(carry.pending)
    }
}
