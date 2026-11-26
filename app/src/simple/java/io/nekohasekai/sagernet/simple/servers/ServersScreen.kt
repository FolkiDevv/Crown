package io.nekohasekai.sagernet.simple.servers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.simple.FocusShapes
import io.nekohasekai.sagernet.simple.focusRing
import io.nekohasekai.sagernet.simple.focusWhenShown
import io.nekohasekai.sagernet.simple.rememberFocusWhenShown
import io.nekohasekai.sagernet.simple.unavailable
import io.nekohasekai.sagernet.simple.subs.Notice
import io.nekohasekai.sagernet.simple.subs.NoticeReason
import io.nekohasekai.sagernet.simple.subs.NoticeTexts
import io.nekohasekai.sagernet.simple.subs.Quote
import io.nekohasekai.sagernet.simple.trafficSize
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** What the server list can ask for. */
class ServersActions(
    val chooseAuto: () -> Unit,
    val chooseCountry: (CountryRow) -> Unit,
    val chooseServer: (ServerEntry) -> Unit,
    val update: (Long) -> Unit,
    val updateAll: () -> Unit,
    val add: () -> Unit,
    /** The menus of the subscriptions and of the own servers. */
    val manage: ManageActions,
    /** Opens the whole announcement of a subscription. */
    val announce: (Long) -> Unit,
)

