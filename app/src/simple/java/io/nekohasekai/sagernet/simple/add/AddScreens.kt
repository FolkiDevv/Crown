package io.nekohasekai.sagernet.simple.add

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.FocusShapes
import io.nekohasekai.sagernet.simple.ServiceDetail
import io.nekohasekai.sagernet.simple.focusRing
import io.nekohasekai.sagernet.simple.focusWhenShown
import io.nekohasekai.sagernet.simple.trafficSize
import java.text.DateFormat
import java.util.Date

/** A dialog button with the focus ring of a remote. */
private val DialogButton = Modifier.focusRing(FocusShapes.Pill)

/** The four ways to add a server (simple-ui "Универсальное добавление серверов"). */
class AddActions(
    val paste: () -> Unit,
    val scan: () -> Unit,
    val link: () -> Unit,
    val file: () -> Unit,
)

private class AddMethod(val icon: Int, val title: Int, val summary: Int, val action: (AddActions) -> Unit)

private val methods = listOf(
    AddMethod(R.drawable.simple_ic_paste, R.string.simple_add_paste, R.string.simple_add_paste_summary) { it.paste() },
    AddMethod(R.drawable.simple_ic_qr, R.string.simple_add_scan, R.string.simple_add_scan_summary) { it.scan() },
    AddMethod(R.drawable.simple_ic_link, R.string.simple_add_link, R.string.simple_add_link_summary) { it.link() },
    AddMethod(R.drawable.simple_ic_file, R.string.simple_add_file, R.string.simple_add_file_summary) { it.file() },
)

/**
 * The ways to add, one row each; [highlightFirst]: the clipboard row stands out (the welcome screen); [focusFirst]: a
 * remote (D-pad) starts on the first row.
 */
@Composable
fun AddMethods(
    actions: AddActions,
    modifier: Modifier = Modifier,
    highlightFirst: Boolean = false,
    focusFirst: Boolean = true,
) {
    Column(modifier) {
        methods.forEachIndexed { index, method ->
            val first = highlightFirst && index == 0
            Card(
                onClick = { method.action(actions) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .focusRing(FocusShapes.Card)
                    .then(if (index == 0) Modifier.focusWhenShown(enabled = focusFirst) else Modifier),
                colors = if (first) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            ) {
                ListItem(
                    leadingContent = { Icon(painterResource(method.icon), contentDescription = null) },
                    headlineContent = { Text(stringResource(method.title), fontWeight = FontWeight.Medium) },
                    supportingContent = { Text(stringResource(method.summary)) },
                    colors = ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        headlineColor = if (first) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        supportingColor = if (first) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        leadingIconColor = if (first) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    }
}

/** The first start without servers (simple-ui "Первый запуск без серверов"): the ways to add and where to get a link. */
@Composable
fun Welcome(actions: AddActions, low: Boolean, modifier: Modifier = Modifier, focus: Boolean = true) {
    Column(modifier.padding(top = if (low) 4.dp else 16.dp)) {
        Text(
            stringResource(R.string.simple_welcome_title),
            style = if (low) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.simple_welcome_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (low) 8.dp else 20.dp))
        AddMethods(actions, highlightFirst = true, focusFirst = focus)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    stringResource(R.string.simple_welcome_where_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.simple_welcome_where_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The add sheet, opened from the Servers section (and wherever servers exist already). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(actions: AddActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                stringResource(R.string.simple_add_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            AddMethods(actions)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A subscription or server link typed or pasted in. */
@Composable
fun LinkDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.simple_add_link_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.simple_add_link_text),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.simple_add_link_field)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank(), modifier = Modifier.focusRing(FocusShapes.Pill)) { Text(stringResource(R.string.simple_add_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = DialogButton) { Text(stringResource(R.string.simple_cancel)) } },
    )
}

/** What the add flow waits for; it cannot be dismissed (the import goes on in any case). */
@Composable
fun AddBusyDialog(busy: AddBusy) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Spacer(Modifier.width(20.dp))
                Text(
                    stringResource(
                        when (busy) {
                            AddBusy.READING -> R.string.simple_add_busy_reading
                            AddBusy.SUBSCRIPTION -> R.string.simple_add_busy_subscription
                            AddBusy.SERVERS -> R.string.simple_add_busy_servers
                        }
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        },
    )
}

/** The outcome of an add: the summary of a subscription or a plain explanation of what went wrong. */
@Composable
fun AddResultDialog(dialog: AddDialog, canConnect: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        is AddDialog.Summary -> SummaryDialog(dialog.summary, canConnect, onConnect, onDismiss)
        AddDialog.NotRecognized -> MessageDialog(R.string.simple_add_unknown_title, stringResource(R.string.simple_add_unknown_text), onDismiss)
        AddDialog.Failed -> MessageDialog(R.string.simple_add_failed_title, stringResource(R.string.simple_add_failed_text), onDismiss)
        is AddDialog.SubscriptionFailed -> MessageDialog(
            R.string.simple_add_subscription_failed_title,
            stringResource(R.string.simple_add_subscription_failed_text),
            onDismiss,
            detail = dialog.detail,
        )

        is AddDialog.FileUnreadable -> MessageDialog(
            R.string.simple_add_file_failed_title,
            stringResource(
                if (dialog.tooLarge) R.string.simple_add_file_too_large else R.string.simple_add_file_unreadable,
                dialog.name,
            ),
            onDismiss,
        )
    }
}

@Composable
private fun MessageDialog(title: Int, text: String, onDismiss: () -> Unit, detail: String? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    ServiceDetail(detail)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = DialogButton.focusWhenShown()) { Text(stringResource(R.string.simple_ok)) }
        },
    )
}

