package io.nekohasekai.sagernet.simple.servers

import io.nekohasekai.sagernet.simple.servers.SubscriptionHealth.Expiry
import io.nekohasekai.sagernet.simple.servers.SubscriptionHealth.Traffic
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionHealthTest {

    private val now = 1_800_000_000L
    private val day = 86_400L
    private val gb = 1L shl 30

    @Test
    fun expiry() {
        assertEquals(Expiry.NONE, SubscriptionHealth.expiry(0L, now))
        assertEquals(Expiry.OK, SubscriptionHealth.expiry(now + 30 * day, now))
        assertEquals(Expiry.OK, SubscriptionHealth.expiry(now + 3 * day + 1, now))
        assertEquals(Expiry.SOON, SubscriptionHealth.expiry(now + 3 * day, now))
        assertEquals(Expiry.SOON, SubscriptionHealth.expiry(now + 60, now))
        assertEquals(Expiry.EXPIRED, SubscriptionHealth.expiry(now, now))
        assertEquals(Expiry.EXPIRED, SubscriptionHealth.expiry(now - day, now))
    }

    @Test
    fun daysLeft() {
        assertEquals(1, SubscriptionHealth.daysLeft(now + 3 * 3600, now))
        assertEquals(1, SubscriptionHealth.daysLeft(now + day, now))
        assertEquals(2, SubscriptionHealth.daysLeft(now + day + 1, now))
        assertEquals(3, SubscriptionHealth.daysLeft(now + 3 * day, now))
        assertEquals(0, SubscriptionHealth.daysLeft(now - 1, now))
    }

    @Test
    fun traffic() {
        assertEquals(Traffic.NONE, SubscriptionHealth.traffic(false, 100 * gb, 0L))
        assertEquals(Traffic.UNLIMITED, SubscriptionHealth.traffic(true, 0L, 5 * gb))
        assertEquals(Traffic.OK, SubscriptionHealth.traffic(true, 100 * gb, 50 * gb))
        // Under 10 % of the quota.
        assertEquals(Traffic.OK, SubscriptionHealth.traffic(true, 100 * gb, 90 * gb))
        assertEquals(Traffic.LOW, SubscriptionHealth.traffic(true, 100 * gb, 90 * gb + 1))
        // Under 1 GB, whatever the quota.
        assertEquals(Traffic.OK, SubscriptionHealth.traffic(true, 5 * gb, 4 * gb))
        assertEquals(Traffic.LOW, SubscriptionHealth.traffic(true, 5 * gb, 4 * gb + 1))
        assertEquals(Traffic.EXHAUSTED, SubscriptionHealth.traffic(true, 100 * gb, 100 * gb))
        assertEquals(Traffic.EXHAUSTED, SubscriptionHealth.traffic(true, 100 * gb, 120 * gb))
    }
}
