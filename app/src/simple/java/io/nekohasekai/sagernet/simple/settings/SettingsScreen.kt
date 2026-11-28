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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.FocusShapes
import io.nekohasekai.sagernet.simple.focusRing
import io.nekohasekai.sagernet.simple.focusWhenShown
import io.nekohasekai.sagernet.simple.rememberFocusWhenShown
import io.nekohasekai.sagernet.simple.unavailable
import io.nekohasekai.sagernet.simple.modes.AppList
import io.nekohasekai.sagernet.simple.modes.ModeCard
import io.nekohasekai.sagernet.simple.modes.ModesUiState
import io.nekohasekai.sagernet.simple.modes.TrafficMode
import io.nekohasekai.sagernet.utils.AlwaysOnVpn

/** What the settings section opens and changes. */
class SettingsActions(
    val selectMode: (TrafficMode) -> Unit,
    val editApps: (AppList) -> Unit,
    val dismissFallback: () -> Unit,
    val setAutostart: (Boolean) -> Unit,
    val setAutoUpdate: (Boolean) -> Unit,
    val setNightMode: (NightMode) -> Unit,
    val setAdvanced: (Boolean) -> Unit,
    /** The daily update check and pre-releases (advanced mode, in-app updater builds). */
    val setUpdateCheck: (Boolean) -> Unit,
    val setBetaUpdates: (Boolean) -> Unit,
    /** «Разрешить управление VPN другим приложениям» (advanced mode; restrict-exported-vpn-controls). */
    val setExternalControl: (Boolean) -> Unit,
    /** A classic screen: the backup («Перенос на другое устройство») or a screen of the advanced mode. */
    val openClassicScreen: (ClassicScreen) -> Unit,
    /** The whole classic interface (advanced mode, after [ClassicWarningDialog]); not remembered (see SimpleUi). */
    val openClassic: () -> Unit,
    /** «Починка» (batch 7). */
    val openRepair: () -> Unit,
    /** The system VPN settings, where «Постоянный VPN» and «Блокировать соединения без VPN» are. */
    val openVpnSettings: () -> Unit,
    /** The in-app updater's check, at once; null where the build has no in-app updater (fdroid). */
    val checkUpdates: (() -> Unit)?,
)

/**
 * The settings section (simple-ui "Короткие настройки упрощённого интерфейса", "Расширенный режим"): the traffic mode,
 * «Приложения без VPN», auto connect at boot, automatic subscription updates, the theme, the move to another device
 * (the classic backup) and the advanced mode with its links to the classic screens and the classic interface. Nothing
 * technical shows while the advanced mode is off; the classic interface opens only after a warning, since it is meant
 * for a user support sends there.
 *
 * The first item of the list is the accent «Починка» button (simple-ui "Вход в «Починку» в настройках", batch 7).
 */
@Composable
fun SettingsScreen(
    state: ModesUiState,
    settings: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var classicDialog by rememberSaveable { mutableStateOf(false) }
    // A remote (D-pad) starts on «Починка»; asked here, not in the item, which appears again as it scrolls into view.
    val list = rememberLazyListState()
    val repairFocus = rememberFocusWhenShown(bringIntoComposition = { list.scrollToItem(0) })
    LazyColumn(modifier.fillMaxSize(), state = list) {
        item { RepairEntry(actions.openRepair, Modifier.focusRequester(repairFocus)) }
        modeItems(state, actions)

        item { SectionDivider() }
        item { Header(R.string.simple_settings_connection) }
        item {
            SwitchItem(
                title = stringResource(R.string.simple_settings_autostart),
                summary = stringResource(R.string.simple_settings_autostart_summary),
                checked = settings.autostart,
                onChange = actions.setAutostart,
            )
        }
        item {
            SwitchItem(
                title = stringResource(R.string.simple_settings_auto_update),
                summary = stringResource(R.string.simple_settings_auto_update_summary),
                checked = settings.autoUpdate,
                onChange = actions.setAutoUpdate,
            )
        }
        item {
            NavigationItem(
                title = stringResource(R.string.simple_settings_always_on),
                summary = stringResource(settings.alwaysOn.summary),
                onClick = actions.openVpnSettings,
            )
        }

        item { SectionDivider() }
        item { Header(R.string.simple_settings_general) }
        item {
            NavigationItem(
                title = stringResource(R.string.simple_settings_theme),
                summary = stringResource(settings.nightMode.label),
                arrow = false,
            ) { themeDialog = true }
        }
        item { ClassicScreenItem(ClassicScreen.BACKUP, actions.openClassicScreen) }
        item {
            SwitchItem(
                title = stringResource(R.string.simple_settings_advanced),
                summary = stringResource(R.string.simple_settings_advanced_summary),
                checked = settings.advanced,
                onChange = actions.setAdvanced,
            )
        }
        actions.checkUpdates?.let { check -> updateItems(check, settings, actions) }

        if (settings.advanced) {
            item { SectionDivider() }
            item { Header(R.string.simple_advanced_header) }
            item {
                SwitchItem(
                    title = stringResource(R.string.simple_external_control),
                    summary = stringResource(R.string.simple_external_control_summary),
                    checked = settings.externalControl,
                    onChange = actions.setExternalControl,
                )
            }
            items(ClassicScreen.entries.filter { it.advanced }, key = { it.name }) { screen ->
                ClassicScreenItem(screen, actions.openClassicScreen)
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.simple_settings_classic)) },
                    supportingContent = { Text(stringResource(R.string.simple_settings_classic_summary)) },
                    modifier = Modifier.clickable { classicDialog = true },
                )
            }
        }
    }

    if (classicDialog) {
        ClassicWarningDialog(
            onOpen = {
                classicDialog = false
                actions.openClassic()
            },
            onDismiss = { classicDialog = false },
        )
    }

    if (themeDialog) {
        ThemeDialog(
            current = settings.nightMode,
            onSelect = {
                themeDialog = false
                actions.setNightMode(it)
            },
            onDismiss = { themeDialog = false },
        )
    }
}

