package io.nekohasekai.sagernet.simple.home

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.delay

/** What the home screen opens; the screens behind the stubs arrive in later batches. */
class HomeActions(
    /** The VPN consent and the start of the service. */
    val requestStart: () -> Unit,
    /** The «Серверы» section. */
    val openServers: () -> Unit,
    /** The classic interface for one visit, without changing the choice (adding servers until batch 4). */
    val openClassicOnce: () -> Unit,
)

/** The home section: the connect button with its status, the problem banner and the server and mode cards. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    onToggle: () -> Unit,
    onDismissProblem: () -> Unit,
    onModeStub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var repairDialog by rememberSaveable { mutableStateOf(false) }
    var addDialog by rememberSaveable { mutableStateOf(false) }

    val banner: @Composable (Modifier) -> Unit = { bannerModifier ->
        state.problem?.let { problem ->
            ProblemBanner(problem, onRepair = { repairDialog = true }, onDismiss = onDismissProblem, modifier = bannerModifier)
            Spacer(Modifier.height(16.dp))
        }
    }
    val content = HomeStatus.content(state.hasServers, state.phase)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A wide but low window (a phone in landscape): the button and its status beside the banner and the cards,
        // so whether the VPN is on is visible without scrolling.
        val sideBySide = content == HomeContent.CONTROLS && maxWidth > maxHeight && maxWidth >= 560.dp
        if (sideBySide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ConnectButton(state.phase, onToggle)
                    Spacer(Modifier.height(16.dp))
                    Status(state)
                }
                Spacer(Modifier.width(16.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val cardWidth = Modifier.fillMaxWidth()
                    banner(cardWidth)
                    ServerCardView(state, actions.openServers, cardWidth)
                    Spacer(Modifier.height(12.dp))
                    ModeCard(onModeStub, cardWidth)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val cardWidth = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                banner(cardWidth)
                when (content) {
                    HomeContent.LOADING -> Unit
                    HomeContent.NO_SERVERS -> NoServers(onAdd = { addDialog = true }, modifier = cardWidth)
                    HomeContent.CONTROLS -> {
                        Spacer(Modifier.height(16.dp))
                        ConnectButton(state.phase, onToggle)
                        Spacer(Modifier.height(20.dp))
                        Status(state)
                        Spacer(Modifier.height(28.dp))
                        ServerCardView(state, actions.openServers, cardWidth)
                        Spacer(Modifier.height(12.dp))
                        ModeCard(onModeStub, cardWidth)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (repairDialog) {
        RepairStubDialog((state.problem as? HomeProblem.ConnectionLost)?.detail) { repairDialog = false }
    }
    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text(stringResource(R.string.simple_add_server_interim_title)) },
            text = { Text(stringResource(R.string.simple_add_server_interim_text)) },
            confirmButton = {
                TextButton(onClick = {
                    addDialog = false
                    actions.openClassicOnce()
                }) { Text(stringResource(R.string.simple_open_classic)) }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false }) { Text(stringResource(R.string.simple_cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectButton(phase: ConnectionPhase, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when (phase) {
            ConnectionPhase.PROTECTED -> colors.primary
            ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> colors.primaryContainer
            ConnectionPhase.OFF, ConnectionPhase.DISCONNECTING -> colors.surfaceContainerHighest
        },
        label = "connect-container",
    )
    val iconColor = when (phase) {
        ConnectionPhase.PROTECTED -> colors.onPrimary
        ConnectionPhase.CONNECTING, ConnectionPhase.RECONNECTING -> colors.onPrimaryContainer
        ConnectionPhase.OFF, ConnectionPhase.DISCONNECTING -> colors.onSurfaceVariant
    }
    val action = stringResource(
        when (phase) {
            ConnectionPhase.OFF, ConnectionPhase.DISCONNECTING -> R.string.simple_action_connect
            ConnectionPhase.CONNECTING -> R.string.simple_action_cancel
            ConnectionPhase.PROTECTED, ConnectionPhase.RECONNECTING -> R.string.simple_action_disconnect
        }
    )
    val status = stringResource(phase.label())
    val busy = phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.RECONNECTING ||
        phase == ConnectionPhase.DISCONNECTING
    Box(Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 4.dp)
        }
        Surface(
            onClick = onToggle,
            enabled = phase != ConnectionPhase.DISCONNECTING,
            shape = CircleShape,
            color = container,
            border = if (phase == ConnectionPhase.OFF) BorderStroke(2.dp, colors.outlineVariant) else null,
            shadowElevation = if (phase == ConnectionPhase.PROTECTED) 6.dp else 0.dp,
            modifier = Modifier
                .size(160.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = action
                    stateDescription = status
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(R.drawable.simple_ic_power),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

private fun ConnectionPhase.label(): Int = when (this) {
    ConnectionPhase.OFF -> R.string.simple_status_off
    ConnectionPhase.CONNECTING -> R.string.simple_status_connecting
    ConnectionPhase.PROTECTED -> R.string.simple_status_protected
    ConnectionPhase.RECONNECTING -> R.string.simple_status_reconnecting
    ConnectionPhase.DISCONNECTING -> R.string.simple_status_disconnecting
}

@Composable
private fun Status(state: HomeUiState) {
    val colors = MaterialTheme.colorScheme
    Text(
        stringResource(state.phase.label()),
        style = MaterialTheme.typography.headlineSmall,
        color = if (state.phase == ConnectionPhase.PROTECTED) colors.primary else colors.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    val secondary = when (state.phase) {
        ConnectionPhase.OFF -> stringResource(R.string.simple_hint_connect)
        ConnectionPhase.CONNECTING -> stringResource(R.string.simple_hint_cancel)
        ConnectionPhase.PROTECTED -> stringResource(
            R.string.simple_connected_for, HomeStatus.formatDuration(elapsedSince(state.connectedSince))
        )

        ConnectionPhase.RECONNECTING -> stringResource(R.string.simple_hint_reconnecting)
        ConnectionPhase.DISCONNECTING -> ""
    }
    Text(
        secondary,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (state.phase == ConnectionPhase.PROTECTED) {
        val context = LocalContext.current
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(
                R.string.simple_speed,
                Formatter.formatShortFileSize(context, state.rxRate),
                Formatter.formatShortFileSize(context, state.txRate),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Milliseconds since [since] (elapsedRealtime), ticking every second while shown. */
