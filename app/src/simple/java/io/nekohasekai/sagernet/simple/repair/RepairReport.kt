package io.nekohasekai.sagernet.simple.repair

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the report says about the device and the app (connection-repair "Экспорт отчёта"). */
data class ReportDevice(
    val android: String,
    val sdk: Int,
    val device: String,
    val app: String,
    val appVersionCode: Long,
    val build: String,
    /** The core's versions as the core logs them ("sing-box …, xray …"), else the core ref the app was built with. */
    val core: String,
    /** wifi, cellular, ethernet…; "none" without a network. */
    val network: String,
    /** This app's VPN, the traffic mode, the service state: one line each, no addresses. */
    val state: Map<String, String> = emptyMap(),
)

/**
 * The texts of the «Починка» report (design.md D8): `report.json` for tools and `report.txt` for a person, both with
 * every check in its order, its result, the explanation, the start and the time it took, the fixes and the verdict.
 * The explanations are in English: the report goes to a specialist, whatever the language of the phone. Pure Kotlin;
 * the cleaning ([ReportRedactor]) and the archive are the caller's.
 */
object RepairReport {

    const val FORMAT = 1

    fun json(results: Map<RepairStep, StepResult>, verdict: RepairVerdict?, device: ReportDevice, createdAt: Long): String {
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("created", iso(createdAt))
        root.put("verdict", verdictJson(verdict))
        root.put("device", JSONObject().apply {
            put("android", device.android)
            put("sdk", device.sdk)
            put("device", device.device)
            put("app", device.app)
            put("app_version_code", device.appVersionCode)
            put("build", device.build)
            put("core", device.core)
            put("network", device.network)
            device.state.forEach { (key, value) -> put(key, value) }
        })
        val steps = JSONArray()
        for (step in RepairPlan.ORDER) {
            val result = results[step]
            steps.put(JSONObject().apply {
                put("step", step.name.lowercase())
                if (result == null) {
                    put("status", "not_run")
                    return@apply
                }
                put("status", result.status.name.lowercase())
                put("explanation", explain(step, result))
                if (result.findings.isNotEmpty()) put("findings", JSONArray(result.findings.map { it.name.lowercase() }))
                result.skip?.let { put("skip_reason", it.name.lowercase()) }
                result.fix?.let { put("fix", it.name.lowercase()) }
                result.undoneFix?.let { put("fix_undone", it.name.lowercase()) }
                result.fixChange?.let { put("fix_change", changeJson(it)) }
                if (result.values.isNotEmpty()) put("values", JSONObject(result.values.toSortedMap()))
                result.servers?.let { s ->
                    put("servers", JSONObject().apply {
                        put("total", s.total)
                        put("answering", s.answering)
                        put("whitelist_total", s.whitelistTotal)
                        put("whitelist_answering", s.whitelistAnswering)
                        put("current_answers", s.currentAnswers)
                        put("chosen", s.chosenId != 0L)
                    })
                }
                result.note?.let { put("note", it) }
                if (result.startedAt > 0) put("started", iso(result.startedAt))
                put("elapsed_ms", result.elapsedMs)
            })
        }
        root.put("steps", steps)
        root.put("fixes", JSONArray(fixes(results, verdict).map { (step, fix) ->
            JSONObject().put("step", step.name.lowercase()).put("fix", fix.name.lowercase()).apply {
                results[step]?.fixChange?.let { put("change", changeJson(it)) }
            }
        }))
        return root.toString(2).replace("\\/", "/")
    }

    fun text(results: Map<RepairStep, StepResult>, verdict: RepairVerdict?, device: ReportDevice, createdAt: Long): String =
        buildString {
            appendLine("===== Throne «Починка» (connection repair) report =====")
            appendLine("created   : ${iso(createdAt)}")
            appendLine("verdict   : ${verdictLine(verdict)}")
            appendLine("android   : ${device.android} (SDK ${device.sdk}), ${device.device}")
            appendLine("app       : ${device.app} (versionCode ${device.appVersionCode}), ${device.build}")
            appendLine("core      : ${device.core}")
            appendLine("network   : ${device.network}")
            device.state.forEach { (key, value) -> appendLine("${key.padEnd(10)}: $value") }
            appendLine()
            appendLine("===== checks =====")
            RepairPlan.ORDER.forEachIndexed { i, step ->
                val result = results[step]
                val status = result?.status?.name?.lowercase() ?: "not run"
                append("${i + 1}. ${step.name.lowercase()}: $status")
                if (result != null) {
                    if (result.startedAt > 0) append(", started ${time(result.startedAt)}")
                    append(", ${result.elapsedMs} ms")
                }
                appendLine()
                if (result != null) {
                    appendLine("   ${explain(step, result)}")
                    result.fix?.let { appendLine("   fixed: ${fixText(it)}${changeText(result.fixChange)}") }
                    result.undoneFix?.let {
                        appendLine("   fix undone (the run was stopped): ${fixText(it)}${changeText(result.fixChange)}")
                    }
                    result.note?.let { appendLine("   note: $it") }
                }
            }
            val fixes = fixes(results, verdict)
            appendLine()
            appendLine("===== fixes =====")
            if (fixes.isEmpty()) appendLine("none")
            fixes.forEach { (step, fix) ->
                appendLine("${step.name.lowercase()}: ${fixText(fix)}${changeText(results[step]?.fixChange)}")
            }
        }

