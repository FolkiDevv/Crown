package io.nekohasekai.sagernet.simple.subs

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.trafficSize
import java.text.DateFormat
import java.util.Date

/**
 * The words of a [Notice], the same on the home card and in the notification: [title], [body], the provider's own
 * [quote] ("" when none) and the label of the button that leads to the provider's page.
 */
class NoticeTexts(val title: String, val body: String, val quote: String, val renewLabel: String) {

    companion object {
        fun of(context: Context, notice: Notice): NoticeTexts {
            val facts = notice.facts
            val name = facts.name
            val locale = context.resources.configuration.locales[0]
            val date = DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(facts.expire * 1000))
            val blocked = notice.level == NoticeLevel.BLOCKED
            fun s(id: Int, vararg args: Any) = context.getString(id, *args)
            val (title, body) = when (notice.reason) {
                NoticeReason.EXPIRED -> s(R.string.simple_notice_expired_title, name) to s(R.string.simple_notice_expired_text, date)
                NoticeReason.EXHAUSTED -> s(R.string.simple_notice_exhausted_title, name) to s(R.string.simple_notice_exhausted_text)
                NoticeReason.DEVICE_LIMIT -> s(R.string.simple_notice_limit_title, name) to
                    s(if (blocked) R.string.simple_notice_limit_text_blocked else R.string.simple_notice_limit_text)

                NoticeReason.REFUSED, NoticeReason.PLACEHOLDERS -> if (blocked) {
                    s(R.string.simple_notice_blocked_title, name) to s(R.string.simple_notice_blocked_text)
                } else {
                    s(R.string.simple_notice_refused_title, name) to s(R.string.simple_notice_refused_text)
                }

                NoticeReason.EXPIRING -> s(R.string.simple_notice_expiring_title, name) to
                    if (notice.daysLeft <= 0) {
                        s(R.string.simple_notice_expiring_today)
                    } else {
                        context.resources.getQuantityString(R.plurals.simple_notice_expiring_text, notice.daysLeft, notice.daysLeft, date)
                    }

                NoticeReason.LOW_TRAFFIC -> s(R.string.simple_notice_low_title, name) to s(
                    R.string.simple_notice_low_text,
                    trafficSize(context, (facts.total - facts.used).coerceAtLeast(0L)),
                    trafficSize(context, facts.total),
                )

                NoticeReason.ANNOUNCE -> s(R.string.simple_notice_announce_title, name) to notice.message
            }
            val quote = if (notice.reason == NoticeReason.ANNOUNCE) "" else notice.message
            val traffic = notice.reason == NoticeReason.EXHAUSTED || notice.reason == NoticeReason.LOW_TRAFFIC
            val renew = s(if (traffic) R.string.simple_notice_top_up else R.string.simple_notice_renew)
            return NoticeTexts(title, body, quote, renew)
        }
    }
}
