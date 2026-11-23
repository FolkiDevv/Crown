package io.nekohasekai.sagernet.simple

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lines of the details shown before «Показать полностью». */
private const val COLLAPSED_LINES = 4

/** The longest details shown at all: a core error can run to pages. */
private const val MAX_CHARS = 4000

/**
 * «Подробности: …» of a failed connection or subscription download: the service's message in the current language
 * ([ServiceMessages]), a few lines with an ellipsis and «Показать полностью» (a focusable button, so a remote reaches
 * the rest too) instead of a text cut in the middle of a word.
 */
@Composable
fun ServiceDetail(detail: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val text by produceState(detail, detail, locale) {
        value = withContext(Dispatchers.Default) { ServiceMessages.localize(context, detail) }
    }
    var expanded by rememberSaveable(detail) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            stringResource(R.string.simple_repair_details, ServiceMessages.shorten(text.trim(), MAX_CHARS)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            // Opened, it can take a remote's focus, which scrolls a long text into view from its start.
            modifier = if (expanded) Modifier.focusRing(FocusShapes.Card, outset = 0.dp).focusable() else Modifier,
        )
        if (overflows || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.focusRing(FocusShapes.Pill),
            ) {
                Text(stringResource(if (expanded) R.string.simple_details_less else R.string.simple_details_more))
            }
        }
    }
}