/**
 * «Серверы»: «Авто» first, then each subscription (its card, then its countries, each opening to its servers), then
 * «Мои серверы» by name, then «Для белых списков». Pull to refresh updates the subscriptions. Rows show only the
 * name, the country and the delay (simple-ui spec "Скрытие технических деталей").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    state: ServersUiState,
    actions: ServersActions,
    modifier: Modifier = Modifier,
    /** A remote (D-pad) starts on «Авто» (or on «Добавить сервер») as the section opens. */
    focus: Boolean = true,
) {
    val sections = state.sections
    when {
        sections == null -> Box(modifier.fillMaxSize())
        sections.isEmpty && state.cards.isEmpty() -> EmptyServers(actions.add, modifier, focus)
        else -> {
            // Pull to refresh only by touch: a remote's focus scrolling the list to its top would leave the indicator
            // half pulled (the subscription cards have their own update buttons).
            val touch = LocalInputModeManager.current.inputMode == InputMode.Touch
            val pull = rememberPullToRefreshState()
            Box(
                modifier
                    .fillMaxSize()
                    .pullToRefresh(isRefreshing = state.refreshing, state = pull, enabled = touch, onRefresh = actions.updateAll),
            ) {
                ServerListView(state, sections, actions, focus)
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = state.refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun ServerListView(state: ServersUiState, sections: ServerSections, actions: ServersActions, focus: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    // Expanded countries, as "<group>:<country>" keys joined by newlines (saveable as one string).
    var expandedKeys by rememberSaveable { mutableStateOf("") }
    val expanded = expandedKeys.split('\n').filter { it.isNotEmpty() }.toSet()
    fun toggle(key: String) {
        expandedKeys = (if (key in expanded) expanded - key else expanded + key).joinToString("\n")
    }
    // The open menu, dialog or rename: "sub:<group id>" / "server:<id>" (saveable as strings).
    var sheet by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf("") }
    var deleting by rememberSaveable { mutableLongStateOf(0L) }
    val manage = actions.manage
    ManageOverlays(state, sections, locale, manage, sheet, renaming, deleting,
        setSheet = { sheet = it }, setRenaming = { renaming = it }, setDeleting = { deleting = it })
    // Asked here, not in the item: «Авто» scrolling back into view must not take the focus again.
    val list = rememberLazyListState()
    val autoFocus = rememberFocusWhenShown(enabled = focus, bringIntoComposition = { list.scrollToItem(0) })
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "auto") {
            val pick = state.pick?.takeIf { state.autoSelected }
            ChoiceRow(
                leading = { Icon(Icons.Filled.Star, contentDescription = null) },
                headline = stringResource(R.string.simple_server_auto),
                supporting = pick?.let { stringResource(R.string.simple_servers_now, pickLabel(it)) }
                    ?: stringResource(R.string.simple_servers_auto_summary),
                selected = state.autoSelected,
                enabled = !state.busy,
                onClick = actions.chooseAuto,
                modifier = Modifier.focusRequester(autoFocus),
            )
        }
        for (subscription in sections.subscriptions) {
            val group = subscription.group
            val card = state.cards[group.id]
            item(key = "sub-${group.id}") {
                card?.let {
                    SubscriptionCardView(
                        it,
                        locale,
                        onUpdate = { actions.update(group.id) },
                        onMore = { sheet = "sub:${group.id}" },
                        onAnnounce = { actions.announce(group.id) },
                        onProvider = manage.openProvider,
                    )
                }
            }
            // A subscription the VPN cannot work through: its card says why, its servers are of no use.
            if (card?.blocked != null) continue
            if (subscription.countries.isEmpty()) {
                item(key = "sub-empty-${group.id}") {
                    Text(
                        stringResource(R.string.simple_sub_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            for (row in subscription.countries) {
                val open = row.key in expanded
                item(key = "country-${row.key}") {
                    val chosen = state.countrySelected && state.choice == row.code?.let { CountryChoice(row.groupId, it) }
                    CountryRowView(row, chosen, open, state, locale, actions) { toggle(row.key) }
                }
                if (open) servers(row.servers, "c-${row.key}", state, locale, actions, indent = true)
            }
        }
        if (sections.mine.isNotEmpty()) {
            item(key = "mine") { SectionHeader(stringResource(R.string.simple_group_my_servers)) }
            servers(sections.mine, "mine", state, locale, actions, menu = { sheet = "server:${it.id}" })
        }
        if (sections.whitelist.isNotEmpty()) {
            item(key = "whitelist") {
                SectionHeader(
                    stringResource(R.string.simple_servers_whitelist),
                    stringResource(R.string.simple_servers_whitelist_hint),
                )
            }
            // An own server named for whitelists keeps its menu here; servers of a subscription have none.
            servers(sections.whitelist, "wl", state, locale, actions, menu = { sheet = "server:${it.id}" }) { it.own(sections) }
        }
    }
}

private fun LazyListScope.servers(
    list: List<ServerEntry>,
    prefix: String,
    state: ServersUiState,
    locale: Locale,
    actions: ServersActions,
    indent: Boolean = false,
    /** «Мои серверы»: the ⋮ of a row, in the place where a country has its arrow (a remote reaches it with right). */
    menu: ((ServerEntry) -> Unit)? = null,
    /** Which rows of [list] have the ⋮ (all of them by default). */
    hasMenu: (ServerEntry) -> Boolean = { true },
) {
    items(list, key = { "$prefix-${it.id}" }) { server ->
        ChoiceRow(
            leading = { CountryFlag(server.country.code) },
            headline = CountryFromName.withoutLeadingFlag(server.name),
            supporting = countryText(server.country, locale),
            trailing = latencyText(server.latency),
            trailingError = server.latency == -1,
            selected = state.selected == server.id,
            enabled = !state.busy,
            indent = indent,
            onClick = { actions.chooseServer(server) },
            extra = menu?.takeIf { hasMenu(server) }?.let { open ->
                { extraModifier ->
                    IconButton(
                        onClick = { open(server) },
                        modifier = extraModifier.focusRing(FocusShapes.Pill, outset = (-3).dp),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.simple_server_more))
                    }
                }
            },
        )
    }
}

/** The menus and dialogs of the list: [sheet] "sub:<id>" or "server:<id>", [renaming] likewise, [deleting] a group. */
@Composable
private fun ManageOverlays(
    state: ServersUiState,
    sections: ServerSections,
    locale: Locale,
    manage: ManageActions,
    sheet: String,
    renaming: String,
    deleting: Long,
    setSheet: (String) -> Unit,
    setRenaming: (String) -> Unit,
    setDeleting: (Long) -> Unit,
) {
    fun idOf(key: String, prefix: String): Long? = key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()
    fun serverOf(id: Long?) = id?.let { (sections.mine + sections.whitelist.filter { s -> s.own(sections) }).firstOrNull { s -> s.id == it } }
    // A subscription or server gone meanwhile (deleted in the classic interface) closes its menu.
    idOf(sheet, "sub:")?.let { gid ->
        state.cards[gid]?.let { card ->
            SubscriptionSheet(
                card,
                advanced = state.advanced,
                actions = manage,
                onRename = {
                    setSheet("")
                    setRenaming("sub:$gid")
                },
                onDelete = {
                    setSheet("")
                    setDeleting(gid)
                },
                onDismiss = { setSheet("") },
            )
        }
    }
    serverOf(idOf(sheet, "server:"))?.let { server ->
        val supporting = listOfNotNull(countryText(server.country, locale), latencyText(server.latency)).joinToString(" · ")
        ServerSheet(
            server,
            supporting = supporting,
            advanced = state.advanced,
            actions = manage,
            onRename = {
                setSheet("")
                setRenaming("server:${server.id}")
            },
            onDismiss = { setSheet("") },
        )
    }
    idOf(renaming, "sub:")?.let { gid ->
        state.cards[gid]?.let { card ->
            RenameDialog(card.name, onSave = { name ->
                setRenaming("")
                manage.renameSubscription(gid, name)
            }, onDismiss = { setRenaming("") })
        }
    }
    serverOf(idOf(renaming, "server:"))?.let { server ->
        RenameDialog(server.name, onSave = { name ->
            setRenaming("")
            manage.renameServer(server.id, name)
        }, onDismiss = { setRenaming("") })
    }
    if (deleting > 0L) {
        state.cards[deleting]?.let { card ->
            DeleteSubscriptionDialog(card, onDelete = {
                setDeleting(0L)
                manage.deleteSubscription(card.groupId)
            }, onDismiss = { setDeleting(0L) })
        }
    }
    state.share?.let { ShareDialog(it, onDismiss = manage.closeShare) }
}

@Composable
private fun CountryRowView(
    row: CountryRow,
    chosen: Boolean,
    open: Boolean,
    state: ServersUiState,
    locale: Locale,
    actions: ServersActions,
    onToggle: () -> Unit,
) {
    val code = row.code
    val count = pluralStringResource(R.plurals.simple_servers_count, row.servers.size, row.servers.size)
    val pick = state.pick?.takeIf { chosen }
    ChoiceRow(
        leading = { CountryFlag(code) },
        headline = if (code != null) CountryLabels.name(code, locale) else countryText(row.country, locale),
        // The row shows the country's flag already.
        supporting = pick?.let { stringResource(R.string.simple_servers_now, CountryFromName.withoutLeadingFlag(it.name)) } ?: count,
        // Every server of the country failed its check: say so rather than show no delay.
        trailing = row.bestLatency?.let { stringResource(R.string.simple_latency_ms, it) }
            ?: stringResource(R.string.simple_server_no_answer).takeIf { row.servers.all { it.latency == -1 } },
        trailingError = row.bestLatency == null && row.servers.all { it.latency == -1 },
        selected = chosen,
        // A country is chosen as a whole only when it is known; the others open to their servers.
        selectable = code != null,
        enabled = !state.busy || code == null,
        onClick = if (code != null) ({ actions.chooseCountry(row) }) else onToggle,
        extra = { extraModifier ->
            IconButton(onClick = onToggle, modifier = extraModifier.focusRing(FocusShapes.Pill, outset = (-3).dp)) {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (open) R.string.simple_servers_collapse else R.string.simple_servers_expand
                    ),
                )
            }
        },
    )
}

/**
 * One row of the list: a radio choice (or, unselectable, a plain row), with an optional extra control at the end; the
 * extra control takes the modifier it is given, which lets a remote move to it with right and back with left (it
 * lies inside the row, where the D-pad's search for the next element on the right does not look).
 */
@Composable
private fun ChoiceRow(
    leading: @Composable () -> Unit,
    headline: String,
    supporting: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
    trailingError: Boolean = false,
    selectable: Boolean = true,
    indent: Boolean = false,
    extra: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rowFocus = remember { FocusRequester() }
    val extraFocus = remember { FocusRequester() }
    // The ring of a remote's focus comes with the indication of SimpleTheme (Focus.kt).
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (extra != null) Modifier.focusRequester(rowFocus).focusProperties { right = extraFocus } else Modifier)
        // Busy rows keep a remote's focus (Focus.kt unavailable).
        .unavailable(!enabled)
        .selectable(selected = selected, role = if (selectable) Role.RadioButton else Role.Button) { if (enabled) onClick() }
        .padding(start = if (indent) 24.dp else 0.dp)
    ListItem(
        modifier = rowModifier,
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = leading,
        headlineContent = { Text(headline, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = supporting?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (trailingError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                extra?.invoke(Modifier.focusRequester(extraFocus).focusProperties { left = rowFocus })
                if (selectable) {
                    if (trailing != null && extra == null) Spacer(Modifier.width(8.dp))
                    RadioButton(selected = selected, onClick = null, enabled = enabled)
                }
            }
        },
    )
}

@Composable
private fun CountryFlag(code: String?) {
    Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
        val flag = code?.let { CountryFromName.flagOf(it) }.orEmpty()
        Text(
            flag.ifEmpty { "?" },
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (flag.isEmpty()) FontWeight.Bold else null,
        )
    }
}

