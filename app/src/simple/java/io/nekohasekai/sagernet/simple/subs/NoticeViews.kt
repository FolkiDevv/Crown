package io.nekohasekai.sagernet.simple.subs

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.FocusShapes
import io.nekohasekai.sagernet.simple.focusRing

/** What the subscription messages can ask for. */
class NoticeActions(
    val recheck: (Long) -> Unit,
    val later: (Notice) -> Unit,
    val hide: (Notice) -> Unit,
    val more: (Notice) -> Unit,
    /** Opens a page of the provider of a subscription (its account, support): it is checked again on return. */
    val openProvider: (Long, String) -> Unit,
)

/** Opens [url] outside the app (a browser, Telegram); false when nothing on the device opens it. */
fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, url.trim().toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, R.string.simple_notice_no_app, Toast.LENGTH_SHORT).show()
    false
} catch (_: SecurityException) {
    Toast.makeText(context, R.string.simple_notice_no_app, Toast.LENGTH_SHORT).show()
    false
}

/** The amber of the whitelist card and the mode card's warning. */
@Composable
fun amberColors(): Pair<Color, Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(0xFF4A3800) to Color(0xFFFFE08F) else Color(0xFFFFE6A6) to Color(0xFF3A2A00)
}

/**
 * The home screen's subscription message (simple-ui "Одно место для сообщений"): red like the problem banner when the
 * VPN cannot work through the subscription, amber like the whitelist card when the user has something to do, the
 * provider's announcement on a quiet card with ✕ and «Подробнее».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoticeCard(notice: Notice, actions: NoticeActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val texts = remember(notice) { NoticeTexts.of(context, notice) }
    val facts = notice.facts
    val (amber, onAmber) = amberColors()
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (notice.level) {
        NoticeLevel.BLOCKED -> colors.errorContainer to colors.onErrorContainer
        NoticeLevel.WARNING -> amber to onAmber
        NoticeLevel.NEWS -> colors.surfaceContainerHigh to colors.onSurface
    }
    val news = notice.level == NoticeLevel.NEWS
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = if (news) 4.dp else 16.dp)) {
            if (news) {
                Icon(
                    painterResource(R.drawable.simple_ic_campaign),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(texts.title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    texts.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (news) 3 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
                if (texts.quote.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Quote(texts.quote, content, onOpen = { actions.more(notice) }.takeIf { texts.quote == facts.announce })
                }
            }
            if (news) {
                IconButton(
                    onClick = { actions.hide(notice) },
                    modifier = Modifier.focusRing(FocusShapes.Pill, outset = (-3).dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.simple_notice_hide))
                }
            }
        }
        // The buttons go on two lines when one does not hold them (a narrow phone, a large font).
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = if (news) 4.dp else 12.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            val textColors = ButtonDefaults.textButtonColors(contentColor = if (news) colors.primary else content)
            val buttonModifier = Modifier.focusRing(FocusShapes.Pill)
            if (news) {
                TextButton(onClick = { actions.more(notice) }, colors = textColors, modifier = buttonModifier) {
                    Text(stringResource(R.string.simple_notice_more))
                }
            } else {
                ProviderButtons(notice, texts, textColors, buttonModifier, actions)
            }
        }
    }
}

@Composable
private fun ProviderButtons(
    notice: Notice,
    texts: NoticeTexts,
    textColors: ButtonColors,
    buttonModifier: Modifier,
    actions: NoticeActions,
) {
    val facts = notice.facts
    val putOff = notice.reason == NoticeReason.EXPIRING || notice.reason == NoticeReason.LOW_TRAFFIC
    if (putOff) {
        TextButton(onClick = { actions.later(notice) }, colors = textColors, modifier = buttonModifier) {
            Text(stringResource(R.string.simple_notice_later))
        }
    } else {
        TextButton(onClick = { actions.recheck(facts.groupId) }, colors = textColors, modifier = buttonModifier) {
            Text(stringResource(R.string.simple_notice_recheck))
        }
    }
    // The provider's page: to renew or top up while the end or the traffic is the reason, otherwise the
    // account (the device limit, a refusal); support when the provider gave no page.
    val renewing = notice.reason in RENEWABLE
    val (label, url) = when {
        facts.webUrl.isNotEmpty() ->
            (if (renewing) texts.renewLabel else stringResource(R.string.simple_notice_account)) to facts.webUrl

        facts.supportUrl.isNotEmpty() -> stringResource(R.string.simple_notice_support) to facts.supportUrl
        else -> null to null
    }
    if (label != null && url != null) {
        Button(onClick = { actions.openProvider(facts.groupId, url) }, modifier = buttonModifier) { Text(label) }
    }
}

private val RENEWABLE = setOf(
    NoticeReason.EXPIRED, NoticeReason.EXHAUSTED, NoticeReason.EXPIRING, NoticeReason.LOW_TRAFFIC,
)

/**
 * The provider's own words, set apart from ours. [onOpen]: the quote is the announcement, which opens whole (six
 * lines may not hold its instructions and links).
 */
