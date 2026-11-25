package io.nekohasekai.sagernet.simple.servers

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What the subscription card warns about (design.md Open Questions, settled in batch 5): the end of the subscription
 * from [EXPIRY_WARNING_DAYS] days before it, and the traffic once less than [LOW_TRAFFIC_PERCENT] % of the quota or
 * less than [LOW_TRAFFIC_BYTES] is left, whichever comes first. No Android types, so JVM unit tests cover it.
 */
object SubscriptionHealth {

    const val EXPIRY_WARNING_DAYS = 3
    const val LOW_TRAFFIC_PERCENT = 10
    const val LOW_TRAFFIC_BYTES = 1L shl 30

    private const val DAY_SECONDS = 24L * 60 * 60

    enum class Expiry { NONE, OK, SOON, EXPIRED }

    enum class Traffic { NONE, UNLIMITED, OK, LOW, EXHAUSTED }

    /** [expire] in epoch seconds, 0 = not reported. */
    fun expiry(expire: Long, nowSeconds: Long): Expiry = when {
        expire <= 0L -> Expiry.NONE
        expire <= nowSeconds -> Expiry.EXPIRED
        expire - nowSeconds <= EXPIRY_WARNING_DAYS * DAY_SECONDS -> Expiry.SOON
        else -> Expiry.OK
    }

    /**
     * Calendar days in [zone] from today to the day of [expire], the date the card shows next to it: 0 for an end later
     * today or one that is over, 1 for tomorrow.
     */
    fun daysLeft(expire: Long, nowSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (expire <= nowSeconds) return 0
        val today = Instant.ofEpochSecond(nowSeconds).atZone(zone).toLocalDate()
        val end = Instant.ofEpochSecond(expire).atZone(zone).toLocalDate()
        return ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(0)
    }

    /** [hasQuota]: the provider reported traffic at all; [total] 0 with it = unlimited. */
    fun traffic(hasQuota: Boolean, total: Long, used: Long): Traffic {
        if (!hasQuota) return Traffic.NONE
        if (total <= 0L) return Traffic.UNLIMITED
        val remaining = (total - used).coerceAtLeast(0L)
        return when {
            remaining == 0L -> Traffic.EXHAUSTED
            remaining < LOW_TRAFFIC_BYTES || remaining * 100 < total * LOW_TRAFFIC_PERCENT -> Traffic.LOW
            else -> Traffic.OK
        }
    }
}