@Composable
private fun SectionHeader(title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubscriptionCardView(
    card: SubscriptionCard,
    locale: Locale,
    onUpdate: () -> Unit,
    onMore: () -> Unit,
    onAnnounce: () -> Unit,
    onProvider: (Long, String) -> Unit,
) {
    val context = LocalContext.current
    val warning = warningColor()
    val error = MaterialTheme.colorScheme.error
    val normal = MaterialTheme.colorScheme.onSurfaceVariant
    val now = nowSeconds()
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.LONG, locale) }
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                when (card.traffic) {
                    SubscriptionHealth.Traffic.NONE -> Unit
                    SubscriptionHealth.Traffic.UNLIMITED ->
                        InfoLine(stringResource(R.string.simple_sub_traffic_unlimited), normal)

                    SubscriptionHealth.Traffic.EXHAUSTED ->
                        InfoLine(stringResource(R.string.simple_sub_traffic_exhausted), error)

                    SubscriptionHealth.Traffic.OK, SubscriptionHealth.Traffic.LOW -> InfoLine(
                        stringResource(
                            R.string.simple_sub_traffic_left,
                            trafficSize(context, card.remaining ?: 0L),
                            trafficSize(context, card.total),
                        ),
                        if (card.traffic == SubscriptionHealth.Traffic.LOW) warning else normal,
                    )
                }
                val date = if (card.expire > 0L) dateFormat.format(Date(card.expire * 1000)) else ""
                // From the ticking clock, so an end passing while the section is open shows at once.
                when (SubscriptionHealth.expiry(card.expire, now)) {
                    SubscriptionHealth.Expiry.NONE -> Unit
                    SubscriptionHealth.Expiry.OK -> InfoLine(stringResource(R.string.simple_summary_expires, date), normal)
                    SubscriptionHealth.Expiry.SOON -> {
                        val days = SubscriptionHealth.daysLeft(card.expire, now)
                        InfoLine(
                            if (days == 0) stringResource(R.string.simple_sub_expires_today)
                            else pluralStringResource(R.plurals.simple_sub_expires_soon, days, days, date),
                            warning,
                        )
                    }

                    SubscriptionHealth.Expiry.EXPIRED -> InfoLine(stringResource(R.string.simple_summary_expired, date), error)
                }
                InfoLine(updatedText(card.lastUpdate, now, dateFormat), normal)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (card.refreshing) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onUpdate, modifier = Modifier.focusRing(FocusShapes.Pill, outset = (-3).dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.simple_sub_update))
                    }
                }
            }
            IconButton(onClick = onMore, modifier = Modifier.focusRing(FocusShapes.Pill, outset = (-3).dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.simple_sub_more))
            }
        }
        card.blocked?.let { BlockedPart(it, onUpdate, onProvider, onAnnounce) }
        // The provider's announcement, unless the red message quotes it already: one line, the whole in its sheet.
        val announce = card.facts?.announce.orEmpty()
        if (announce.isNotEmpty() && card.blocked?.message != announce) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onAnnounce)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.simple_ic_campaign),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.simple_sub_announce_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(announce, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The red part of a subscription card the VPN cannot work through: why (when the lines above do not say it), what the
 * provider said, «Проверить снова» and the provider's page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockedPart(notice: Notice, onUpdate: () -> Unit, onProvider: (Long, String) -> Unit, onAnnounce: () -> Unit) {
    val context = LocalContext.current
    val texts = remember(notice) { NoticeTexts.of(context, notice) }
    val error = MaterialTheme.colorScheme.error
    val facts = notice.facts
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        // The end and the traffic are on the card's lines already.
        if (notice.reason != NoticeReason.EXPIRED && notice.reason != NoticeReason.EXHAUSTED) {
            Text(texts.body, style = MaterialTheme.typography.bodyMedium, color = error)
        }
        if (texts.quote.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Quote(texts.quote, MaterialTheme.colorScheme.onSurface, onOpen = onAnnounce.takeIf { texts.quote == facts.announce })
        }
        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(onClick = onUpdate, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_notice_recheck))
            }
            val renewing = notice.reason == NoticeReason.EXPIRED || notice.reason == NoticeReason.EXHAUSTED
            when {
                facts.webUrl.isNotEmpty() -> Button(
                    onClick = { onProvider(facts.groupId, facts.webUrl) },
                    modifier = Modifier.focusRing(FocusShapes.Pill),
                ) { Text(if (renewing) texts.renewLabel else stringResource(R.string.simple_notice_account)) }

                facts.supportUrl.isNotEmpty() -> Button(
                    onClick = { onProvider(facts.groupId, facts.supportUrl) },
                    modifier = Modifier.focusRing(FocusShapes.Pill),
                ) { Text(stringResource(R.string.simple_notice_support)) }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun EmptyServers(onAdd: () -> Unit, modifier: Modifier, focus: Boolean) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.simple_servers_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onAdd,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier
                    .focusRing(FocusShapes.Pill)
                    .focusWhenShown(enabled = focus),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.simple_add_server))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ texts

