package io.nekohasekai.sagernet.simple.repair

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts of the «Починка» report: every check with its result, explanation and time, the fixes, the verdict. */
class RepairReportTest {

    private val device = ReportDevice(
        android = "17", sdk = 37, device = "Google sdk_gphone64_x86_64", app = "1.2.3 DEBUG", appVersionCode = 42,
        build = "oss debug", core = "sing-box 1.12.0, xray 25.1.1, go1.24", network = "wifi",
        state = mapOf("service" to "stopped", "mode" to "auto"),
    )

    private val results: Map<RepairStep, StepResult> = RepairPlan.ORDER.associateWith {
        StepResult.ok().copy(startedAt = 1_700_000_000_000L, elapsedMs = 120L)
    } + mapOf(
        RepairStep.SERVERS to StepResult(
            StepStatus.OK, servers = ServersSummary(5, 4, 1, 1, chosenId = 3L, currentAnswers = true), elapsedMs = 9000L,
        ),
        RepairStep.DNS_DIRECT to StepResult(StepStatus.FIXED, setOf(Finding.DNS_NO_ANSWER), fix = FixKind.DIRECT_DNS_WHITELISTED),
        RepairStep.SITES_VPN to StepResult.fail(Finding.VPN_SITES_DOWN),
        RepairStep.RU_DIRECT to StepResult.skipped(SkipReason.TIMED_OUT, note = "SocketTimeoutException: timeout"),
    )

    private val verdict = RepairVerdict.of(results)

    @Test
    fun jsonHasEveryCheckTheFixesAndTheDevice() {
        val json = JSONObject(RepairReport.json(results, verdict, device, 1_700_000_100_000L))
        val steps = json.getJSONArray("steps")
        assertEquals(RepairPlan.ORDER.size, steps.length())
        RepairPlan.ORDER.forEachIndexed { i, step ->
            val item = steps.getJSONObject(i)
            assertEquals(step.name.lowercase(), item.getString("step"))
            assertTrue(item.getString("explanation").isNotBlank())
            assertTrue(item.has("elapsed_ms"))
        }
        assertEquals("fixed", steps.getJSONObject(RepairStep.DNS_DIRECT.ordinal).getString("status"))
        assertEquals("timed_out", steps.getJSONObject(RepairStep.RU_DIRECT.ordinal).getString("skip_reason"))
        assertEquals(4, steps.getJSONObject(RepairStep.SERVERS.ordinal).getJSONObject("servers").getInt("answering"))
        assertEquals("direct_dns_whitelisted", json.getJSONArray("fixes").getJSONObject(0).getString("fix"))
        assertEquals("unknown", json.getJSONObject("verdict").getString("kind"))
        val d = json.getJSONObject("device")
        assertEquals("17", d.getString("android"))
        assertEquals("1.2.3 DEBUG", d.getString("app"))
        assertTrue(d.getString("core").startsWith("sing-box"))
        assertEquals("wifi", d.getString("network"))
    }

    @Test
    fun textHasEveryCheckWithItsExplanation() {
        val text = RepairReport.text(results, verdict, device, 1_700_000_100_000L)
        RepairPlan.ORDER.forEachIndexed { i, step -> assertTrue(text, text.contains("${i + 1}. ${step.name.lowercase()}: ")) }
        assertTrue(text.contains("sites do not open through the VPN"))
        assertTrue(text.contains("not checked: timed out"))
        assertTrue(text.contains("fixed: direct DNS changed to a whitelisted one"))
        assertTrue(text.contains("4 of 5 servers answer"))
        assertTrue(text.contains("network   : wifi"))
        assertTrue(text.contains("cause unknown"))
    }

    @Test
    fun theFixesShowTheSettingBeforeAndAfterAndAStopUndoesThem() {
        val change = FixChange("direct DNS", "192.0.2.1", "77.88.8.8, 77.88.8.1")
        val fixed = results + (RepairStep.DNS_DIRECT to StepResult(
            StepStatus.FIXED, setOf(Finding.DNS_NO_ANSWER), fix = FixKind.DIRECT_DNS_WHITELISTED, fixChange = change,
        ))
        val json = JSONObject(RepairReport.json(fixed, RepairVerdict.of(fixed), device, 0L))
        val stepChange = json.getJSONArray("steps").getJSONObject(RepairStep.DNS_DIRECT.ordinal).getJSONObject("fix_change")
        assertEquals("192.0.2.1", stepChange.getString("before"))
        assertEquals("77.88.8.8, 77.88.8.1", stepChange.getString("after"))
        assertEquals("direct DNS", json.getJSONArray("fixes").getJSONObject(0).getJSONObject("change").getString("setting"))
        val text = RepairReport.text(fixed, RepairVerdict.of(fixed), device, 0L)
        assertTrue(text, text.contains("fixed: direct DNS changed to a whitelisted one (direct DNS: 192.0.2.1 -> 77.88.8.8, 77.88.8.1)"))

        // Stopped after the fix: the check is back to its failure, the fix is listed as undone, not as made.
        val undone = results + (RepairStep.DNS_DIRECT to StepResult(
            StepStatus.FAIL, setOf(Finding.DNS_NO_ANSWER), undoneFix = FixKind.DIRECT_DNS_WHITELISTED, fixChange = change,
        ))
        val stoppedJson = JSONObject(RepairReport.json(undone, null, device, 0L))
        val step = stoppedJson.getJSONArray("steps").getJSONObject(RepairStep.DNS_DIRECT.ordinal)
        assertEquals("fail", step.getString("status"))
        assertEquals("direct_dns_whitelisted", step.getString("fix_undone"))
        assertEquals(0, stoppedJson.getJSONArray("fixes").length())
        val stoppedText = RepairReport.text(undone, null, device, 0L)
        assertTrue(stoppedText, stoppedText.contains("fix undone (the run was stopped): direct DNS changed to a whitelisted one"))
    }

    @Test
    fun theVpnThatDidNotConnectIsExplained() {
        val result = StepResult.skipped(SkipReason.VPN_START_FAILED)
        assertEquals("not checked: the VPN started for the checks did not connect", RepairReport.explain(RepairStep.SITES_VPN, result))
    }

    @Test
    fun aStoppedRunHasNoVerdict() {
        val stopped = RepairPlan.ORDER.associateWith { StepResult.skipped(SkipReason.STOPPED) }
        val json = JSONObject(RepairReport.json(stopped, null, device, 0L))
        assertEquals("stopped", json.getJSONObject("verdict").getString("kind"))
    }
}
