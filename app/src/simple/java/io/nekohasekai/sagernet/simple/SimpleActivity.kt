package io.nekohasekai.sagernet.simple

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.home.HomeActions
import io.nekohasekai.sagernet.simple.home.HomeScreen
import io.nekohasekai.sagernet.simple.home.HomeViewModel
import io.nekohasekai.sagernet.simple.modes.AppList
import io.nekohasekai.sagernet.simple.modes.ModesViewModel
import io.nekohasekai.sagernet.simple.settings.SettingsActions
import io.nekohasekai.sagernet.simple.settings.SettingsScreen
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.VpnRequestActivity

/**
 * The simple interface (openspec simple-ui-redesign). Opened by [SimpleUi.redirect] from MainActivity, which stays
 * the entry point of the launcher, notification, tile and shortcuts. Batch 1: the adaptive three-section frame
 * (bottom bar on narrow windows, navigation rail on medium/wide ones) and the switch to the classic interface.
 * Batch 2: the home section (connect button with its status, server and mode cards, problem banner).
 */
class SimpleActivity : AppCompatActivity() {

    private val home: HomeViewModel by viewModels()
    private val modes: ModesViewModel by viewModels()

    /** The classic app list (AppManagerActivity) editing one of the simple UI's two app lists. */
    private val appList = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        modes.appListClosed()
    }

    /** The VPN consent, then the start of the service; true in the result when the consent was refused. */
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { refused ->
        if (refused) home.message(R.string.simple_vpn_permission_denied)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SimpleTheme {
                SimpleApp(
                    home = home,
                    modes = modes,
                    openAppList = ::openAppList,
                    requestStart = ::requestStart,
                    openClassicOnce = ::openClassicOnce,
                    onOpenClassic = { SimpleUi.switchTo(this, simple = false) },
                )
            }
        }
        if (savedInstanceState == null) askNotificationsOnce()
    }

    override fun onStart() {
        super.onStart()
        home.setForeground(true)
        modes.refresh()
    }

    override fun onStop() {
        home.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Same as MainActivity.onResume: MainActivity finishes before its onResume when it redirects here
        if (DataStore.hideFromRecentApps) excludeFromRecents()
    }

    private fun requestStart() {
        try {
            connect.launch(null)
        } catch (_: ActivityNotFoundException) {
            VpnRequestActivity.showConsentUnavailable(this)
        } catch (_: SecurityException) {
            VpnRequestActivity.showConsentUnavailable(this)
        }
    }

    /** The classic app list over the per-app keys ModesViewModel.edit has just set to [list]. */
    private fun openAppList(@Suppress("UNUSED_PARAMETER") list: AppList) {
        try {
            appList.launch(Intent(this, AppManagerActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
            modes.appListClosed()
        }
    }

    /** The classic interface on top of this one for a visit; the choice of interface stays. */
    private fun openClassicOnce() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true))
    }

    /**
     * The notification permission (13+) once per install, as the classic first start asks it (FirstStartPrompts):
     * without it Android hides the notification of the running VPN. Skipped when the classic UI asked already.
     */
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || SagerNet.isTv) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = getSharedPreferences(SimpleUi.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
        val classicAsked = getSharedPreferences(CLASSIC_FIRST_START_PREFS, Context.MODE_PRIVATE)
            .getBoolean(CLASSIC_FIRST_START_SHOWN, false)
        if (classicAsked) return
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun excludeFromRecents() {
        try {
            getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()?.setExcludeFromRecents(true)
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    private companion object {
        const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"

        /** FirstStartPrompts' own flag: the classic first start has asked for the permission. */
        const val CLASSIC_FIRST_START_PREFS = "first_start"
        const val CLASSIC_FIRST_START_SHOWN = "prompts_shown"
    }
}

enum class SimpleSection(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.simple_nav_home, Icons.Filled.Home),
    SERVERS(R.string.simple_nav_servers, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.simple_nav_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun SimpleApp(
    home: HomeViewModel,
    modes: ModesViewModel,
    openAppList: (AppList) -> Unit,
    requestStart: () -> Unit,
    openClassicOnce: () -> Unit,
    onOpenClassic: () -> Unit,
) {
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
        // The bottom bar keeps the content clear of the navigation bar; the rail does not, so in rail mode the
        // content stays clear of the gesture handle (bottom) and of 3-button navigation on the end side itself.
        val contentInsets = if (layoutType == NavigationSuiteType.NavigationRail) {
            Modifier.windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
        } else {
            Modifier
        }
        Box(contentInsets) {
            SectionContent(section, home, modes, openAppList, requestStart, openClassicOnce, onOpenClassic) { section = it }
        }
    }
}

@Composable
private fun SectionContent(
    section: SimpleSection,
    home: HomeViewModel,
    modes: ModesViewModel,
    openAppList: (AppList) -> Unit,
    requestStart: () -> Unit,
    openClassicOnce: () -> Unit,
    onOpenClassic: () -> Unit,
    select: (SimpleSection) -> Unit,
) {
    when (section) {
        SimpleSection.HOME -> HomeSection(
            home,
            modes,
            HomeActions(
                requestStart = requestStart,
                openServers = { select(SimpleSection.SERVERS) },
                openClassicOnce = openClassicOnce,
                openMode = { select(SimpleSection.SETTINGS) },
            ),
        )

        SimpleSection.SERVERS -> PlaceholderSection(section, R.string.simple_servers_placeholder)
        SimpleSection.SETTINGS -> SettingsSection(modes, openAppList, onOpenClassic)
    }
}

@Composable
private fun HomeSection(home: HomeViewModel, modes: ModesViewModel, actions: HomeActions) {
    val state by home.state.collectAsStateWithLifecycle()
    val modesState by modes.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(home) {
        home.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    // A mode applied while the service starts or reloads: read what is in effect once the state settles.
    LaunchedEffect(state.phase) { modes.refresh() }
    // A mode applied (also from Settings while the VPN is off) may have switched the managed auto-selector.
    LaunchedEffect(modesState.applied) { if (modesState.applied > 0) home.refreshServer() }
    SectionScaffold(SimpleSection.HOME, snackbar) { modifier ->
        HomeScreen(
            state = state,
            modeCard = modesState.card,
            actions = actions,
            onToggle = { home.toggle(actions.requestStart) },
            onDismissProblem = home::dismissProblem,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionScaffold(
    section: SimpleSection,
    snackbar: SnackbarHostState? = null,
    content: @Composable (Modifier) -> Unit,
) {
    // The navigation suite already keeps clear of the bottom/side system bars; the top bar takes the status bar.
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(section.label)) }) },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
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
private fun SettingsSection(modes: ModesViewModel, openAppList: (AppList) -> Unit, onOpenClassic: () -> Unit) {
    val state by modes.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(modes) {
        modes.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    SectionScaffold(SimpleSection.SETTINGS, snackbar) { modifier ->
        SettingsScreen(
            state = state,
            actions = SettingsActions(
                selectMode = { modes.select(it, openAppList) },
                editApps = { modes.edit(it, openAppList) },
                dismissFallback = modes::dismissFallback,
                openClassic = onOpenClassic,
            ),
            modifier = modifier,
        )
    }
}
