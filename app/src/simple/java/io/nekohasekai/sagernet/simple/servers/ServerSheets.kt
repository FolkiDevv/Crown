package io.nekohasekai.sagernet.simple.servers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.FocusShapes
import io.nekohasekai.sagernet.simple.focusRing
import io.nekohasekai.sagernet.simple.leaveWithDpad
import java.nio.charset.StandardCharsets

/** What the menus of «Серверы» can ask for (simple-ui "Управление подпиской", "Управление своими серверами"). */
class ManageActions(
    val update: (Long) -> Unit,
    /** A page of the subscription's provider (its account, support). */
    val openProvider: (Long, String) -> Unit,
    val renameSubscription: (Long, String) -> Unit,
    val shareSubscription: (SubscriptionCard) -> Unit,
    val deleteSubscription: (Long) -> Unit,
    /** The classic settings of the subscription (advanced mode). */
    val subscriptionSettings: (Long) -> Unit,
    val connect: (ServerEntry) -> Unit,
    val testServer: (Long) -> Unit,
    val renameServer: (Long, String) -> Unit,
    val shareServer: (Long) -> Unit,
    val deleteServer: (Long) -> Unit,
    /** The classic editor of the server (advanced mode). */
    val editServer: (Long) -> Unit,
    val closeShare: () -> Unit,
)

/** The menu of a subscription's ⋮: its actions, the classic settings last and only in the advanced mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionSheet(
    card: SubscriptionCard,
    advanced: Boolean,
    actions: ManageActions,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val facts = card.facts
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetColumn {
            SheetHeader(
                card.name,
                pluralStringResource(R.plurals.simple_servers_count, card.servers, card.servers),
            )
            SheetItem(rememberVectorPainter(Icons.Filled.Refresh), stringResource(R.string.simple_sub_action_update)) {
                onDismiss()
                actions.update(card.groupId)
            }
            facts?.webUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                SheetItem(painterResource(R.drawable.simple_ic_link), stringResource(R.string.simple_notice_account)) {
                    onDismiss()
                    actions.openProvider(card.groupId, url)
                }
            }
            facts?.supportUrl?.takeIf { it.isNotEmpty() }?.let { url ->
                SheetItem(painterResource(R.drawable.simple_ic_link), stringResource(R.string.simple_notice_support)) {
                    onDismiss()
                    actions.openProvider(card.groupId, url)
                }
            }
            SheetItem(rememberVectorPainter(Icons.Filled.Edit), stringResource(R.string.simple_action_rename), onClick = onRename)
            if (card.url.isNotBlank()) {
                SheetItem(
                    rememberVectorPainter(Icons.Filled.Share),
                    stringResource(R.string.simple_sub_action_share),
                    stringResource(R.string.simple_sub_action_share_summary),
                ) {
                    onDismiss()
                    actions.shareSubscription(card)
                }
            }
            SheetItem(
                rememberVectorPainter(Icons.Filled.Delete),
                stringResource(R.string.simple_sub_action_delete),
                danger = true,
                onClick = onDelete,
            )
            if (advanced) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SheetItem(
                    rememberVectorPainter(Icons.Filled.Settings),
                    stringResource(R.string.simple_sub_action_all),
                    stringResource(R.string.simple_sub_action_all_summary),
                    badge = true,
                ) {
                    onDismiss()
                    actions.subscriptionSettings(card.groupId)
                }
            }
        }
    }
}

/** The menu of an own server's ⋮ (servers of a subscription have none: their provider changes them). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    server: ServerEntry,
    supporting: String?,
    advanced: Boolean,
    actions: ManageActions,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetColumn {
            SheetHeader(CountryFromName.withoutLeadingFlag(server.name), supporting)
            SheetItem(rememberVectorPainter(Icons.Filled.PlayArrow), stringResource(R.string.simple_server_action_connect)) {
                onDismiss()
                actions.connect(server)
            }
            SheetItem(painterResource(R.drawable.simple_ic_speed), stringResource(R.string.simple_server_action_test)) {
                onDismiss()
                actions.testServer(server.id)
            }
            SheetItem(rememberVectorPainter(Icons.Filled.Edit), stringResource(R.string.simple_action_rename), onClick = onRename)
            SheetItem(
                rememberVectorPainter(Icons.Filled.Share),
                stringResource(R.string.simple_server_action_share),
                stringResource(R.string.simple_server_action_share_summary),
            ) {
                onDismiss()
                actions.shareServer(server.id)
            }
            SheetItem(rememberVectorPainter(Icons.Filled.Delete), stringResource(R.string.simple_action_delete), danger = true) {
                onDismiss()
                actions.deleteServer(server.id)
            }
            if (advanced) {
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                SheetItem(
                    rememberVectorPainter(Icons.Filled.Settings),
                    stringResource(R.string.simple_server_action_edit),
                    badge = true,
                ) {
                    onDismiss()
                    actions.editServer(server.id)
                }
            }
        }
    }
}

@Composable
private fun SheetColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) { content() }
}

@Composable
private fun SheetHeader(title: String, supporting: String?) {
    Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        supporting?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetItem(
    icon: Painter,
    title: String,
    summary: String? = null,
    danger: Boolean = false,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            summary?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (badge) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small) {
                Text(
                    stringResource(R.string.simple_advanced_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** «Переименовать»: the current name to edit; «Сохранить» with an empty one does nothing. */
