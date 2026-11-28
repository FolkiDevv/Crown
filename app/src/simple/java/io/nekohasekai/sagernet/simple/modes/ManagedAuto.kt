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

    /**
     * Markers of a move to [TUNING_VERSION] 1 that is stored but not yet confirmed, by what moved. A selector whose
     * other setting was set by hand moves only one of them, and a stale save then puts back only that one.
     */
    const val PENDING_BOTH = -1L
    const val PENDING_PASSIVE = -2L
    const val PENDING_WATCH = -3L

    /** A new managed selector over [groups], [firstTier] the name filter of its first tier. */
    fun create(name: String, groups: List<Long>, firstTier: String): AutoSelector = AutoSelector().apply {
        this.name = name
        gids = ArrayList(groups)
        strategy = AutoSelector.STRATEGY_PRIORITY
        tiers = arrayListOf(AutoSelector.Tier(-1, firstTier))
        passive = PASSIVE
        watchIntervalSec = WATCH_INTERVAL_SEC
    }

    /** What [update] did: whether the selector must be saved, and the tuning marker to store with it. */
    data class Update(val save: Boolean, val tuning: Long)

    /**
     * Brings the stored selector [existing] up to date: the groups, tiers and strategy always, and its check settings
     * by the stored marker [tuning]:
     * - [TUNING_VERSION]: done, nothing is moved again (a value put back by hand stays);
     * - older (0 = never): each check setting still at its old default moves, one set otherwise (in the classic
     *   editor) stays; when anything moved the marker becomes one of the pending ones below, which also says which
     *   settings moved (moved, but not yet seen stored);
     * - pending: the next ensure confirms it. Only when every setting that moved is back at its old default (a stale
     *   whole-selector save of `:bg`, which loaded it before the move, put the old JSON back) they move again, once:
     *   the marker becomes [TUNING_VERSION] with it. An old value alone cannot tell that stale save from the user
     *   putting the setting back in the classic editor before the confirmation, so such an edit is undone at most this
     *   one time and stays when repeated. When only one of two moved settings is back, it was changed by hand and stays.
     */
    fun update(existing: AutoSelector, groups: List<Long>, firstTier: String, tuning: Long): Update {
        var changed = false
        val tiers = listOf(AutoSelector.Tier(-1, firstTier))
        if (existing.gids != groups || existing.tiers != tiers || !existing.prioritized) {
            existing.gids = ArrayList(groups)
            existing.tiers = ArrayList(tiers)
            existing.strategy = AutoSelector.STRATEGY_PRIORITY
            changed = true
        }
        if (tuning == TUNING_VERSION) return Update(changed, tuning)
        val passiveOld = existing.passive == OLD_PASSIVE
        val watchOld = existing.watchIntervalSec == OLD_WATCH_INTERVAL_SEC
        // Which settings to move: on the first pass each one still at its old default; on a pending pass the ones
        // that moved before, and only when all of them are back.
        val (movePassive, moveWatch) = when (tuning) {
            PENDING_BOTH -> (passiveOld && watchOld).let { it to it }
            PENDING_PASSIVE -> passiveOld to false
            PENDING_WATCH -> false to watchOld
            else -> passiveOld to watchOld
        }
        if (movePassive) existing.passive = PASSIVE
        if (moveWatch) existing.watchIntervalSec = WATCH_INTERVAL_SEC
        val marker = when {
            tuning == PENDING_BOTH || tuning == PENDING_PASSIVE || tuning == PENDING_WATCH -> TUNING_VERSION
            movePassive && moveWatch -> PENDING_BOTH
            movePassive -> PENDING_PASSIVE
            moveWatch -> PENDING_WATCH
            else -> TUNING_VERSION
        }
        return Update(changed || movePassive || moveWatch, marker)
    }
}