    /** The setting a fix changed, before and after (a server by its id, never its address). */
    private fun changeJson(change: FixChange): JSONObject =
        JSONObject().put("setting", change.setting).put("before", change.before).put("after", change.after)

    /**
     * "(server was 67, now 21)": no `key: value` form, which the log cleaning (LogRedactor's `server: …` rule) would
     * take for a server address and hide. Public IP addresses (DNS servers) are still hidden by the cleaning.
     */
    private fun changeText(change: FixChange?): String =
        change?.let { " (${it.setting} was ${it.before}, now ${it.after})" }.orEmpty()

    private fun fixes(results: Map<RepairStep, StepResult>, verdict: RepairVerdict?): List<Pair<RepairStep, FixKind>> =
        verdict?.fixes ?: RepairPlan.ORDER.mapNotNull { step -> results[step]?.fix?.let { step to it } }

    private fun verdictJson(verdict: RepairVerdict?): JSONObject = JSONObject().apply {
        when (verdict) {
            null -> put("kind", "stopped")
            is RepairVerdict.Fixed -> put("kind", "fixed")
            is RepairVerdict.CauseFound -> put("kind", "cause_found").put("cause", verdict.cause.name.lowercase())
            is RepairVerdict.Unknown -> put("kind", "unknown").put("nothing_found", verdict.nothingFound)
                .put("not_checked", verdict.notChecked)
        }
        put("text", verdictLine(verdict))
    }

    private fun verdictLine(verdict: RepairVerdict?): String = when (verdict) {
        null -> "none: the run was stopped before the end"
        is RepairVerdict.Fixed -> "fixed by the app"
        is RepairVerdict.CauseFound -> "cause found: ${causeText(verdict.cause)}"
        is RepairVerdict.Unknown -> when {
            !verdict.nothingFound -> "cause unknown: a check failed that no known cause explains"
            verdict.notChecked -> "cause unknown: the checks that ran found no problem, some did not run"
            else -> "cause unknown: no check found a problem"
        }
    }

    private fun causeText(cause: RepairVerdict.Cause): String = when (cause) {
        RepairVerdict.Cause.NO_INTERNET -> "no internet without the VPN"
        RepairVerdict.Cause.CLOCK -> "the device clock is off"
        RepairVerdict.Cause.OTHER_VPN -> "another app's VPN is active"
        RepairVerdict.Cause.PRIVATE_DNS -> "Android private DNS is set"
        RepairVerdict.Cause.SUBSCRIPTION_ENDED -> "the subscription ended (term or traffic)"
        RepairVerdict.Cause.SUBSCRIPTION_EMPTY -> "the subscription has no servers"
        RepairVerdict.Cause.NO_SERVERS -> "no servers added"
        RepairVerdict.Cause.WHITELISTS_NO_SERVERS -> "whitelists in the network, no servers for whitelists"
        RepairVerdict.Cause.WHITELISTS_SERVERS_DOWN -> "whitelists in the network, the servers for whitelists do not answer"
        RepairVerdict.Cause.ALL_SERVERS_DOWN -> "no server answers"
        RepairVerdict.Cause.CURRENT_SERVER_DOWN -> "the current server does not answer, others do"
        RepairVerdict.Cause.BATTERY -> "Android may stop the VPN in the background"
    }

    private fun fixText(fix: FixKind): String = when (fix) {
        FixKind.SWITCHED_SERVER -> "switched to a working server"
        FixKind.DIRECT_DNS_WHITELISTED -> "direct DNS changed to a whitelisted one"
        FixKind.VPN_DNS_ALTERNATIVE -> "DNS through the VPN changed to an alternative"
        FixKind.SUBSCRIPTION_UPDATED -> "subscription updated"
        FixKind.ROUTES_UPDATED -> "routing profiles updated"
        FixKind.MTU_LOWERED -> "MTU lowered to 1280"
    }

