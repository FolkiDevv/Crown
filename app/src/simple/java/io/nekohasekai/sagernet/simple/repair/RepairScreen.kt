package io.nekohasekai.sagernet.simple.repair

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import kotlin.math.abs

/** Where a button of «Починка» leads: a system settings screen (only the user changes Android's settings) or «Серверы». */
enum class RepairAction(@StringRes val label: Int) {
    DATE_SETTINGS(R.string.simple_repair_open_date),
    NETWORK_SETTINGS(R.string.simple_repair_open_network),
    VPN_SETTINGS(R.string.simple_repair_open_vpn),
    WIFI_SETTINGS(R.string.simple_repair_open_wifi),
    BACKGROUND(R.string.simple_repair_allow_background),
    SERVERS(R.string.simple_repair_open_servers),
}

class RepairActions(
    val stop: () -> Unit,
    val again: () -> Unit,
    val close: () -> Unit,
    val open: (RepairAction) -> Unit,
    /** «Экспортировать отчёт» (batch 9). */
    val export: () -> Unit = {},
)

/**
 * The «Починка» screen (connection-repair "Ход проверки", "Итоговый вердикт"): the checks in their order, a spinner at
 * the one in progress, the result and a short explanation at the finished ones, buttons to the system settings where
 * only the user can change something, and the verdict at the end. Back (or the arrow) leaves it and stops a run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairScreen(
    state: RepairUiState,
    /** The service's message of a connection that was lost by itself, if the home banner shows one. */
    detail: String?,
    actions: RepairActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = actions.close)
    val progress = state.progress ?: RepairProgress.initial()
    Scaffold(
        // Full screen without the navigation suite: clear of the system bars and cutouts on every side.
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.simple_repair_title)) },
                navigationIcon = {
                    IconButton(onClick = actions.close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.simple_repair_back))
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.running) {
                        OutlinedButton(onClick = actions.stop, modifier = Modifier.widthIn(min = 200.dp)) {
                            Text(stringResource(R.string.simple_repair_stop))
                        }
                    } else {
                        Button(onClick = actions.again, modifier = Modifier.widthIn(min = 200.dp)) {
                            Text(stringResource(R.string.simple_repair_again))
                        }
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val width = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
            state.verdict?.let { verdict ->
                item(key = "verdict") {
                    VerdictCard(verdict, progress.results, actions.open, width.padding(bottom = 12.dp)) {
                        ExportButton(state, actions.export, main = verdict is RepairVerdict.Unknown)
                    }
                }
            }
            if (!detail.isNullOrBlank()) {
                item(key = "detail") {
                    Text(
                        stringResource(R.string.simple_repair_details, detail.take(600)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = width.padding(vertical = 4.dp),
                    )
                }
            }
            when {
                state.running -> item(key = "running") {
                    Text(
                        stringResource(if (progress.undoing) R.string.simple_repair_undoing else R.string.simple_repair_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = width.padding(vertical = 8.dp),
                    )
                }

                state.stopped -> item(key = "stopped") {
                    Column(width.padding(vertical = 8.dp)) {
                        Text(stringResource(R.string.simple_repair_stopped), style = MaterialTheme.typography.titleMedium)
                        if (state.canExport) ExportButton(state, actions.export, main = false)
                    }
                }
            }
            items(RepairPlan.ORDER, key = { it.name }) { step ->
                StepRow(step, progress.steps[step] ?: StepState.Pending, actions.open, width)
            }
        }
    }
}

