package io.nekohasekai.sagernet.simple

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowWidthSizeClass
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.add.AddActions
import io.nekohasekai.sagernet.simple.add.AddBusyDialog
import io.nekohasekai.sagernet.simple.add.AddEvent
import io.nekohasekai.sagernet.simple.add.AddResultDialog
import io.nekohasekai.sagernet.simple.add.AddSheet
import io.nekohasekai.sagernet.simple.add.AddViewModel
import io.nekohasekai.sagernet.simple.add.ClipboardOfferDialog
import io.nekohasekai.sagernet.simple.add.LinkDialog
import io.nekohasekai.sagernet.simple.add.Notice
import io.nekohasekai.sagernet.simple.home.ConnectionPhase
import io.nekohasekai.sagernet.simple.home.HomeActions
import io.nekohasekai.sagernet.simple.home.HomeScreen
import io.nekohasekai.sagernet.simple.home.HomeViewModel
import io.nekohasekai.sagernet.simple.modes.AppList
import io.nekohasekai.sagernet.simple.modes.ModesViewModel
import io.nekohasekai.sagernet.simple.servers.ServersActions
import io.nekohasekai.sagernet.simple.servers.ServersEvent
import io.nekohasekai.sagernet.simple.servers.ServersScreen
import io.nekohasekai.sagernet.simple.servers.ServersViewModel
import io.nekohasekai.sagernet.simple.settings.SettingsActions
import io.nekohasekai.sagernet.simple.settings.ClassicHintCard
import io.nekohasekai.sagernet.simple.settings.ClassicScreen
import io.nekohasekai.sagernet.simple.settings.ClassicVisit
import io.nekohasekai.sagernet.simple.settings.SettingsScreen
import io.nekohasekai.sagernet.simple.settings.SettingsViewModel
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The simple interface (openspec simple-ui-redesign). Opened by [SimpleUi.redirect] from MainActivity, which stays
 * the entry point of the launcher, notification, tile and shortcuts. Batch 1: the adaptive three-section frame
 * (bottom bar on narrow windows, navigation rail on medium/wide ones) and the switch to the classic interface.
 * Batch 2: the home section (connect button with its status, server and mode cards, problem banner).
 * Batch 4: adding servers (clipboard, QR, link, file; links, files and shares opened from other apps) and the
 * clipboard offer when the interface opens.
 * Batch 10: the whole settings section with the advanced mode (classic screens through [ClassicVisit]) and the
 * once-only hint after an upgrade on the way back to the classic interface.
 */
class SimpleActivity : AppCompatActivity() {

    private val home: HomeViewModel by viewModels()
    private val modes: ModesViewModel by viewModels()
    private val add: AddViewModel by viewModels()
    private val servers: ServersViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()

