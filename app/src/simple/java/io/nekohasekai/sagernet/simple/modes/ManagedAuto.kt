package io.nekohasekai.sagernet.simple.modes

import io.nekohasekai.sagernet.outbound.types.AutoSelector

/**
 * What the managed «Авто» selectors hold (design.md D5, simple-ui "Быстрое переключение «Авто»"), apart from the
 * database so that JVM tests cover it; TrafficModes stores the result.
 *
 * Their check settings set a server that stops answering aside sooner than the [AutoSelector] defaults, which left
 * 40–100 s without traffic:
 * - [PASSIVE]: the core also judges a server by the connections the apps make through it, so one that leaves them
 *   unanswered or refuses them is set aside after a few failures even while its checks still pass; while real traffic
 *   flows it sends fewer checks.
 * - [WATCH_INTERVAL_SEC]: the server that carries the traffic is checked on its own every 5 s (the lowest the selector
 *   takes) instead of every 15 s.
 *
 * The other fields keep their defaults: `dial_retries` 2 is how many other servers a connection that cannot connect
 * tries at once, so fewer would only fail more connections; `expected` 3 already keeps standbys confirmed.
 */
object ManagedAuto {

    /** Raised when the check settings below change; an existing selector is moved to them once per version. */
    const val TUNING_VERSION = 1L

    const val PASSIVE = true
    const val WATCH_INTERVAL_SEC = 5

    /** The [AutoSelector] defaults the selectors were made with before [TUNING_VERSION] 1. */
    private const val OLD_PASSIVE = false
    private const val OLD_WATCH_INTERVAL_SEC = 15

    /** A new managed selector over [groups], [firstTier] the name filter of its first tier. */
    fun create(name: String, groups: List<Long>, firstTier: String): AutoSelector = AutoSelector().apply {
        this.name = name
        gids = ArrayList(groups)
        strategy = AutoSelector.STRATEGY_PRIORITY
        tiers = arrayListOf(AutoSelector.Tier(-1, firstTier))
        passive = PASSIVE
        watchIntervalSec = WATCH_INTERVAL_SEC
    }

    /**
     * Brings the stored selector [existing] up to date: the groups, tiers and strategy always; with [tune] (its check
     * settings are older than [TUNING_VERSION]) also each check setting still at its old default, while one set
     * otherwise (in the classic editor) stays. Returns whether [existing] changed and must be saved.
     */
    fun update(existing: AutoSelector, groups: List<Long>, firstTier: String, tune: Boolean): Boolean {
        var changed = false
        val tiers = listOf(AutoSelector.Tier(-1, firstTier))
        if (existing.gids != groups || existing.tiers != tiers || !existing.prioritized) {
            existing.gids = ArrayList(groups)
            existing.tiers = ArrayList(tiers)
            existing.strategy = AutoSelector.STRATEGY_PRIORITY
            changed = true
        }
        if (!tune) return changed
        if (existing.passive == OLD_PASSIVE) {
            existing.passive = PASSIVE
            changed = true
        }
        if (existing.watchIntervalSec == OLD_WATCH_INTERVAL_SEC) {
            existing.watchIntervalSec = WATCH_INTERVAL_SEC
            changed = true
        }
        return changed
    }
}
