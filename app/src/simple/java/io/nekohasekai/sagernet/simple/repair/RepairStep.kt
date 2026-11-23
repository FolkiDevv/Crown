package io.nekohasekai.sagernet.simple.repair

/**
 * The checks of «Починка» in the order the spec gives them (connection-repair "Набор и порядок проверок"). Pure Kotlin:
 * the order, the dependencies ([RepairPlan]) and the verdict ([RepairVerdict]) are covered by JVM unit tests.
 */
enum class RepairStep {
    /** Интернет без VPN: the configured addresses through the physical network. */
    INTERNET,

    /** Белые списки в сети: the same answers, told apart by RegimeClassifier. */
    WHITELISTS,

    /** Время на устройстве: the `Date` header of a trusted host against the clock. */
    CLOCK,

    /** Частный DNS Android и другие VPN. */
    PRIVATE_DNS_VPN,

    /** DNS напрямую: the in-app direct DNS server through the physical network. */
    DNS_DIRECT,

    /** Подписка: what the provider said about it on the last update. */
    SUBSCRIPTION,

    /** Доступность серверов: a URL test of every server; chooses the server for the checks through the VPN. */
    SERVERS,

    /** DNS через VPN. */
    DNS_VPN,

    /** Сайты через VPN. */
    SITES_VPN,

    /** Российские сайты напрямую. */
    RU_DIRECT,

    /** Работа в фоне: the battery optimization. */
    BACKGROUND,
}

/** What a check found, as design.md D8 lists it: `ok / warn / fail / skipped(причина) / fixed(что сделано)`. */
enum class StepStatus {
    OK,

    /** Works, but something may get in the way (battery optimization, the subscription ends soon). */
    WARN,
    FAIL,

    /** «Не проверено» with a [SkipReason]: never an error (connection-repair "Зависимые проверки"). */
    SKIPPED,

    /** A problem the app fixed by itself (batch 8). */
    FIXED;

    /** Whether the checks that depend on this one can run. */
    val passed: Boolean get() = this == OK || this == WARN || this == FIXED
}

/** Why a check was not done. */
enum class SkipReason {
    /** «Интернет без VPN» failed. */
    NO_INTERNET,

    /** No server answered «Доступность серверов». */
    NO_WORKING_SERVER,

    /** The VPN of another app is active: this app does not raise its own (the system would revoke the other's). */
    OTHER_VPN,

    /** The VPN of this app is off; this version checks through the VPN only while it is on. */
    VPN_OFF,

    /** The user refused the VPN consent (batch 8 asks for it). */
    NO_VPN_PERMISSION,

    /** This app's own requests do not go through the VPN (per-app proxy without this app). */
    APP_BYPASSES_VPN,

    /** No subscription added: nothing to check. */
    NO_SUBSCRIPTION,

    /** The direct DNS server is of a kind the check does not speak (DoT, DoQ, DHCP…). */
    UNSUPPORTED_DNS,

    /** Not available on this Android version. */
    UNSUPPORTED_ANDROID,

    /** None of the trusted hosts told the time. */
    NO_TIME_SOURCE,

    /** Another test (the classic interface's) kept the test engine busy. */
    TESTS_BUSY,

    /** The check took longer than its time limit. */
    TIMED_OUT,

    /** The check itself failed unexpectedly. */
    ERROR,

    /** The user stopped «Починка» before this check. */
    STOPPED,
}

/** The details of a result that the explanation, the verdict and the report need. */
enum class Finding {
    // INTERNET
    NO_NETWORK,
    NOTHING_ANSWERS,

    // WHITELISTS
    WHITELISTS_FOUND,
    NO_WHITELISTS,

    // CLOCK
    CLOCK_OFF,

    // PRIVATE_DNS_VPN
    PRIVATE_DNS,
    OTHER_VPN,

    // DNS_DIRECT
    DNS_NO_ANSWER,

    // SUBSCRIPTION
    SUBSCRIPTION_EXPIRED,
    TRAFFIC_EXHAUSTED,
    SUBSCRIPTION_EMPTY,
    SUBSCRIPTION_ENDS_SOON,
    TRAFFIC_LOW,

    // SERVERS
    NO_SERVERS,
    NO_SERVER_ANSWERS,
    CURRENT_SERVER_DOWN,

    // DNS_VPN, SITES_VPN
    VPN_DNS_NO_ANSWER,
    VPN_SITES_DOWN,

    // RU_DIRECT
    RU_SITES_DOWN,

    // BACKGROUND
    BATTERY_OPTIMIZED,
    BACKGROUND_RESTRICTED,
}

/** The reversible fixes of batch 8 (connection-repair "Безопасные автоматические исправления"). */
enum class FixKind {
    SWITCHED_SERVER,
    DIRECT_DNS_WHITELISTED,
    VPN_DNS_ALTERNATIVE,
    SUBSCRIPTION_UPDATED,
    ROUTES_UPDATED,
    MTU_LOWERED,
}

/** What «Доступность серверов» found; the verdict tells the whitelist cases apart by it. */
data class ServersSummary(
    val total: Int,
    val answering: Int,
    /** Servers recognized as servers for whitelists (WhitelistKeywords). */
    val whitelistTotal: Int,
    val whitelistAnswering: Int,
    /** The server for the checks through the VPN: the current one if it answers, else the best answering; 0 = none. */
    val chosenId: Long = 0L,
    /** The current server answered (for «Авто»: any of its servers did). */
    val currentAnswers: Boolean = false,
)

