package io.nekohasekai.sagernet.simple.servers

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

    /** Whole days left, rounded up (an end in 3 h is «1 day»); 0 once it is over. */
    fun daysLeft(expire: Long, nowSeconds: Long): Int =
        if (expire <= nowSeconds) 0 else ((expire - nowSeconds + DAY_SECONDS - 1) / DAY_SECONDS).toInt()

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
