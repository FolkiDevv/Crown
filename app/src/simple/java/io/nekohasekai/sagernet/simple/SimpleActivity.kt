package io.nekohasekai.sagernet.simple

import android.app.ActivityManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowWidthSizeClass
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs

/**
 * The simple interface (openspec simple-ui-redesign). Opened by [SimpleUi.redirect] from MainActivity, which stays
 * the entry point of the launcher, notification, tile and shortcuts. Batch 1: the adaptive three-section frame
 * (bottom bar on narrow windows, navigation rail on medium/wide ones) and the switch to the classic interface.
 */
class SimpleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SimpleTheme {
                SimpleApp(onOpenClassic = { SimpleUi.switchTo(this, simple = false) })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Same as MainActivity.onResume: MainActivity finishes before its onResume when it redirects here
        if (DataStore.hideFromRecentApps) excludeFromRecents()
    }

    private fun excludeFromRecents() {
        try {
            getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()?.setExcludeFromRecents(true)
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }
}

enum class SimpleSection(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.simple_nav_home, Icons.Filled.Home),
    SERVERS(R.string.simple_nav_servers, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.simple_nav_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun SimpleApp(onOpenClassic: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(SimpleSection.HOME) }
    // Rail whenever the window is not narrow, by width only: the library default keeps the bottom bar on any window
    // of compact height, i.e. on every phone in landscape (simple-ui spec: bar on narrow, side panel on medium/wide)
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = if (adaptiveInfo.windowPosture.isTabletop ||
        adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT
    ) NavigationSuiteType.NavigationBar else NavigationSuiteType.NavigationRail
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            SimpleSection.entries.forEach { item ->
                item(
                    selected = item == section,
                    onClick = { section = item },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                )
            }
        },
    ) {
        when (section) {
            SimpleSection.HOME -> PlaceholderSection(section, R.string.simple_home_placeholder)
            SimpleSection.SERVERS -> PlaceholderSection(section, R.string.simple_servers_placeholder)
            SimpleSection.SETTINGS -> SettingsSection(onOpenClassic)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionScaffold(section: SimpleSection, content: @Composable (Modifier) -> Unit) {
    // The navigation suite already keeps clear of the bottom/side system bars; the top bar takes the status bar.
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(section.label)) }) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
private fun PlaceholderSection(section: SimpleSection, @StringRes text: Int) {
    SectionScaffold(section) { modifier ->
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(text),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SettingsSection(onOpenClassic: () -> Unit) {
    SectionScaffold(SimpleSection.SETTINGS) { modifier ->
        LazyColumn(modifier.fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.simple_settings_classic)) },
                    supportingContent = { Text(stringResource(R.string.simple_settings_classic_summary)) },
                    modifier = Modifier.clickable(onClick = onOpenClassic),
                )
            }
        }
    }
}