    /** The classic app list (AppManagerActivity) editing one of the simple UI's two app lists. */
    private val appList = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        modes.appListClosed()
    }

    /** The VPN consent, then the start of the service; true in the result when the consent was refused. */
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { refused ->
        if (refused) home.message(R.string.simple_vpn_permission_denied)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** «Открыть файл»: any file; a backup goes to the stock restore, the rest to the import. */
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::openFile)
    }

    /** The clipboard is looked at once each time the interface comes to the front, when its window has the focus. */
    private var clipboardCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Subscription defaults of the simple UI (HWID, the provider's interval), once per install.
        lifecycleScope.launch(Dispatchers.IO) { SimpleDefaults.apply(applicationContext) }
        setContent {
            SimpleTheme {
                SimpleApp(
                    home = home,
                    modes = modes,
                    add = add,
                    servers = servers,
                    settings = settings,
                    launchers = AddLaunchers(::pasteClipboard, ::openScanner, ::chooseFile),
                    openAppList = ::openAppList,
                    requestStart = ::requestStart,
                    onOpenClassic = { SimpleUi.switchTo(this, simple = false) },
                    openClassicScreen = ::openClassicScreen,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                add.events.collect { event ->
                    when (event) {
                        AddEvent.Added -> {
                            // The new subscription group was created in :bg: the «Авто» selectors take it in here.
                            modes.syncGroups()
                            home.refreshServer()
                            modes.refresh()
                            // The new servers get their delay (and country by exit IP) without waiting for the next round.
                            servers.reload(thenTest = true)
                        }

                        is AddEvent.OpenClassic -> openClassicImport(event.text)
                        is AddEvent.OpenBackup -> openBackup(event.uri)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                servers.events.collect { event ->
                    when (event) {
                        ServersEvent.Chosen -> home.refreshServer()
                        // An update in :bg may have renamed or replaced the servers of the chosen country.
                        ServersEvent.Updated -> {
                            modes.syncGroups()
                            home.refreshServer()
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            askNotificationsOnce()
            handleImportIntent(intent)
        }
    }

    /** A link, file, share or QR scan for the running interface (singleTask), handed over by MainActivity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        home.setForeground(true)
        modes.refresh()
        // Groups added meanwhile in :bg (a subscription added in the classic interface) join the «Авто» selectors.
        modes.syncGroups()
        servers.reload()
        // Auto connect, updates and the theme may have been changed in the classic interface meanwhile.
        settings.refresh()
        clipboardCheck = true
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

    /** Android lets only the focused app read the clipboard (10+). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !clipboardCheck) return
        clipboardCheck = false
        add.checkClipboard(clipboardText(onlyNew = true))
    }

    // ------------------------------------------------------------------------------------------------ adding

    /**
     * VIEW links and files, SEND texts and streams, and the QR scanner's texts, as MainActivity took them before
     * batch 4 — now without the classic choice dialogs.
     */
    private fun handleImportIntent(intent: Intent?) {
        intent ?: return
        intent.getStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS)?.let {
            add.importTexts(it)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val scheme = uri.scheme?.lowercase()
                if (scheme == "content" || scheme == "file") openFile(uri) else add.importText(uri.toString())
            }

            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    openFile(stream)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { add.importText(it) }
                }
            }
        }
    }

    /** A file opened here or from another app: AddViewModel tells a backup from a configuration (design.md D4). */
    private fun openFile(uri: Uri) = add.openFile(uri)

    /**
     * A backup: MainActivity takes it as a VIEW of the file, like one opened from a file manager, and BackupEntry.open
     * there brings up the stock restore (Tools › Backup). The classic flag on this very intent keeps the redirect from
     * sending it back here.
     */
    private fun openBackup(uri: Uri) {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
            )
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
        }
    }

    private fun pasteClipboard() {
        add.importText(clipboardText(onlyNew = false), fromClipboard = true)
    }

    private fun openScanner() {
        try {
            // It hands the codes to MainActivity (EXTRA_IMPORT_TEXTS), which hands them on here.
            startActivity(Intent(this, ScannerActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
        }
    }

    private fun chooseFile() {
        try {
            pickFile.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
            home.message(R.string.simple_add_no_file_picker)
        }
    }

    /** A routing profile link: the classic interface imports it, for this visit (the choice of interface stays). */
    private fun openClassicImport(text: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
                .putStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS, arrayListOf(text))
        )
    }

    /**
     * The clipboard text; [onlyNew]: null when this clip was looked at already in this process (no second read, so
     * no second "pasted from your clipboard" notice of Android 12+). Only text clips are read.
     */
    private fun clipboardText(onlyNew: Boolean): String? = try {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val description = clipboard?.primaryClipDescription
        when {
            clipboard == null || description == null || !clipboard.hasPrimaryClip() -> null
            !description.hasMimeType("text/*") -> null
            else -> {
                val stamp = if (Build.VERSION.SDK_INT >= 26) description.timestamp else 0L
                if (onlyNew && stamp != 0L && stamp == lastClipStamp) {
                    null
                } else {
                    lastClipStamp = stamp
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                }
            }
        }
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    // ------------------------------------------------------------------------------------------------ other

    /** A classic screen for this visit (the choice of interface stays); back from it comes here again. */
    private fun openClassicScreen(screen: ClassicScreen) {
        try {
            startActivity(ClassicVisit.intent(this, screen))
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
        }
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

        /** The clip last read in this process (ClipDescription.getTimestamp), see [clipboardText]. */
        @Volatile
        var lastClipStamp = 0L
    }
}

/** What only the activity can do for the add flow: read the clipboard, open the scanner, pick a file. */
class AddLaunchers(val paste: () -> Unit, val scan: () -> Unit, val file: () -> Unit)

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
    add: AddViewModel,
    servers: ServersViewModel,
    settings: SettingsViewModel,
    launchers: AddLaunchers,
    openAppList: (AppList) -> Unit,
    requestStart: () -> Unit,
    onOpenClassic: () -> Unit,
    openClassicScreen: (ClassicScreen) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(SimpleSection.HOME) }
    var addSheet by rememberSaveable { mutableStateOf(false) }
    var linkDialog by rememberSaveable { mutableStateOf(false) }
    val addActions = remember(launchers) {
        AddActions(
            paste = {
                addSheet = false
                launchers.paste()
            },
            scan = {
                addSheet = false
                launchers.scan()
            },
            link = {
                addSheet = false
                linkDialog = true
            },
            file = {
                addSheet = false
                launchers.file()
            },
        )
    }
    // One snackbar for every section: the add flow reports from wherever it was started.
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(add) {
        add.notices.collect { notice -> snackbar.showSnackbar(notice.resolve(context)) }
    }

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
            when (section) {
                SimpleSection.HOME -> HomeSection(
                    home,
                    modes,
                    settings,
                    openSettings = { section = SimpleSection.SETTINGS },
                    HomeActions(
                        requestStart = requestStart,
                        openServers = { section = SimpleSection.SERVERS },
                        add = addActions,
                        openMode = { section = SimpleSection.SETTINGS },
                    ),
                    snackbar,
                )

                SimpleSection.SERVERS -> ServersSection(servers, onAdd = { addSheet = true }, snackbar = snackbar)
                SimpleSection.SETTINGS -> SettingsSection(
                    modes,
                    settings,
                    openAppList,
                    onOpenClassic,
                    openClassicScreen,
                    snackbar,
                )
            }
        }
    }

    // The add flow over any section.
    val addState by add.state.collectAsStateWithLifecycle()
    val homeState by home.state.collectAsStateWithLifecycle()
    if (addSheet) AddSheet(addActions) { addSheet = false }
    if (linkDialog) {
        LinkDialog(onDismiss = { linkDialog = false }) { text ->
            linkDialog = false
            add.importText(text)
        }
    }
    val busy = addState.busy
    val dialog = addState.dialog
    val offer = addState.offer
    when {
        busy != null -> AddBusyDialog(busy)
        dialog != null -> AddResultDialog(
            dialog,
            canConnect = homeState.phase == ConnectionPhase.OFF && homeState.hasServers == true,
            onConnect = {
                add.dismissDialog()
                section = SimpleSection.HOME
                home.toggle(requestStart)
            },
            onDismiss = add::dismissDialog,
        )

        offer != null -> ClipboardOfferDialog(offer, onAccept = add::acceptOffer, onDecline = add::declineOffer)
    }
}

