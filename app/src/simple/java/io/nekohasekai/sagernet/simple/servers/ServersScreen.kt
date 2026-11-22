package io.nekohasekai.sagernet.simple.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
)

/**
 * «Серверы»: «Авто» first, then each subscription (its card, then its countries, each opening to its servers), then
 * «Мои серверы» by name, then «Для белых списков». Pull to refresh updates the subscriptions. Rows show only the
 * name, the country and the delay (simple-ui spec "Скрытие технических деталей").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(state: ServersUiState, actions: ServersActions, modifier: Modifier = Modifier) {
    val sections = state.sections
    when {
        sections == null -> Box(modifier.fillMaxSize())
        sections.isEmpty && state.cards.isEmpty() -> EmptyServers(actions.add, modifier)
        else -> PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.updateAll,
            modifier = modifier.fillMaxSize(),
        ) {
            ServerListView(state, sections, actions)
        }
    }
}

@Composable
private fun ServerListView(state: ServersUiState, sections: ServerSections, actions: ServersActions) {
    val locale = LocalConfiguration.current.locales[0]
    // Expanded countries, as "<group>:<country>" keys joined by newlines (saveable as one string).
    var expandedKeys by rememberSaveable { mutableStateOf("") }
    val expanded = expandedKeys.split('\n').filter { it.isNotEmpty() }.toSet()
    fun toggle(key: String) {
        expandedKeys = (if (key in expanded) expanded - key else expanded + key).joinToString("\n")
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
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
            )
        }
        for (subscription in sections.subscriptions) {
            val group = subscription.group
            item(key = "sub-${group.id}") {
                state.cards[group.id]?.let { SubscriptionCardView(it, locale) { actions.update(group.id) } }
            }
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
            servers(sections.mine, "mine", state, locale, actions)
        }
        if (sections.whitelist.isNotEmpty()) {
            item(key = "whitelist") {
                SectionHeader(
                    stringResource(R.string.simple_servers_whitelist),
                    stringResource(R.string.simple_servers_whitelist_hint),
                )
            }
            servers(sections.whitelist, "wl", state, locale, actions)
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
        )
    }
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
        extra = {
            IconButton(onClick = onToggle) {
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

/** One row of the list: a radio choice (or, unselectable, a plain row), with an optional extra control at the end. */
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
    extra: (@Composable () -> Unit)? = null,
) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .selectable(selected = selected, enabled = enabled, role = if (selectable) Role.RadioButton else Role.Button, onClick = onClick)
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
                extra?.invoke()
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
private fun SubscriptionCardView(card: SubscriptionCard, locale: Locale, onUpdate: () -> Unit) {
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
                when (card.expiry) {
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
                    IconButton(onClick = onUpdate) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.simple_sub_update))
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun EmptyServers(onAdd: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                stringResource(R.string.simple_servers_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAdd, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
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