@Composable
private fun countryText(country: ServerCountry, locale: Locale): String = when (country) {
    is ServerCountry.Known -> CountryLabels.name(country.code, locale)
    ServerCountry.Detecting -> stringResource(R.string.simple_country_detecting)
    ServerCountry.Unknown -> stringResource(R.string.simple_country_unknown)
}

@Composable
private fun latencyText(latency: Int): String? = when {
    latency > 0 -> stringResource(R.string.simple_latency_ms, latency)
    latency == ProxyEntity.LATENCY_CONNECT_ONLY -> stringResource(R.string.simple_server_answers)
    latency < 0 -> stringResource(R.string.simple_server_no_answer)
    else -> null
}

/** The pick's name after one flag and a space: its own leading flag, else its country's flag. */
private fun pickLabel(pick: RunningPick): String {
    val tidy = CountryFromName.tidy(pick.name)
    val name = CountryFromName.withoutLeadingFlag(tidy)
    // Nothing but a flag.
    if (name == tidy && CountryFromName.startsWithFlag(tidy)) return tidy
    val flag = if (name != tidy) tidy.removeSuffix(name).trim() else pick.country?.let { CountryFromName.flagOf(it) }
    return if (flag.isNullOrEmpty()) name else "$flag $name"
}

@Composable
private fun updatedText(lastUpdate: Long, now: Long, dateFormat: DateFormat): String {
    if (lastUpdate <= 0L) return stringResource(R.string.simple_sub_never_updated)
    val ago = (now - lastUpdate).coerceAtLeast(0L)
    return when {
        ago < 60 -> stringResource(R.string.simple_sub_updated_now)
        ago < 60 * 60 -> (ago / 60).toInt().let { pluralStringResource(R.plurals.simple_sub_updated_minutes, it, it) }
        ago < 24 * 60 * 60 -> (ago / 3600).toInt().let { pluralStringResource(R.plurals.simple_sub_updated_hours, it, it) }
        else -> stringResource(R.string.simple_sub_updated_date, dateFormat.format(Date(lastUpdate * 1000)))
    }
}

/** The current time in epoch seconds, ticking every half minute so "updated N minutes ago" stays right. */
@Composable
private fun nowSeconds(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis() / 1000
        }
    }
    return now
}

/** An amber readable on the surface, for what is about to run out. */
@Composable
private fun warningColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFC966) else Color(0xFF8A5A00)

/** A server of the user's own (a group that is no subscription), wherever the list puts it. */
private fun ServerEntry.own(sections: ServerSections): Boolean = sections.subscriptions.none { it.group.id == groupId }