private fun Notice.resolve(context: Context): String =
    if (plural != 0) context.resources.getQuantityString(plural, count, count) else context.getString(text)

@Composable
private fun HomeSection(
    home: HomeViewModel,
    modes: ModesViewModel,
    settings: SettingsViewModel,
    openSettings: () -> Unit,
    actions: HomeActions,
    snackbar: SnackbarHostState,
) {
    val state by home.state.collectAsStateWithLifecycle()
    val modesState by modes.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(home) {
        home.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    // A mode applied while the service starts or reloads: read what is in effect once the state settles.
    LaunchedEffect(state.phase) { modes.refresh() }
    // A mode applied (also from Settings while the VPN is off) may have switched the managed auto-selector.
    LaunchedEffect(modesState.applied) { if (modesState.applied > 0) home.refreshServer() }
    SectionScaffold(SimpleSection.HOME, snackbar) { modifier ->
        Column(modifier) {
            // After an upgrade, once: the classic interface is in the settings (simple-ui "Подсказка после обновления").
            if (settingsState.classicHint) {
                ClassicHintCard(
                    onOpenSettings = {
                        settings.closeClassicHint()
                        openSettings()
                    },
                    onClose = settings::closeClassicHint,
                )
            }
            HomeScreen(
                state = state,
                modeCard = modesState.card,
                actions = actions,
                onToggle = { home.toggle(actions.requestStart) },
                onDismissProblem = home::dismissProblem,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SectionScaffold(
    section: SimpleSection,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    // The navigation suite already keeps clear of the bottom/side system bars; the top bar takes the status bar.
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(section.label)) }, actions = actions) },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
private fun ServersSection(servers: ServersViewModel, onAdd: () -> Unit, snackbar: SnackbarHostState) {
    val state by servers.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(servers) {
        servers.messages.collect { message ->
            snackbar.showSnackbar(
                message.arg?.let { context.getString(message.text, it) } ?: context.getString(message.text)
            )
        }
    }
    // Opening the section reads the list again and starts the background delay check (simple-ui 5.3).
    LaunchedEffect(servers) { servers.opened() }
    SectionScaffold(
        SimpleSection.SERVERS,
        snackbar,
        actions = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.simple_add_server))
            }
        },
    ) { modifier ->
        ServersScreen(
            state = state,
            actions = ServersActions(
                chooseAuto = servers::chooseAuto,
                chooseCountry = servers::chooseCountry,
                chooseServer = servers::chooseServer,
                update = servers::update,
                updateAll = servers::updateAll,
                add = onAdd,
            ),
            modifier = modifier,
        )
    }
}

@Composable
private fun SettingsSection(
    modes: ModesViewModel,
    settings: SettingsViewModel,
    openAppList: (AppList) -> Unit,
    onOpenClassic: () -> Unit,
    openClassicScreen: (ClassicScreen) -> Unit,
    snackbar: SnackbarHostState,
) {
    val state by modes.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(modes) {
        modes.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    SectionScaffold(SimpleSection.SETTINGS, snackbar) { modifier ->
        SettingsScreen(
            state = state,
            settings = settingsState,
            actions = SettingsActions(
                selectMode = { modes.select(it, openAppList) },
                editApps = { modes.edit(it, openAppList) },
                dismissFallback = modes::dismissFallback,
                setAutostart = settings::setAutostart,
                setAutoUpdate = settings::setAutoUpdate,
                setNightMode = settings::setNightMode,
                setAdvanced = settings::setAdvanced,
                openClassicScreen = openClassicScreen,
                openClassic = onOpenClassic,
            ),
            modifier = modifier,
        )
    }
}