/**
 * One check's result. [values] carries the numbers the explanation shows (minutes of clock skew, servers answering…);
 * [elapsedMs] and [startedAt] (epoch ms) go to the report.
 */
data class StepResult(
    val status: StepStatus,
    val findings: Set<Finding> = emptySet(),
    val skip: SkipReason? = null,
    val fix: FixKind? = null,
    val values: Map<String, Long> = emptyMap(),
    val servers: ServersSummary? = null,
    /** Free text for the report only (an error message); never shown as the explanation. */
    val note: String? = null,
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
) {
    fun has(finding: Finding) = finding in findings

    companion object {
        fun ok(vararg findings: Finding, values: Map<String, Long> = emptyMap()) =
            StepResult(StepStatus.OK, findings.toSet(), values = values)

        fun warn(vararg findings: Finding, values: Map<String, Long> = emptyMap()) =
            StepResult(StepStatus.WARN, findings.toSet(), values = values)

        fun fail(vararg findings: Finding, values: Map<String, Long> = emptyMap()) =
            StepResult(StepStatus.FAIL, findings.toSet(), values = values)

        fun skipped(reason: SkipReason, note: String? = null) = StepResult(StepStatus.SKIPPED, skip = reason, note = note)
    }
}

/** Keys of [StepResult.values]. */
object RepairValues {
    /** CLOCK: the device clock minus the reference, in seconds. */
    const val CLOCK_OFFSET_S = "clock_offset_s"

    /** INTERNET / RU_DIRECT: answering and asked addresses. */
    const val REACHED = "reached"
    const val ASKED = "asked"

    /** SUBSCRIPTION: days until the end. */
    const val DAYS_LEFT = "days_left"

    /** SITES_VPN: the delay through the VPN, ms. */
    const val LATENCY_MS = "latency_ms"
}

/**
 * The dependency table of the checks (design.md D8, connection-repair "Зависимые проверки"): a check whose
 * prerequisite did not pass is «не проверено» with the reason, never an error.
 */
object RepairPlan {

    val ORDER: List<RepairStep> = RepairStep.entries

    /** The checks each one needs to have passed. */
    val REQUIRES: Map<RepairStep, Set<RepairStep>> = mapOf(
        RepairStep.INTERNET to emptySet(),
        RepairStep.WHITELISTS to setOf(RepairStep.INTERNET),
        RepairStep.CLOCK to setOf(RepairStep.INTERNET),
        RepairStep.PRIVATE_DNS_VPN to emptySet(),
        RepairStep.DNS_DIRECT to setOf(RepairStep.INTERNET),
        RepairStep.SUBSCRIPTION to emptySet(),
        RepairStep.SERVERS to setOf(RepairStep.INTERNET),
        RepairStep.DNS_VPN to setOf(RepairStep.SERVERS, RepairStep.PRIVATE_DNS_VPN),
        RepairStep.SITES_VPN to setOf(RepairStep.SERVERS, RepairStep.PRIVATE_DNS_VPN),
        RepairStep.RU_DIRECT to setOf(RepairStep.INTERNET),
        RepairStep.BACKGROUND to emptySet(),
    )

    /** The checks through the VPN: they need a server and no VPN of another app. */
    val THROUGH_VPN: Set<RepairStep> = setOf(RepairStep.DNS_VPN, RepairStep.SITES_VPN)

    /** What a failed prerequisite means for the checks after it. */
    private fun reasonFor(failed: RepairStep): SkipReason = when (failed) {
        RepairStep.INTERNET -> SkipReason.NO_INTERNET
        RepairStep.SERVERS -> SkipReason.NO_WORKING_SERVER
        else -> SkipReason.ERROR
    }

    /**
     * Why [step] cannot run after [done], or null when it can. A prerequisite that was itself skipped passes its own
     * reason on (no internet → the checks through the VPN are «не проверено: нет интернета», not «нет сервера»). For the
     * checks through the VPN another app's VPN comes before a missing server: it is what the user has to resolve.
     */
    fun skipReason(step: RepairStep, done: Map<RepairStep, StepResult>): SkipReason? {
        val requires = REQUIRES.getValue(step)
        fun blocked(prerequisite: RepairStep): SkipReason? {
            val result = done[prerequisite] ?: return SkipReason.ERROR
            return when {
                result.status.passed -> null
                result.status == StepStatus.SKIPPED -> result.skip ?: reasonFor(prerequisite)
                else -> reasonFor(prerequisite)
            }
        }
        if (RepairStep.INTERNET in requires || RepairStep.SERVERS in requires) {
            // The root first: without internet nothing that needs it says anything.
            val internet = done[RepairStep.INTERNET]
            if (internet != null && !internet.status.passed && step != RepairStep.INTERNET) return SkipReason.NO_INTERNET
        }
        if (step in THROUGH_VPN && done[RepairStep.PRIVATE_DNS_VPN]?.has(Finding.OTHER_VPN) == true) {
            return SkipReason.OTHER_VPN
        }
        for (prerequisite in requires) {
            // A problem found by «Частный DNS и другие VPN» blocks nothing but another VPN (above).
            if (prerequisite == RepairStep.PRIVATE_DNS_VPN) continue
            blocked(prerequisite)?.let { return it }
        }
        return null
    }
}
