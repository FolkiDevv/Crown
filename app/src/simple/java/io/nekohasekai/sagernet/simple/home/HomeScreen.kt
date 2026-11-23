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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.add.AddActions
import io.nekohasekai.sagernet.simple.add.Welcome
import io.nekohasekai.sagernet.simple.modes.ModeCard
import io.nekohasekai.sagernet.simple.servers.CountryFromName
import io.nekohasekai.sagernet.simple.servers.CountryLabels
import kotlinx.coroutines.delay

/** What the home screen opens; the screens behind the stubs arrive in later batches. */
class HomeActions(
    /** The VPN consent and the start of the service. */
    val requestStart: () -> Unit,
    /** The «Серверы» section. */
    val openServers: () -> Unit,
    /** The ways to add a server, shown on the welcome screen while there is none (batch 4). */
    val add: AddActions,
    /** The traffic mode settings. */
    val openMode: () -> Unit,
)

/** The home section: the connect button with its status, the problem banner and the server and mode cards. */
@Composable
fun HomeScreen(
    state: HomeUiState,
    /** null until the mode in effect is read. */
    modeCard: ModeCard?,
    actions: HomeActions,
    onToggle: () -> Unit,
    onDismissProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var repairDialog by rememberSaveable { mutableStateOf(false) }

    val banner: @Composable (Modifier, Dp) -> Unit = { bannerModifier, gap ->
        state.problem?.let { problem ->
            ProblemBanner(problem, onRepair = { repairDialog = true }, onDismiss = onDismissProblem, modifier = bannerModifier)
            Spacer(Modifier.height(gap))
        }
    }
    val content = HomeStatus.content(state.hasServers, state.phase)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // A wide but low window (a phone in landscape): the button and its status beside the banner and the cards,
        // so whether the VPN is on is visible without scrolling.
        val sideBySide = content == HomeContent.CONTROLS && maxWidth > maxHeight && maxWidth >= 560.dp
        // A low window (a phone in landscape) tightens the gaps so the cards and the add button fit without scrolling.
        val low = maxHeight < 480.dp
        if (sideBySide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = if (low) 0.dp else 8.dp),
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
                    banner(cardWidth, if (low) 8.dp else 16.dp)
                    ServerCardView(state, modeCard?.whitelists == true, actions.openServers, cardWidth)
                    Spacer(Modifier.height(if (low) 8.dp else 12.dp))
                    ModeCardView(modeCard, actions.openMode, cardWidth)
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
                banner(cardWidth, 16.dp)
                when (content) {
                    HomeContent.LOADING -> Unit
                    HomeContent.NO_SERVERS -> Welcome(actions.add, low = low, modifier = cardWidth)
                    HomeContent.CONTROLS -> {
                        Spacer(Modifier.height(16.dp))
                        ConnectButton(state.phase, onToggle)
                        Spacer(Modifier.height(20.dp))
                        Status(state)
                        Spacer(Modifier.height(28.dp))
                        ServerCardView(state, modeCard?.whitelists == true, actions.openServers, cardWidth)
                        Spacer(Modifier.height(12.dp))
                        ModeCardView(modeCard, actions.openMode, cardWidth)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (repairDialog) {
        RepairStubDialog((state.problem as? HomeProblem.ConnectionLost)?.detail) { repairDialog = false }
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
private fun ServerCardView(state: HomeUiState, whitelists: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val server = state.server?.let { card ->
        card.localizedName?.let { card.copy(name = stringResource(it)) } ?: card
    }
    val locale = LocalConfiguration.current.locales[0]
    val country = server?.country?.let { CountryLabels.label(it, locale) }
    // The pick shown under a country headline without its own flag (the headline has the country's).
    val picked = server?.autoSelected?.let { CountryFromName.withoutLeadingFlag(it) }.orEmpty()
    val headline: String
    val supporting: String?
    when {
        server == null -> {
            headline = stringResource(R.string.simple_server_none)
            supporting = null
        }

        // A country chosen in «Серверы»: the country, and the server its selector runs.
        server.countryChoice != null -> {
            headline = CountryLabels.label(server.countryChoice, locale)
            supporting = picked.ifEmpty { stringResource(R.string.simple_server_country_best) }
        }

        // «Авто» picked a server: the country it is in (simple-ui "Карточки сервера и режима на главной").
        server.auto && server.autoSelected.isNotEmpty() -> {
            headline = country ?: server.autoSelected
            supporting = if (country != null) {
                stringResource(R.string.simple_server_auto_with, picked)
            } else {
                stringResource(R.string.simple_server_auto)
            }
        }

        server.auto -> {
            headline = server.name
            supporting = stringResource(
                if (state.phase == ConnectionPhase.PROTECTED) R.string.simple_server_auto_choosing
                else R.string.simple_server_auto
            )
        }

        else -> {
            headline = CountryFromName.spacedFlag(server.name)
            // A name that starts with its flag: the country line without a second one.
            supporting = server.country?.let {
                if (CountryFromName.startsWithFlag(server.name)) CountryLabels.name(it, locale) else country
            }
        }
    }
    val latency = server?.latencyMs?.let { stringResource(R.string.simple_latency_ms, it) }
    // With whitelists in effect: whether the server is one for whitelists, and how many of «Авто»'s servers work in
    // this network (traffic-modes "Применение результата в режиме «Авто»").
    val notes = buildList {
        if (server?.whitelistServer == true) add(stringResource(R.string.simple_server_whitelist))
        val working = server?.working
        if (whitelists && working != null) {
            add(stringResource(R.string.simple_server_working, working.alive, working.total))
        }
    }
    HomeCard(
        overline = stringResource(R.string.simple_card_server),
        headline = headline,
        supporting = supporting,
        note = notes.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        trailing = latency,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
private fun ModeCardView(card: ModeCard?, onClick: () -> Unit, modifier: Modifier) {
    val headline = when (card) {
        null -> ""
        ModeCard.AUTO_SMART -> stringResource(R.string.simple_mode_card_auto_smart)
        ModeCard.AUTO_WHITELIST -> stringResource(R.string.simple_mode_card_auto_whitelist)
        ModeCard.SMART -> stringResource(R.string.simple_mode_smart)
        ModeCard.WHITELIST -> stringResource(R.string.simple_mode_whitelist)
        ModeCard.APPS -> stringResource(R.string.simple_mode_apps)
        ModeCard.CUSTOM -> stringResource(R.string.simple_mode_card_custom)
    }
    val supporting = when (card) {
        ModeCard.AUTO_WHITELIST, ModeCard.WHITELIST -> stringResource(R.string.simple_mode_card_whitelist_hint)
        ModeCard.CUSTOM -> stringResource(R.string.simple_mode_card_custom_hint)
        else -> null
    }
    HomeCard(
        overline = stringResource(R.string.simple_card_mode),
        headline = headline,
        supporting = supporting,
        trailing = null,
        onClick = onClick,
        modifier = modifier,
        warning = card?.whitelists == true,
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
    /** Whitelists are in effect: the card stands out in a warning colour (simple-ui spec, mode card). */
    warning: Boolean = false,
    /** A short third line under [supporting]. */
    note: String? = null,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val container = when {
        !warning -> MaterialTheme.colorScheme.surface
        dark -> Color(0xFF4A3800)
        else -> Color(0xFFFFE6A6)
    }
    val content = when {
        !warning -> MaterialTheme.colorScheme.onSurface
        dark -> Color(0xFFFFE08F)
        else -> Color(0xFF3A2A00)
    }
    val secondary = if (warning) content else MaterialTheme.colorScheme.onSurfaceVariant
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(containerColor = container, contentColor = content),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (warning) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    overline,
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
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
                        color = secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                note?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                        maxLines = 2,
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
                tint = secondary,
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