@Composable
private fun StepRow(step: RepairStep, state: StepState, open: (RepairAction) -> Unit, modifier: Modifier) {
    val result = (state as? StepState.Done)?.result
    Row(modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusIcon(state) }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(stringResource(step.title), style = MaterialTheme.typography.titleSmall)
            if (result != null) {
                for (line in explanation(step, result)) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (action in stepActions(step, result)) {
                    TextButton(onClick = { open(action) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(stringResource(action.label))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(state: StepState) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        StepState.Pending -> Box(
            Modifier
                .size(16.dp)
                .border(2.dp, colors.outlineVariant, CircleShape)
        )

        StepState.Running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        is StepState.Done -> when (state.result.status) {
            StepStatus.OK -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.primary)
            StepStatus.WARN -> Icon(Icons.Filled.Warning, contentDescription = null, tint = warningColor())
            StepStatus.FAIL -> Icon(Icons.Filled.Close, contentDescription = null, tint = colors.error)
            StepStatus.FIXED -> Icon(Icons.Filled.Build, contentDescription = null, tint = colors.primary)
            // The explanation says «Не проверено»; a screen reader need not read the dash.
            StepStatus.SKIPPED -> Text(
                "—",
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

@Composable
private fun VerdictCard(
    verdict: RepairVerdict,
    results: Map<RepairStep, StepResult>,
    open: (RepairAction) -> Unit,
    modifier: Modifier,
    /** The report export, at the end of the card with any verdict. */
    export: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (verdict) {
        is RepairVerdict.Fixed -> colors.primaryContainer to colors.onPrimaryContainer
        is RepairVerdict.CauseFound -> colors.tertiaryContainer to colors.onTertiaryContainer
        is RepairVerdict.Unknown -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val heading = when (verdict) {
                is RepairVerdict.Fixed -> R.string.simple_repair_verdict_fixed
                is RepairVerdict.CauseFound -> R.string.simple_repair_verdict_found
                is RepairVerdict.Unknown -> R.string.simple_repair_verdict_unknown
            }
            Text(stringResource(heading), style = MaterialTheme.typography.labelLarge)
            when (verdict) {
                is RepairVerdict.CauseFound -> {
                    val (title, steps) = verdict.cause.texts
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(steps), style = MaterialTheme.typography.bodyMedium)
                }

                is RepairVerdict.Unknown -> Text(
                    stringResource(
                        when {
                            !verdict.nothingFound -> R.string.simple_repair_unknown_text
                            verdict.notChecked -> R.string.simple_repair_unknown_partial
                            else -> R.string.simple_repair_unknown_clean
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                is RepairVerdict.Fixed -> Unit
            }
            // Each fix says what it changed, so the verdict lists them as they are.
            for ((_, fix) in verdict.fixes) {
                Text(
                    stringResource(fix.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val action = (verdict as? RepairVerdict.CauseFound)?.cause?.action(results)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = { open(action) }) { Text(stringResource(action.label)) }
            }
            export()
        }
    }
}

/**
 * «Экспортировать отчёт»: the main button of «Причину определить не удалось» ([main]), a quieter one with the other
 * outcomes and after a stop (connection-repair "Экспорт отчёта": with any outcome).
 */
@Composable
private fun ExportButton(state: RepairUiState, export: () -> Unit, main: Boolean) {
    val label = stringResource(if (state.exporting) R.string.simple_repair_exporting else R.string.simple_repair_export)
    val enabled = state.canExport && !state.exporting && state.pendingShare == null
    if (main) {
        Spacer(Modifier.height(4.dp))
        Button(onClick = export, enabled = enabled) { Text(label) }
    } else {
        TextButton(onClick = export, enabled = enabled, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(label) }
    }
}

// ------------------------------------------------------------------------------------------------ texts

private val RepairStep.title: Int
    get() = when (this) {
        RepairStep.INTERNET -> R.string.simple_repair_step_internet
        RepairStep.WHITELISTS -> R.string.simple_repair_step_whitelists
        RepairStep.CLOCK -> R.string.simple_repair_step_clock
        RepairStep.PRIVATE_DNS_VPN -> R.string.simple_repair_step_private_dns
        RepairStep.DNS_DIRECT -> R.string.simple_repair_step_dns_direct
        RepairStep.SUBSCRIPTION -> R.string.simple_repair_step_subscription
        RepairStep.SERVERS -> R.string.simple_repair_step_servers
        RepairStep.DNS_VPN -> R.string.simple_repair_step_dns_vpn
        RepairStep.SITES_VPN -> R.string.simple_repair_step_sites_vpn
        RepairStep.RU_DIRECT -> R.string.simple_repair_step_ru_direct
        RepairStep.BACKGROUND -> R.string.simple_repair_step_background
    }

private val RepairVerdict.Cause.texts: Pair<Int, Int>
    get() = when (this) {
        RepairVerdict.Cause.NO_INTERNET -> R.string.simple_repair_cause_no_internet to R.string.simple_repair_cause_no_internet_steps
        RepairVerdict.Cause.CLOCK -> R.string.simple_repair_cause_clock to R.string.simple_repair_cause_clock_steps
        RepairVerdict.Cause.OTHER_VPN -> R.string.simple_repair_cause_other_vpn to R.string.simple_repair_cause_other_vpn_steps
        RepairVerdict.Cause.PRIVATE_DNS -> R.string.simple_repair_cause_private_dns to R.string.simple_repair_cause_private_dns_steps
        RepairVerdict.Cause.SUBSCRIPTION_ENDED -> R.string.simple_repair_cause_sub_ended to R.string.simple_repair_cause_sub_ended_steps
        RepairVerdict.Cause.SUBSCRIPTION_EMPTY -> R.string.simple_repair_cause_sub_empty to R.string.simple_repair_cause_sub_empty_steps
        RepairVerdict.Cause.NO_SERVERS -> R.string.simple_repair_cause_no_servers to R.string.simple_repair_cause_no_servers_steps
        RepairVerdict.Cause.WHITELISTS_NO_SERVERS ->
            R.string.simple_repair_cause_wl_no_servers to R.string.simple_repair_cause_wl_no_servers_steps

        RepairVerdict.Cause.WHITELISTS_SERVERS_DOWN -> R.string.simple_repair_cause_wl_down to R.string.simple_repair_cause_wl_down_steps
        RepairVerdict.Cause.ALL_SERVERS_DOWN -> R.string.simple_repair_cause_servers_down to R.string.simple_repair_cause_servers_down_steps
        RepairVerdict.Cause.CURRENT_SERVER_DOWN -> R.string.simple_repair_cause_current_down to R.string.simple_repair_cause_current_down_steps
        RepairVerdict.Cause.BATTERY -> R.string.simple_repair_cause_battery to R.string.simple_repair_cause_battery_steps
    }

/** The main button of a cause, if the user can do something about it from here. */
private fun RepairVerdict.Cause.action(results: Map<RepairStep, StepResult>): RepairAction? = when (this) {
    RepairVerdict.Cause.NO_INTERNET -> RepairAction.NETWORK_SETTINGS
    RepairVerdict.Cause.CLOCK -> RepairAction.DATE_SETTINGS
    RepairVerdict.Cause.OTHER_VPN -> RepairAction.VPN_SETTINGS
    RepairVerdict.Cause.PRIVATE_DNS -> RepairAction.NETWORK_SETTINGS
    RepairVerdict.Cause.SUBSCRIPTION_ENDED, RepairVerdict.Cause.SUBSCRIPTION_EMPTY, RepairVerdict.Cause.NO_SERVERS,
    RepairVerdict.Cause.ALL_SERVERS_DOWN, RepairVerdict.Cause.CURRENT_SERVER_DOWN -> RepairAction.SERVERS

    RepairVerdict.Cause.WHITELISTS_NO_SERVERS, RepairVerdict.Cause.WHITELISTS_SERVERS_DOWN -> RepairAction.WIFI_SETTINGS
    RepairVerdict.Cause.BATTERY -> RepairAction.BACKGROUND.takeIf { results[RepairStep.BACKGROUND] != null }
}

/** The buttons under a check's result: the system settings where only the user can change something. */
private fun stepActions(step: RepairStep, result: StepResult): List<RepairAction> {
    if (result.status != StepStatus.FAIL && result.status != StepStatus.WARN) return emptyList()
    return when (step) {
        RepairStep.CLOCK -> listOf(RepairAction.DATE_SETTINGS)
        RepairStep.PRIVATE_DNS_VPN -> buildList {
            if (result.has(Finding.PRIVATE_DNS)) add(RepairAction.NETWORK_SETTINGS)
            if (result.has(Finding.OTHER_VPN)) add(RepairAction.VPN_SETTINGS)
        }

        RepairStep.BACKGROUND -> listOf(RepairAction.BACKGROUND)
        else -> emptyList()
    }
}

/** The short explanation of a result, one line per finding. */
@Composable
private fun explanation(step: RepairStep, result: StepResult): List<String> {
    if (result.status == StepStatus.SKIPPED) return listOf(stringResource(result.skip.text))
    val values = result.values
    fun value(key: String) = (values[key] ?: 0L).toInt()
    val lines = mutableListOf<String>()
    when (step) {
        RepairStep.INTERNET -> lines += when {
            result.has(Finding.NO_NETWORK) -> stringResource(R.string.simple_repair_internet_no_network)
            result.has(Finding.NOTHING_ANSWERS) -> stringResource(R.string.simple_repair_internet_nothing)
            else -> stringResource(R.string.simple_repair_internet_ok, value(RepairValues.REACHED), value(RepairValues.ASKED))
        }

        RepairStep.WHITELISTS -> lines += stringResource(
            if (result.has(Finding.WHITELISTS_FOUND)) R.string.simple_repair_whitelists_found else R.string.simple_repair_whitelists_none
        )

        RepairStep.CLOCK -> lines += if (result.has(Finding.CLOCK_OFF)) {
            val offset = values[RepairValues.CLOCK_OFFSET_S] ?: 0L
            stringResource(
                if (offset > 0) R.string.simple_repair_clock_ahead else R.string.simple_repair_clock_behind,
                duration(abs(offset)),
            )
        } else {
            stringResource(R.string.simple_repair_clock_ok)
        }

        RepairStep.PRIVATE_DNS_VPN -> {
            if (result.has(Finding.PRIVATE_DNS)) lines += stringResource(R.string.simple_repair_private_dns)
            if (result.has(Finding.OTHER_VPN)) lines += stringResource(R.string.simple_repair_other_vpn)
            if (lines.isEmpty()) lines += stringResource(R.string.simple_repair_private_ok)
        }

        RepairStep.DNS_DIRECT -> lines += stringResource(
            if (result.has(Finding.DNS_NO_ANSWER)) R.string.simple_repair_dns_fail else R.string.simple_repair_dns_ok
        )

        RepairStep.SUBSCRIPTION -> {
            if (result.has(Finding.SUBSCRIPTION_EXPIRED)) lines += stringResource(R.string.simple_repair_sub_expired)
            if (result.has(Finding.TRAFFIC_EXHAUSTED)) lines += stringResource(R.string.simple_repair_sub_exhausted)
            if (result.has(Finding.SUBSCRIPTION_EMPTY)) lines += stringResource(R.string.simple_repair_sub_empty)
            if (result.has(Finding.SUBSCRIPTION_ENDS_SOON)) {
                val days = value(RepairValues.DAYS_LEFT)
                lines += if (days <= 0) {
                    stringResource(R.string.simple_repair_sub_today)
                } else {
                    pluralStringResource(R.plurals.simple_repair_sub_soon, days, days)
                }
            }
            if (result.has(Finding.TRAFFIC_LOW)) lines += stringResource(R.string.simple_repair_sub_low)
            if (lines.isEmpty()) lines += stringResource(R.string.simple_repair_sub_ok)
        }

        RepairStep.SERVERS -> {
            val servers = result.servers
            lines += when {
                result.has(Finding.NO_SERVERS) || servers == null -> stringResource(R.string.simple_repair_servers_empty)
                result.has(Finding.NO_SERVER_ANSWERS) -> stringResource(R.string.simple_repair_servers_none, servers.total)
                result.has(Finding.CURRENT_SERVER_DOWN) ->
                    stringResource(R.string.simple_repair_servers_current_down, servers.answering, servers.total)

                else -> stringResource(R.string.simple_repair_servers_ok, servers.answering, servers.total)
            }
            if (servers != null && servers.whitelistTotal > 0) {
                lines += stringResource(R.string.simple_repair_servers_whitelist, servers.whitelistAnswering, servers.whitelistTotal)
            }
        }

        RepairStep.DNS_VPN -> lines += stringResource(
            if (result.has(Finding.VPN_DNS_NO_ANSWER)) R.string.simple_repair_dns_vpn_fail else R.string.simple_repair_dns_vpn_ok
        )

        RepairStep.SITES_VPN -> lines += if (result.has(Finding.VPN_SITES_DOWN)) {
            stringResource(R.string.simple_repair_sites_fail)
        } else {
            stringResource(R.string.simple_repair_sites_ok, value(RepairValues.LATENCY_MS))
        }

        RepairStep.RU_DIRECT -> lines += if (result.has(Finding.RU_SITES_DOWN)) {
            stringResource(R.string.simple_repair_ru_fail)
        } else {
            stringResource(R.string.simple_repair_ru_ok, value(RepairValues.REACHED), value(RepairValues.ASKED))
        }

        RepairStep.BACKGROUND -> lines += stringResource(
            when {
                result.has(Finding.BACKGROUND_RESTRICTED) -> R.string.simple_repair_background_restricted
                result.has(Finding.BATTERY_OPTIMIZED) -> R.string.simple_repair_background_optimized
                else -> R.string.simple_repair_background_ok
            }
        )
    }
    if (result.status == StepStatus.FIXED) lines += stringResource(result.fix.text)
    if (result.undoneFix != null) lines += stringResource(R.string.simple_repair_fix_undone)
    return lines
}

/** «7 минут», «3 часа», «2 дня»: the largest unit that fits twice. */
@Composable
private fun duration(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days >= 2 -> pluralStringResource(R.plurals.simple_repair_days, days.toInt(), days.toInt())
        hours >= 2 -> pluralStringResource(R.plurals.simple_repair_hours, hours.toInt(), hours.toInt())
        else -> pluralStringResource(R.plurals.simple_repair_minutes, minutes.toInt(), minutes.toInt())
    }
}

/** What an automatic fix did, after the check's own explanation and in the verdict. */
private val FixKind?.text: Int
    get() = when (this) {
        FixKind.SWITCHED_SERVER -> R.string.simple_repair_fix_server
        FixKind.DIRECT_DNS_WHITELISTED -> R.string.simple_repair_fix_direct_dns
        FixKind.VPN_DNS_ALTERNATIVE -> R.string.simple_repair_fix_vpn_dns
        FixKind.SUBSCRIPTION_UPDATED -> R.string.simple_repair_fix_subscription
        FixKind.ROUTES_UPDATED -> R.string.simple_repair_fix_routes
        FixKind.MTU_LOWERED -> R.string.simple_repair_fix_mtu
        null -> R.string.simple_repair_fixed
    }

private val SkipReason?.text: Int
    get() = when (this) {
        SkipReason.NO_INTERNET -> R.string.simple_repair_skip_no_internet
        SkipReason.NO_WORKING_SERVER -> R.string.simple_repair_skip_no_server
        SkipReason.OTHER_VPN -> R.string.simple_repair_skip_other_vpn
        SkipReason.VPN_OFF -> R.string.simple_repair_skip_vpn_off
        SkipReason.NO_VPN_PERMISSION -> R.string.simple_repair_skip_no_permission
        SkipReason.VPN_START_FAILED -> R.string.simple_repair_skip_vpn_start
        SkipReason.APP_BYPASSES_VPN -> R.string.simple_repair_skip_bypass
        SkipReason.NO_SUBSCRIPTION -> R.string.simple_repair_skip_no_subscription
        SkipReason.UNSUPPORTED_DNS -> R.string.simple_repair_skip_dns_kind
        SkipReason.UNSUPPORTED_ANDROID -> R.string.simple_repair_skip_android
        SkipReason.NO_TIME_SOURCE -> R.string.simple_repair_skip_no_time
        SkipReason.TESTS_BUSY -> R.string.simple_repair_skip_busy
        SkipReason.TIMED_OUT -> R.string.simple_repair_skip_timeout
        SkipReason.STOPPED -> R.string.simple_repair_skip_stopped
        SkipReason.ERROR, null -> R.string.simple_repair_skip_error
    }

/** An amber readable on the surface, as the subscription card's warnings. */
@Composable
private fun warningColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFFFC966) else Color(0xFF8A5A00)