@Composable
private fun elapsedSince(since: Long): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (since > 0L) {
            now = SystemClock.elapsedRealtime()
            delay(1000L - (now - since).mod(1000L))
        }
    }
    return if (since > 0L) now - since else 0L
}

@Composable
private fun ServerCardView(state: HomeUiState, onClick: () -> Unit, modifier: Modifier) {
    val server = state.server
    val headline: String
    val supporting: String?
    when {
        server == null -> {
            headline = stringResource(R.string.simple_server_none)
            supporting = null
        }

        server.auto && server.autoSelected.isNotEmpty() -> {
            headline = server.autoSelected
            supporting = stringResource(R.string.simple_server_auto)
        }

        server.auto -> {
            headline = server.name
            supporting = stringResource(
                if (state.phase == ConnectionPhase.PROTECTED) R.string.simple_server_auto_choosing
                else R.string.simple_server_auto
            )
        }

        else -> {
            headline = server.name
            supporting = null
        }
    }
    val latency = server?.latencyMs?.let { stringResource(R.string.simple_latency_ms, it) }
    HomeCard(
        overline = stringResource(R.string.simple_card_server),
        headline = headline,
        supporting = supporting,
        trailing = latency,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ModeCard(onClick: () -> Unit, modifier: Modifier) {
    // Batch 3 brings the traffic modes; until then the card shows the default the simple UI is built around.
    HomeCard(
        overline = stringResource(R.string.simple_card_mode),
        headline = stringResource(R.string.simple_mode_smart),
        supporting = null,
        trailing = null,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun HomeCard(
    overline: String,
    headline: String,
    supporting: String?,
    trailing: String?,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    headline,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
private fun ProblemBanner(problem: HomeProblem, onRepair: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    val (title, text) = when (problem) {
        is HomeProblem.ConnectionLost -> R.string.simple_problem_lost_title to R.string.simple_problem_lost_text
        HomeProblem.ServerNotResponding -> R.string.simple_problem_server_title to R.string.simple_problem_server_text
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.padding(top = 2.dp))
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.simple_problem_dismiss))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(onClick = onRepair) { Text(stringResource(R.string.simple_problem_repair)) }
        }
    }
}

@Composable
private fun NoServers(onAdd: () -> Unit, modifier: Modifier) {
    Column(
        modifier.padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Icon(
                painterResource(R.drawable.simple_ic_power),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(28.dp)
                    .size(48.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.simple_no_servers_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.simple_no_servers_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAdd) { Text(stringResource(R.string.simple_add_server)) }
    }
}

/** «Починка» arrives in batch 7; the banner and the settings already lead here. */
@Composable
fun RepairStubDialog(detail: String?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.simple_repair_title)) },
        text = {
            Column {
                Text(stringResource(R.string.simple_repair_coming))
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.simple_repair_details, detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.simple_ok)) } },
    )
}
