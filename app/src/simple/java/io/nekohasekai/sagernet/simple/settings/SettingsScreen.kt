package io.nekohasekai.sagernet.simple.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.modes.AppList
import io.nekohasekai.sagernet.simple.modes.ModeCard
import io.nekohasekai.sagernet.simple.modes.ModesUiState
import io.nekohasekai.sagernet.simple.modes.TrafficMode

/** What the settings section opens. */
class SettingsActions(
    val selectMode: (TrafficMode) -> Unit,
    val editApps: (AppList) -> Unit,
    val dismissFallback: () -> Unit,
    val openClassic: () -> Unit,
)

/**
 * The settings section: the traffic mode, «Приложения без VPN» and the classic interface. The rest of the settings
 * (simple-ui spec "Раздел «Настройки»") arrives with batch 10.
 */
@Composable
fun SettingsScreen(state: ModesUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize()) {
        item { Header(R.string.simple_settings_mode) }
        if (state.fallback) {
            item { FallbackNotice(actions.dismissFallback) }
        }
        val custom = state.card == ModeCard.CUSTOM
        if (custom) {
            // A routing profile picked in the classic interface is in effect, so no mode shows as chosen.
            item {
                Text(
                    stringResource(R.string.simple_settings_mode_custom),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        item {
            Column(Modifier.selectableGroup()) {
                TrafficMode.entries.forEach { mode ->
                    ModeOption(mode, selected = !custom && state.mode == mode, enabled = !state.busy) { actions.selectMode(mode) }
                }
            }
        }
        if (state.mode == TrafficMode.APPS) {
            item {
                NavigationItem(
                    title = stringResource(R.string.simple_settings_apps_selected),
                    summary = pluralStringResource(R.plurals.simple_apps_count, state.selectedApps, state.selectedApps),
                    enabled = !state.busy,
                ) { actions.editApps(AppList.SELECTED) }
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            NavigationItem(
                title = stringResource(R.string.simple_settings_apps_bypass),
                summary = if (state.bypassApps == 0) {
                    stringResource(R.string.simple_settings_apps_bypass_none)
                } else {
                    pluralStringResource(R.plurals.simple_apps_count, state.bypassApps, state.bypassApps)
                },
                enabled = !state.busy,
            ) { actions.editApps(AppList.BYPASS) }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        item {
            ListItem(
                headlineContent = { Text(stringResource(R.string.simple_settings_classic)) },
                supportingContent = { Text(stringResource(R.string.simple_settings_classic_summary)) },
                modifier = Modifier.clickable(onClick = actions.openClassic),
            )
        }
    }
}

@Composable
private fun Header(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun ModeOption(mode: TrafficMode, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val (title, summary) = when (mode) {
        TrafficMode.AUTO -> R.string.simple_mode_auto to R.string.simple_mode_auto_summary
        TrafficMode.SMART -> R.string.simple_mode_smart to R.string.simple_mode_smart_summary
        TrafficMode.WHITELIST -> R.string.simple_mode_whitelist to R.string.simple_mode_whitelist_summary
        TrafficMode.APPS -> R.string.simple_mode_apps to R.string.simple_mode_apps_summary
    }
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(summary)) },
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun NavigationItem(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/** «Только выбранные приложения» lost its last app, so «Авто» is on (traffic-modes "Пустой список выбранных приложений"). */
@Composable
private fun FallbackNotice(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Text(
                stringResource(R.string.simple_mode_fallback_notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(end = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.simple_ok)) }
        }
    }
}
