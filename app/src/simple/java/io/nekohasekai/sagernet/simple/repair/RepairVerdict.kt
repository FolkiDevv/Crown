package io.nekohasekai.sagernet.simple.repair

/**
 * The outcome of «Починка» (connection-repair "Итоговый вердикт"): «Исправили сами», «Причина найдена» or «Причину
 * определить не удалось». Pure Kotlin over the results, covered by JVM unit tests for every known cause.
 */
sealed interface RepairVerdict {

    /** The fixes the app made; empty in all but [Fixed] when nothing was fixed. */
    val fixes: List<Pair<RepairStep, FixKind>>

    /** «Исправили сами»: every problem found was fixed. */
    data class Fixed(override val fixes: List<Pair<RepairStep, FixKind>>) : RepairVerdict

    /** «Причина найдена»: a known cause, with what the user can do about it. */
    data class CauseFound(val cause: Cause, override val fixes: List<Pair<RepairStep, FixKind>> = emptyList()) : RepairVerdict

    /**
     * «Причину определить не удалось»; [nothingFound]: no check found a problem at all (the problem may be elsewhere or
     * gone), else something failed that no known cause explains. The report export is the main action (batch 9).
     */
    data class Unknown(
        val nothingFound: Boolean,
        override val fixes: List<Pair<RepairStep, FixKind>> = emptyList(),
    ) : RepairVerdict

    /** The known causes, in the order they are looked for: the first that applies is the verdict. */
    enum class Cause {
        /** «Интернет без VPN» failed: the problem is not the VPN. */
        NO_INTERNET,

        /** The clock is off by more than a minute: TLS and most protocols fail. */
        CLOCK,

        /** Another app's VPN is active. */
        OTHER_VPN,

        /** A private DNS set by hand in Android. */
        PRIVATE_DNS,

        /** The subscription is over: its term ended or its traffic is used up. */
        SUBSCRIPTION_ENDED,

        /** No server answers and a subscription has no servers at all. */
        SUBSCRIPTION_EMPTY,

        /** Whitelists in the network, no server answers and none is recognized as a server for whitelists. */
        WHITELISTS_NO_SERVERS,

        /** Whitelists in the network and servers for them exist, but none answers. */
        WHITELISTS_SERVERS_DOWN,

        /** No server answers (no whitelists in the network). */
        ALL_SERVERS_DOWN,

        /** The current server does not answer while others do. */
        CURRENT_SERVER_DOWN,

        /** Android may stop the VPN in the background (battery optimization or restriction). */
        BATTERY,
    }

    companion object {

        /** The verdict for finished checks [results] (a stopped run has none). */
        fun of(results: Map<RepairStep, StepResult>): RepairVerdict {
            val fixes = RepairPlan.ORDER.mapNotNull { step -> results[step]?.fix?.let { step to it } }
            val cause = causeOf(results)
            if (cause != null) return CauseFound(cause, fixes)
            val problems = results.values.any { it.status == StepStatus.FAIL || it.status == StepStatus.WARN }
            if (fixes.isNotEmpty() && !problems) return Fixed(fixes)
            return Unknown(nothingFound = !problems && fixes.isEmpty(), fixes)
        }

        private fun causeOf(results: Map<RepairStep, StepResult>): Cause? {
            fun failed(step: RepairStep) = results[step]?.status == StepStatus.FAIL
            fun found(step: RepairStep, finding: Finding) = results[step]?.has(finding) == true &&
                results[step]?.status != StepStatus.FIXED
            val servers = results[RepairStep.SERVERS]
            // FAIL: no server at all, or none answers (WARN is the current server down while others answer).
            val noneAnswers = servers?.status == StepStatus.FAIL
            val whitelistServers = servers?.servers?.whitelistTotal ?: 0
            val whitelists = found(RepairStep.WHITELISTS, Finding.WHITELISTS_FOUND)
            return when {
                failed(RepairStep.INTERNET) -> Cause.NO_INTERNET
                failed(RepairStep.CLOCK) && found(RepairStep.CLOCK, Finding.CLOCK_OFF) -> Cause.CLOCK
                found(RepairStep.PRIVATE_DNS_VPN, Finding.OTHER_VPN) -> Cause.OTHER_VPN
                found(RepairStep.PRIVATE_DNS_VPN, Finding.PRIVATE_DNS) -> Cause.PRIVATE_DNS
                found(RepairStep.SUBSCRIPTION, Finding.SUBSCRIPTION_EXPIRED) ||
                    found(RepairStep.SUBSCRIPTION, Finding.TRAFFIC_EXHAUSTED) -> Cause.SUBSCRIPTION_ENDED

                noneAnswers && found(RepairStep.SUBSCRIPTION, Finding.SUBSCRIPTION_EMPTY) -> Cause.SUBSCRIPTION_EMPTY
                noneAnswers && whitelists && whitelistServers == 0 -> Cause.WHITELISTS_NO_SERVERS
                noneAnswers && whitelists -> Cause.WHITELISTS_SERVERS_DOWN
                noneAnswers -> Cause.ALL_SERVERS_DOWN
                found(RepairStep.SERVERS, Finding.CURRENT_SERVER_DOWN) -> Cause.CURRENT_SERVER_DOWN
                onlyBackgroundLeft(results) -> Cause.BATTERY
                else -> null
            }
        }

        /** The battery is the cause only when it is the one problem found: anything else would explain more. */
        private fun onlyBackgroundLeft(results: Map<RepairStep, StepResult>): Boolean {
            val background = results[RepairStep.BACKGROUND] ?: return false
            if (background.status != StepStatus.WARN && background.status != StepStatus.FAIL) return false
            return results.none { (step, result) ->
                step != RepairStep.BACKGROUND && (result.status == StepStatus.FAIL || result.status == StepStatus.WARN)
            }
        }
    }
}
