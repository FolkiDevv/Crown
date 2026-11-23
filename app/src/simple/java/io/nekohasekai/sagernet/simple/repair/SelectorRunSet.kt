package io.nekohasekai.sagernet.simple.repair

/**
 * Which servers an auto-selector runs, for «Доступность серверов» (does the current server answer?). The core runs
 * only the selector's build — its eligible members capped by the build limit — so an eligible member outside the build
 * that answers does not make the selector work. Pure Kotlin, covered by JVM unit tests.
 */
object SelectorRunSet {

    /**
     * [connected]: this app's VPN runs now, so [lastBuilt] (kept by the runtime for every build) is what the core runs.
     * Otherwise — or before a first build — the build the planner would make now ([plannedBuild]).
     */
    fun of(connected: Boolean, lastBuilt: List<Long>, plannedBuild: () -> List<Long>): Set<Long> =
        if (connected && lastBuilt.isNotEmpty()) lastBuilt.toSet() else plannedBuild().toSet()

    /** Whether a selector running [members] answers, given the servers that answered the URL test. */
    fun answers(members: Set<Long>, answering: Set<Long>): Boolean = members.any { it in answering }
}