@Composable
private fun SummaryDialog(summary: ImportSummary, canConnect: Boolean, onConnect: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Check, contentDescription = null) },
        title = { Text(stringResource(R.string.simple_summary_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(summary.name, style = MaterialTheme.typography.titleMedium)
                val servers = pluralStringResource(R.plurals.simple_summary_servers, summary.servers, summary.servers)
                Text(
                    if (summary.countries > 0) {
                        stringResource(
                            R.string.simple_summary_servers_countries,
                            servers,
                            pluralStringResource(R.plurals.simple_summary_countries, summary.countries, summary.countries),
                        )
                    } else {
                        servers
                    }
                )
                when {
                    summary.remaining != null -> Text(
                        stringResource(R.string.simple_summary_traffic_left, trafficSize(context, summary.remaining))
                    )

                    summary.unlimited -> Text(stringResource(R.string.simple_summary_traffic_unlimited))
                }
                summary.expire?.let { expire ->
                    val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(expire * 1000))
                    val expired = expire * 1000 < System.currentTimeMillis()
                    Text(
                        stringResource(if (expired) R.string.simple_summary_expired else R.string.simple_summary_expires, date),
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (summary.enabled.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.simple_summary_enabled), style = MaterialTheme.typography.titleSmall)
                    summary.enabled.forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (item) {
                                    Enabled.AUTO_SERVER -> stringResource(R.string.simple_summary_enabled_auto_server)
                                    Enabled.AUTO_UPDATE -> if (summary.updateHours == 24) {
                                        stringResource(R.string.simple_summary_enabled_daily)
                                    } else {
                                        pluralStringResource(
                                            R.plurals.simple_summary_enabled_every_hours,
                                            summary.updateHours,
                                            summary.updateHours,
                                        )
                                    }

                                    Enabled.AUTO_MODE -> stringResource(R.string.simple_summary_enabled_auto_mode)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (canConnect) {
                TextButton(onClick = onConnect, modifier = DialogButton.focusWhenShown()) { Text(stringResource(R.string.simple_action_connect)) }
            } else {
                TextButton(onClick = onDismiss, modifier = DialogButton.focusWhenShown()) { Text(stringResource(R.string.simple_ok)) }
            }
        },
        dismissButton = if (canConnect) {
            { TextButton(onClick = onDismiss, modifier = DialogButton) { Text(stringResource(R.string.simple_summary_done)) } }
        } else {
            null
        },
    )
}

/** Something new in the clipboard (simple-ui "Предложение импорта из буфера обмена"): added only on «Добавить». */
@Composable
fun ClipboardOfferDialog(offer: ClipboardOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(painterResource(R.drawable.simple_ic_paste), contentDescription = null) },
        title = { Text(stringResource(R.string.simple_offer_title)) },
        text = {
            Text(
                if (offer.subscriptionHost != null) {
                    stringResource(R.string.simple_offer_subscription, offer.subscriptionHost)
                } else {
                    pluralStringResource(R.plurals.simple_offer_servers, offer.servers, offer.servers)
                }
            )
        },
        confirmButton = { TextButton(onClick = onAccept, modifier = DialogButton.focusWhenShown()) { Text(stringResource(R.string.simple_add_action)) } },
        dismissButton = { TextButton(onClick = onDecline, modifier = DialogButton) { Text(stringResource(R.string.simple_offer_later)) } },
    )
}