@Composable
fun Quote(text: String, color: Color, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val body: @Composable () -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
    val shape = MaterialTheme.shapes.small
    if (onOpen != null) {
        Surface(
            onClick = onOpen,
            color = color.copy(alpha = 0.08f),
            contentColor = color,
            shape = shape,
            modifier = modifier.fillMaxWidth().focusRing(shape),
            content = body,
        )
    } else {
        Surface(color = color.copy(alpha = 0.08f), contentColor = color, shape = shape, modifier = modifier.fillMaxWidth(), content = body)
    }
}

/**
 * The whole announcement (simple-ui "Объявление провайдера"): its text with its links, the provider's account and
 * support when it gave them, and «Понятно» over the whole width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnouncementSheet(notice: Notice, onProvider: (Long, String) -> Unit, onDismiss: () -> Unit) {
    val facts = notice.facts
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(notice.message, linkColor) { linkified(notice.message, linkColor) { openLink(context, it) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.simple_ic_campaign),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    stringResource(R.string.simple_notice_announce_title, facts.name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(20.dp))
            if (facts.webUrl.isNotEmpty() || facts.supportUrl.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (facts.webUrl.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { onProvider(facts.groupId, facts.webUrl) },
                            modifier = Modifier.focusRing(FocusShapes.Pill),
                        ) { Text(stringResource(R.string.simple_notice_account)) }
                    }
                    if (facts.supportUrl.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { onProvider(facts.groupId, facts.supportUrl) },
                            modifier = Modifier.focusRing(FocusShapes.Pill),
                        ) { Text(stringResource(R.string.simple_notice_support)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusRing(FocusShapes.Pill),
            ) { Text(stringResource(R.string.simple_announce_ok)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val LINK = Regex("""(?:https?|tg)://[^\s<>"'«»]+|\bt\.me/[^\s<>"'«»]+|\bwww\.[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

/**
 * A link found in text without the punctuation after it: a closing bracket stays when the link opened it
 * (`…/Proxy_(computing)`), and goes when it closes the sentence's own bracket (`(see https://…)`).
 */
fun trimLink(found: String): String {
    var end = found.length
    while (end > 0) {
        val c = found[end - 1]
        val open = when (c) {
            ')' -> '('
            ']' -> '['
            else -> null
        }
        val trailing = when {
            c in ".,;:!?" -> true
            open != null -> found.take(end).count { it == open } < found.take(end).count { it == c }
            else -> false
        }
        if (!trailing) break
        end--
    }
    return found.substring(0, end)
}

/** [text] with its web and Telegram links tappable; a link ends before trailing punctuation. */
fun linkified(text: String, color: Color, open: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in LINK.findAll(text)) {
        val raw = trimLink(match.value)
        if (raw.isEmpty()) continue
        append(text, at, match.range.first)
        val url = if (raw.contains("://")) raw else "https://$raw"
        pushLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline)),
            ) { open(url) }
        )
        append(raw)
        pop()
        at = match.range.first + raw.length
    }
    append(text, at, text.length)
}