/** The accent «Починка» button at the top of the section. */
@Composable
private fun RepairEntry(onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .focusRing(FocusShapes.Card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Build, contentDescription = null)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(stringResource(R.string.simple_repair_entry), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.simple_repair_entry_summary), style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** The traffic mode, its app list and «Приложения без VPN» (batch 3). */
private fun LazyListScope.modeItems(state: ModesUiState, actions: SettingsActions) {
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
    item { SectionDivider() }
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
}

/**
 * «Обновления», only where the in-app updater exists: «Проверить обновления» ([check]) always, and in the advanced
 * mode the switches of the classic Settings › General › Updates on the same keys — the daily check and pre-releases
 * (simple-ui "Короткие настройки упрощённого интерфейса", "Переключатели обновлений в расширенном режиме").
 */
private fun LazyListScope.updateItems(check: () -> Unit, settings: SettingsUiState, actions: SettingsActions) {
    item { SectionDivider() }
    item { Header(R.string.simple_updates_header) }
    item {
        NavigationItem(
            title = stringResource(R.string.simple_settings_check_updates),
            summary = stringResource(R.string.simple_settings_check_updates_summary, BuildConfig.VERSION_NAME),
            arrow = false,
            onClick = check,
        )
    }
    if (!settings.advanced) return
    item {
        SwitchItem(
            title = stringResource(R.string.simple_update_check),
            summary = stringResource(R.string.simple_update_check_summary),
            checked = settings.updateCheck,
            onChange = actions.setUpdateCheck,
        )
    }
    item {
        SwitchItem(
            title = stringResource(R.string.simple_update_beta),
            summary = stringResource(R.string.simple_update_beta_summary),
            checked = settings.betaUpdates,
            onChange = actions.setBetaUpdates,
        )
    }
}

private val NightMode.label: Int
    @StringRes get() = when (this) {
        NightMode.SYSTEM -> R.string.simple_theme_system
        NightMode.LIGHT -> R.string.simple_theme_light
        NightMode.DARK -> R.string.simple_theme_dark
        NightMode.BATTERY -> R.string.simple_theme_battery
    }

@Composable
private fun SectionDivider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))

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
        // While a mode is applied the rows stay focusable, so a remote keeps its place (Focus.kt unavailable).
        modifier = Modifier
            .unavailable(!enabled)
            .selectable(selected = selected, role = Role.RadioButton) { if (enabled) onClick() },
    )
}

@Composable
private fun NavigationItem(
    title: String,
    summary: String,
    enabled: Boolean = true,
    arrow: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = if (arrow) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier
            .unavailable(!enabled)
            .clickable { if (enabled) onClick() },
    )
}

@Composable
private fun ClassicScreenItem(screen: ClassicScreen, open: (ClassicScreen) -> Unit) {
    NavigationItem(title = stringResource(screen.title), summary = stringResource(screen.summary)) { open(screen) }
}

@Composable
private fun SwitchItem(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun ThemeDialog(current: NightMode, onSelect: (NightMode) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.simple_settings_theme)) },
        text = {
            Column(Modifier.selectableGroup()) {
                SettingsValues.nightModes(current).forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (mode == current) Modifier.focusWhenShown() else Modifier)
                            .selectable(selected = mode == current, role = Role.RadioButton) { onSelect(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(
                            stringResource(mode.label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill)) { Text(stringResource(R.string.simple_cancel)) }
        },
    )
}

/**
 * The warning before the classic interface: only on support's word, since its settings can break this interface
 * for good. Cancel has the focus, so a stray press of a remote does not open it.
 */
@Composable
private fun ClassicWarningDialog(onOpen: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.simple_classic_warning_title)) },
        text = {
            Text(
                stringResource(R.string.simple_classic_warning_text),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onOpen, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_classic_warning_open))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill).focusWhenShown()) {
                Text(stringResource(R.string.simple_cancel))
            }
        },
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
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill)) { Text(stringResource(R.string.simple_ok)) }
        }
    }
}

/** «Постоянный VPN»: lockdown keeps apps from going online around the VPN while it reconnects or switches servers. */
@get:StringRes
private val AlwaysOnVpn.State.summary: Int
    get() = when (this) {
        AlwaysOnVpn.State.OFF -> R.string.simple_settings_always_on_off
        AlwaysOnVpn.State.ON -> R.string.simple_settings_always_on_on
        AlwaysOnVpn.State.LOCKDOWN -> R.string.simple_settings_always_on_lockdown
        AlwaysOnVpn.State.UNKNOWN -> R.string.simple_settings_always_on_unknown
    }