    /** One line on what a check found: the findings, the numbers, or why it was not done. */
    fun explain(step: RepairStep, result: StepResult): String {
        if (result.status == StepStatus.SKIPPED) return "not checked: ${skipText(result.skip)}"
        val parts = result.findings.map(::findingText).toMutableList()
        val values = result.values
        when (step) {
            RepairStep.INTERNET, RepairStep.RU_DIRECT -> if (RepairValues.ASKED in values) {
                parts += "${values[RepairValues.REACHED] ?: 0} of ${values[RepairValues.ASKED]} addresses answered"
            }

            RepairStep.CLOCK -> values[RepairValues.CLOCK_OFFSET_S]?.let { parts += "device clock minus reference: $it s" }
            RepairStep.SUBSCRIPTION -> values[RepairValues.DAYS_LEFT]?.let { parts += "$it days left" }
            RepairStep.SITES_VPN -> values[RepairValues.LATENCY_MS]?.let { parts += "delay $it ms" }
            RepairStep.SERVERS -> result.servers?.let { s ->
                parts += "${s.answering} of ${s.total} servers answer"
                if (s.whitelistTotal > 0) parts += "${s.whitelistAnswering} of ${s.whitelistTotal} servers for whitelists answer"
                parts += if (s.chosenId != 0L) {
                    if (s.currentAnswers) "the current server is used for the checks through the VPN"
                    else "another answering server is used for the checks through the VPN"
                } else {
                    "no server for the checks through the VPN"
                }
            }

            else -> Unit
        }
        if (parts.isEmpty()) parts += if (result.status == StepStatus.FAIL) "failed" else "no problem found"
        return parts.joinToString("; ")
    }

    private fun findingText(finding: Finding): String = when (finding) {
        Finding.NO_NETWORK -> "no network connection"
        Finding.NOTHING_ANSWERS -> "connected to a network, but nothing answers"
        Finding.WHITELISTS_FOUND -> "whitelists in the network: only allowed sites open"
        Finding.NO_WHITELISTS -> "no whitelists in the network"
        Finding.CLOCK_OFF -> "the device clock is off by more than a minute"
        Finding.PRIVATE_DNS -> "Android private DNS is set by hand"
        Finding.OTHER_VPN -> "another app's VPN is active"
        Finding.DNS_NO_ANSWER -> "the direct DNS server does not answer"
        Finding.SUBSCRIPTION_EXPIRED -> "the subscription has expired"
        Finding.TRAFFIC_EXHAUSTED -> "the subscription traffic is used up"
        Finding.SUBSCRIPTION_EMPTY -> "the subscription has no servers"
        Finding.SUBSCRIPTION_ENDS_SOON -> "the subscription ends soon"
        Finding.TRAFFIC_LOW -> "little subscription traffic left"
        Finding.NO_SERVERS -> "no servers added"
        Finding.NO_SERVER_ANSWERS -> "no server answers"
        Finding.CURRENT_SERVER_DOWN -> "the current server does not answer"
        Finding.VPN_DNS_NO_ANSWER -> "DNS through the VPN does not answer"
        Finding.VPN_SITES_DOWN -> "sites do not open through the VPN"
        Finding.RU_SITES_DOWN -> "Russian sites do not open directly"
        Finding.BATTERY_OPTIMIZED -> "battery optimization is on for the app"
        Finding.BACKGROUND_RESTRICTED -> "background activity is restricted for the app"
    }

    private fun skipText(reason: SkipReason?): String = when (reason) {
        SkipReason.NO_INTERNET -> "no internet"
        SkipReason.NO_WORKING_SERVER -> "no working server"
        SkipReason.OTHER_VPN -> "another app's VPN is active"
        SkipReason.VPN_OFF -> "the VPN is off"
        SkipReason.NO_VPN_PERMISSION -> "no VPN permission"
        SkipReason.VPN_START_FAILED -> "the VPN started for the checks did not connect"
        SkipReason.APP_BYPASSES_VPN -> "this app's own traffic bypasses the VPN"
        SkipReason.NO_SUBSCRIPTION -> "no subscription"
        SkipReason.UNSUPPORTED_DNS -> "the DNS server kind is not supported by the check"
        SkipReason.UNSUPPORTED_ANDROID -> "not available on this Android version"
        SkipReason.NO_TIME_SOURCE -> "no trusted host told the time"
        SkipReason.TESTS_BUSY -> "another test was running"
        SkipReason.TIMED_OUT -> "timed out"
        SkipReason.STOPPED -> "stopped by the user"
        SkipReason.ERROR, null -> "the check failed unexpectedly"
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun time(ms: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
}
