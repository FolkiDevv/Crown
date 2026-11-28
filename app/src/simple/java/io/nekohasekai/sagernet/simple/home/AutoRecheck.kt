package io.nekohasekai.sagernet.simple.home

/**
 * «Сервер не отвечает» while an auto-selector carries the connection (simple-ui "Быстрое переключение «Авто»"): the
 * first failed check of the home screen asks `:bg` to have the core re-check every member at once, instead of waiting
 * while the core keeps re-checking the failed one on its own schedule before it tries a standby.
 *
 * At most once per [MIN_GAP_MS]. The time of the last request is kept in DataStore under [KEY_LAST_AT] as device
 * uptime (ms since boot, the same clock in both processes), so a recreated screen or a killed UI process does not
 * reset the limit; a time later than now (left from before a reboot) does not count.
 */
object AutoRecheck {

    const val KEY_LAST_AT = "simple_auto_recheck_at"

    const val MIN_GAP_MS = 60_000L

    /**
     * Whether to ask now: [selectorRuns] (an auto-selector runs in the connection), not [suspended] (the
     * core itself holds the network to be down), [hasNetwork] (the device has a physical network at all), and the last
     * request at [lastAt] (0 = none) is at least [MIN_GAP_MS] before [now].
     */
    fun due(selectorRuns: Boolean, suspended: Boolean, hasNetwork: Boolean, lastAt: Long, now: Long): Boolean {
        if (!selectorRuns || suspended || !hasNetwork) return false
        return lastAt <= 0L || lastAt > now || now - lastAt >= MIN_GAP_MS
    }
}