@Composable
fun RenameDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val save = { if (text.isNotBlank()) onSave(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.simple_action_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.simple_rename_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .leaveWithDpad(),
            )
        },
        confirmButton = {
            TextButton(onClick = save, enabled = text.isNotBlank(), modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_dialog_cancel))
            }
        },
    )
}

/** «Удалить подписку»: what goes, and that the same link brings it back. */
@Composable
fun DeleteSubscriptionDialog(card: SubscriptionCard, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.simple_sub_delete_title, card.name)) },
        text = { Text(pluralStringResource(R.plurals.simple_sub_delete_text, card.servers, card.servers)) },
        confirmButton = {
            TextButton(onClick = onDelete, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_dialog_cancel))
            }
        },
    )
}

/** «Поделиться»: the link as a QR code (when it fits in one), to copy or to send, with whom it lets in. */
@Composable
fun ShareDialog(request: ShareRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val qr = remember(request.link) { qrBitmap(request.link, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(request.title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (qr != null) {
                    Box(
                        Modifier
                            .widthIn(max = 280.dp)
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(12.dp),
                    ) {
                        Image(
                            qr.asImageBitmap(),
                            contentDescription = request.name,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(stringResource(R.string.simple_share_too_long), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                Text(request.name, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(request.warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { send(context, request.link) }, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                    Text(stringResource(R.string.simple_share_send))
                }
                TextButton(
                    onClick = {
                        copy(context, request.link)
                        onDismiss()
                    },
                    modifier = Modifier.focusRing(FocusShapes.Pill),
                ) { Text(stringResource(R.string.simple_share_copy)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.focusRing(FocusShapes.Pill)) {
                Text(stringResource(R.string.simple_action_close))
            }
        },
    )
}

private fun copy(context: Context, link: String) {
    try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", link))
        Toast.makeText(context, R.string.simple_share_copied, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Logs.w(e)
    }
}

private fun send(context: Context, link: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Logs.w(e)
    }
}

/** [text] as a black-on-white QR code of [size] pixels, null when it does not fit in one. */
private fun qrBitmap(text: String, size: Int): Bitmap? = try {
    val hints: Map<EncodeHintType, Any> = if (StandardCharsets.ISO_8859_1.newEncoder().canEncode(text)) emptyMap()
    else mapOf(EncodeHintType.CHARACTER_SET to StandardCharsets.UTF_8.name())
    val bits = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints + (EncodeHintType.MARGIN to 0))
    val pixels = IntArray(size * size) { i -> if (bits.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply { setPixels(pixels, 0, size, 0, 0, size, size) }
} catch (e: Exception) {
    Logs.w(e)
    null
}
