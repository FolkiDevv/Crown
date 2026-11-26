package io.nekohasekai.sagernet.simple.subs

import io.nekohasekai.sagernet.simple.subs.SubscriptionNotices.AlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SubscriptionNoticesTest {

    // 2027-01-15 08:00 UTC
    private val now = 1_800_000_000L
    private val day = 86_400L
    private val gb = 1L shl 30
    private val utc = ZoneOffset.UTC

    private fun facts(
        gid: Long = 1L,
        expire: Long = 0L,
        hasQuota: Boolean = false,
        total: Long = 0L,
        used: Long = 0L,
        announce: String = "",
        usable: Int = 5,
        placeholders: List<String> = emptyList(),
        mark: FetchMark = FetchMark.NONE,
    ) = SubscriptionFacts(gid, "MyVPN", expire, hasQuota, total, used, announce, "https://my.example", "", usable, placeholders, mark)

    private fun of(facts: SubscriptionFacts) = SubscriptionNotices.of(facts, now, utc)

    private fun al(facts: SubscriptionFacts) = SubscriptionNotices.alertable(facts, now, utc)

    @Test
    fun placeholders() {
        assertTrue(Placeholders.isPlaceholder("0.0.0.0", 443, false))
        assertTrue(Placeholders.isPlaceholder("127.0.0.1", 8080, false))
        assertTrue(Placeholders.isPlaceholder("127.5.6.7", 8080, false))
        assertTrue(Placeholders.isPlaceholder("::1", 443, false))
        assertTrue(Placeholders.isPlaceholder("[::]", 443, false))
        assertTrue(Placeholders.isPlaceholder("0:0:0:0:0:0:0:1", 443, false))
        assertTrue(Placeholders.isPlaceholder("LocalHost", 443, false))
        assertTrue(Placeholders.isPlaceholder("example.com", 1, false))
        assertTrue(Placeholders.isPlaceholder("example.com", 0, false))
        assertFalse(Placeholders.isPlaceholder("example.com", 0, true))
        assertFalse(Placeholders.isPlaceholder("example.com", 443, false))
        assertFalse(Placeholders.isPlaceholder("10.0.2.2", 1080, false))
        assertFalse(Placeholders.isPlaceholder("128.0.0.1", 443, false))
        assertFalse(Placeholders.isPlaceholder("2001:db8::1", 443, false))
        assertFalse(Placeholders.isPlaceholder("1.0.0.0", 443, false))
        assertFalse(Placeholders.isPlaceholder("", 0, false))
    }

    @Test
    fun nothingToSay() {
        assertEquals(emptyList<Notice>(), of(facts(expire = now + 30 * day, hasQuota = true, total = 100 * gb, used = gb)))
    }

    @Test
    fun redBeforeAmber() {
        val expired = of(facts(expire = now - day, hasQuota = true, total = 100 * gb, used = 99 * gb)).single()
        assertEquals(NoticeReason.EXPIRED, expired.reason)
        assertEquals(NoticeLevel.BLOCKED, expired.level)
        val exhausted = of(facts(expire = now + day, hasQuota = true, total = 10 * gb, used = 10 * gb)).single()
        assertEquals(NoticeReason.EXHAUSTED, exhausted.reason)
    }

    @Test
    fun placeholdersOnlyAreRedAndQuoted() {
        val notice = of(facts(usable = 0, placeholders = listOf("⛔ Подписка истекла", " ", "⛔ Подписка истекла", "Продлите в боте"))).single()
        assertEquals(NoticeReason.PLACEHOLDERS, notice.reason)
        assertEquals(NoticeLevel.BLOCKED, notice.level)
        assertEquals("⛔ Подписка истекла\nПродлите в боте", notice.message)
        // Beside real servers they say nothing.
        assertEquals(emptyList<Notice>(), of(facts(usable = 3, placeholders = listOf("ℹ️ Осталось 20 дней"))))
    }

    @Test
    fun deviceLimitIsAmberWhileOldServersWork() {
        val amber = of(facts(mark = FetchMark.LIMIT)).single()
        assertEquals(NoticeReason.DEVICE_LIMIT, amber.reason)
        assertEquals(NoticeLevel.WARNING, amber.level)
        val red = of(facts(mark = FetchMark.LIMIT, usable = 0)).single()
        assertEquals(NoticeLevel.BLOCKED, red.level)
    }

    @Test
    fun refusalQuotesTheAnnouncementInsteadOfASecondCard() {
        val notices = of(facts(mark = FetchMark.EMPTY, announce = "Лимит устройств: 3"))
        assertEquals(NoticeReason.REFUSED, notices.single().reason)
        assertEquals(NoticeLevel.WARNING, notices.single().level)
        assertEquals("Лимит устройств: 3", notices.single().message)
        // An empty answer without an announcement marks nothing (FetchMark.EMPTY needs one).
        assertEquals(emptyList<Notice>(), of(facts(mark = FetchMark.EMPTY)))
    }

    @Test
    fun expiringThresholds() {
        val three = of(facts(expire = now + 3 * day - 3600)).single()
        assertEquals(NoticeReason.EXPIRING, three.reason)
        assertEquals(3, three.daysLeft)
        assertEquals("expiring:3", three.token)
        assertEquals("expiring:3", of(facts(expire = now + 2 * day)).single().token)
        assertEquals("expiring:1", of(facts(expire = now + day)).single().token)
        assertEquals("expiring:0", of(facts(expire = now + 3600)).single().token)
    }

    @Test
    fun lowTrafficAndAnnouncement() {
        val notices = of(facts(hasQuota = true, total = 100 * gb, used = 95 * gb, announce = "Плановые работы"))
        assertEquals(listOf(NoticeReason.LOW_TRAFFIC, NoticeReason.ANNOUNCE), notices.map { it.reason })
        assertEquals(NoticeLevel.NEWS, notices[1].level)
    }

    @Test
    fun homePicksOneByImportance() {
        val news = of(facts(gid = 1L, announce = "Новости")).single()
        val amber = of(facts(gid = 2L, expire = now + day)).single()
        val red = of(facts(gid = 3L, expire = now - day)).single()
        assertEquals(red, SubscriptionNotices.pick(listOf(news, amber, red), emptyMap(), emptyMap()))
        assertEquals(amber, SubscriptionNotices.pick(listOf(news, amber), emptyMap(), emptyMap()))
        // «Позже» hides the amber one until its next threshold, ✕ the announcement until a new text.
        assertEquals(news, SubscriptionNotices.pick(listOf(news, amber), mapOf(2L to amber.token), emptyMap()))
        assertNull(SubscriptionNotices.pick(listOf(news, amber), mapOf(2L to amber.token), mapOf(1L to news.token)))
        val nextThreshold = of(facts(gid = 2L, expire = now + 3600)).single()
        assertEquals(nextThreshold, SubscriptionNotices.pick(listOf(nextThreshold), mapOf(2L to amber.token), emptyMap()))
        val newText = of(facts(gid = 1L, announce = "Другие новости")).single()
        assertEquals(newText, SubscriptionNotices.pick(listOf(newText), emptyMap(), mapOf(1L to news.token)))
        // A red one cannot be put off.
        assertEquals(red, SubscriptionNotices.pick(listOf(red), mapOf(3L to red.token), emptyMap()))
    }

    @Test
    fun alertsOncePerEvent() {
        val soon = al(facts(expire = now + 3 * day - 3600, announce = "Новости"))
        val (first, sent) = SubscriptionNotices.due(soon, emptyMap())
        assertEquals(listOf(NoticeReason.EXPIRING, NoticeReason.ANNOUNCE), first.map { it.reason })
        assertEquals("soon", sent[AlertKind.EXPIRY])
        // The same state again: nothing.
        assertEquals(emptyList<Notice>(), SubscriptionNotices.due(soon, sent).first)
        // The last day, then the end: each once.
        val last = al(facts(expire = now + day, announce = "Новости"))
        val (second, sent2) = SubscriptionNotices.due(last, sent)
        assertEquals(listOf(NoticeReason.EXPIRING), second.map { it.reason })
        assertEquals("last", sent2[AlertKind.EXPIRY])
        val expired = al(facts(expire = now - 60, announce = "Новости"))
        assertEquals(listOf(NoticeReason.EXPIRED), SubscriptionNotices.due(expired, sent2).first.map { it.reason })
        // Renewed: forgotten, so the next end is told again.
        val renewed = SubscriptionNotices.due(al(facts(expire = now + 30 * day, announce = "Новости")), sent2).second
        assertNull(renewed[AlertKind.EXPIRY])
        // Refusals and placeholders send nothing.
        assertEquals(emptyList<Notice>(), SubscriptionNotices.due(al(facts(usable = 0, placeholders = listOf("x"))), emptyMap()).first)
    }

    @Test
    fun alertsFollowEachConditionNotTheCard() {
        // Little traffic, told; then the end comes near too: the card shows the end, the traffic stays told.
        val low = SubscriptionNotices.due(al(facts(expire = now + 30 * day, hasQuota = true, total = 100 * gb, used = 95 * gb)), emptyMap())
        assertEquals(listOf(NoticeReason.LOW_TRAFFIC), low.first.map { it.reason })
        val both = SubscriptionNotices.due(al(facts(expire = now + day, hasQuota = true, total = 100 * gb, used = 95 * gb)), low.second)
        assertEquals(listOf(NoticeReason.EXPIRING), both.first.map { it.reason })
        // Renewed without a top-up: nothing new.
        val renewed = SubscriptionNotices.due(al(facts(expire = now + 30 * day, hasQuota = true, total = 100 * gb, used = 95 * gb)), both.second)
        assertEquals(emptyList<Notice>(), renewed.first)
    }

    @Test
    fun providerLinks() {
        assertEquals("https://my.example/cabinet", ProviderLinks.safe(" https://my.example/cabinet ", allowTg = false))
        assertEquals("http://my.example", ProviderLinks.safe("http://my.example", allowTg = false))
        assertEquals("", ProviderLinks.safe("market://details?id=com.attacker.app", allowTg = true))
        assertEquals("", ProviderLinks.safe("intent://x#Intent;scheme=a;end", allowTg = true))
        assertEquals("", ProviderLinks.safe("javascript:alert(1)", allowTg = true))
        assertEquals("", ProviderLinks.safe("https:///path", allowTg = false))
        assertEquals("", ProviderLinks.safe("my.example/cabinet", allowTg = false))
        assertEquals("", ProviderLinks.safe("tg://resolve?domain=support", allowTg = false))
        assertEquals("tg://resolve?domain=support", ProviderLinks.safe("tg://resolve?domain=support", allowTg = true))
        assertEquals("https://t.me/support", ProviderLinks.safe("https://t.me/support", allowTg = false))
    }

    @Test
    fun linksKeepTheirOwnBrackets() {
        assertEquals("https://en.wikipedia.org/wiki/Proxy_(computing)", trimLink("https://en.wikipedia.org/wiki/Proxy_(computing)"))
        assertEquals("https://en.wikipedia.org/wiki/Proxy_(computing)", trimLink("https://en.wikipedia.org/wiki/Proxy_(computing)."))
        assertEquals("https://my.example/news", trimLink("https://my.example/news)."))
        assertEquals("https://my.example/a", trimLink("https://my.example/a],"))
        assertEquals("t.me/support", trimLink("t.me/support!"))
        // A bracket the link opened itself, as an IPv6 host.
        assertEquals("https://[::1]", trimLink("https://[::1]"))
        assertEquals("https://[::1]/a", trimLink("https://[::1]/a."))
    }

    @Test
    fun fetchMarks() {
        assertEquals(FetchMark.LIMIT, FetchMark.of("limit"))
        assertEquals(FetchMark.EMPTY, FetchMark.of("empty"))
        assertEquals(FetchMark.NONE, FetchMark.of(null))
        assertEquals(FetchMark.NONE, FetchMark.of(""))
        assertEquals(FetchMark.NONE, FetchMark.of("other"))
    }
}
