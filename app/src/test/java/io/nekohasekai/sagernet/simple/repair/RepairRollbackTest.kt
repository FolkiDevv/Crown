package io.nekohasekai.sagernet.simple.repair

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

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
        val onApply: suspend () -> Unit = {},
    ) : RepairFix {
        private val before = settings.values.getValue(key)

        override suspend fun apply() {
            settings.values[key] = value
            settings.log += "apply $key=$value"
            onApply()
        }

        override fun restore() {
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
}
