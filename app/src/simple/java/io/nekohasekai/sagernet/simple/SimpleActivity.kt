package io.nekohasekai.sagernet.simple

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.add.AddActions
import io.nekohasekai.sagernet.simple.add.AddBusyDialog
import io.nekohasekai.sagernet.simple.add.AddEvent
import io.nekohasekai.sagernet.simple.add.AddResultDialog
import io.nekohasekai.sagernet.simple.add.AddSheet
import io.nekohasekai.sagernet.simple.add.AddViewModel
import io.nekohasekai.sagernet.simple.add.ClipboardOfferDialog
import io.nekohasekai.sagernet.simple.add.CrownLinks
import io.nekohasekai.sagernet.simple.add.LinkConfirmDialog
import io.nekohasekai.sagernet.simple.add.LinkDialog
import io.nekohasekai.sagernet.simple.add.Notice
import io.nekohasekai.sagernet.simple.add.SimpleImport
import io.nekohasekai.sagernet.simple.home.ConnectionPhase
import io.nekohasekai.sagernet.simple.home.HomeActions
import io.nekohasekai.sagernet.simple.home.HomeScreen
import io.nekohasekai.sagernet.simple.home.HomeProblem
import io.nekohasekai.sagernet.simple.home.HomeViewModel
import io.nekohasekai.sagernet.simple.modes.AppList
import io.nekohasekai.sagernet.simple.modes.ModesViewModel
import io.nekohasekai.sagernet.simple.repair.RepairAction
import io.nekohasekai.sagernet.simple.repair.RepairActions
import io.nekohasekai.sagernet.simple.repair.RepairEnvironment
import io.nekohasekai.sagernet.simple.repair.RepairReportExport
import io.nekohasekai.sagernet.simple.repair.RepairScreen
import io.nekohasekai.sagernet.simple.repair.RepairViewModel
import io.nekohasekai.sagernet.simple.servers.ManageActions
import io.nekohasekai.sagernet.simple.servers.ServersActions
import io.nekohasekai.sagernet.simple.servers.ServersEvent
import io.nekohasekai.sagernet.simple.servers.ServersScreen
import io.nekohasekai.sagernet.simple.servers.ServersViewModel
import io.nekohasekai.sagernet.simple.settings.SettingsActions
import io.nekohasekai.sagernet.simple.settings.ClassicScreen
import io.nekohasekai.sagernet.simple.settings.ClassicVisit
import io.nekohasekai.sagernet.simple.settings.SettingsScreen
import io.nekohasekai.sagernet.simple.settings.SettingsViewModel
import io.nekohasekai.sagernet.simple.subs.AnnouncementSheet
import io.nekohasekai.sagernet.simple.subs.NoticeActions
import io.nekohasekai.sagernet.simple.subs.NoticeCard
import io.nekohasekai.sagernet.simple.subs.NoticesViewModel
import io.nekohasekai.sagernet.simple.subs.SubscriptionAlerts
import io.nekohasekai.sagernet.simple.subs.SubscriptionState
import io.nekohasekai.sagernet.simple.subs.openLink
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.profileSettingsIntent
import io.nekohasekai.sagernet.update.UpdateActivity
import io.nekohasekai.sagernet.utils.AlwaysOnVpn
import io.nekohasekai.sagernet.utils.BatteryOptimizations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The simple interface (openspec simple-ui-redesign). Opened by [SimpleUi.redirect] from MainActivity, which stays
 * the entry point of the launcher, notification, tile and shortcuts. Batch 1: the adaptive three-section frame
 * (bottom bar on narrow windows, navigation rail on medium/wide ones) and the switch to the classic interface.
 * Batch 2: the home section (connect button with its status, server and mode cards, problem banner).
 * Batch 4: adding servers (clipboard, QR, link, file; links, files and shares opened from other apps) and the
 * clipboard offer when the interface opens.
 * Batch 10: the whole settings section with the advanced mode (classic screens through [ClassicVisit]); the switch to
 * the classic interface sits in the advanced mode behind a warning and is not remembered.
 * Batch 7: «Починка» (RepairScreen) over the sections, from the settings' top button and the home banner.
 * Batch 11: home and «Серверы» side by side (ListDetailPaneScaffold) on a wide window that is not low, and the D-pad
 * (Android TV): a focus ring on every control and the focus on the main control of a screen as it opens (Focus.kt).
 */
class SimpleActivity : AppCompatActivity() {

    private val home: HomeViewModel by viewModels()
    private val modes: ModesViewModel by viewModels()
    private val add: AddViewModel by viewModels()
    private val servers: ServersViewModel by viewModels()
    private val settings: SettingsViewModel by viewModels()
    private val repair: RepairViewModel by viewModels()
    private val notices: NoticesViewModel by viewModels()

    /** The classic app list (AppManagerActivity) editing one of the simple UI's two app lists. */
    private val appList = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        modes.appListClosed()
    }

    /**
     * A classic editor of a server or a subscription; it reports a change the running connection must take (it reloads
     * the connection itself only in classic). Here, not in a section: the result must outlive a switch of the layout.
     */
    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(ProfileSettingsActivity.EXTRA_RESTART_NEEDED, false) == true) servers.settingsChanged()
    }

    /** The VPN consent, then the start of the service; true in the result when the consent was refused. */
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) { refused ->
        if (refused) home.message(R.string.simple_vpn_permission_denied)
    }

    /** The system's VPN consent dialog «Починка» asks for; the answer goes back to it (a rotation keeps it). */
    private val repairConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        repair.consentAnswered(result.resultCode == RESULT_OK)
    }

    /** The battery prompt follows the notification permission, whatever the answer. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { askBattery() }

    /** The battery-optimization prompt is open ([askBattery]); a rotation keeps it. */
    private val batteryPrompt = mutableStateOf(false)

    /** A screen this activity opened itself is in front (system settings, a classic screen, the scanner...). */
    private var ownLaunch = false

    /** The user left the app (not for a screen it opened, not a rotation): the next start is an entry. */
    private var leftApp = false

    /** «Открыть файл»: any file; a backup goes to the stock restore, the rest to the import. */
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { add.openFile(it) }
    }

    /** The QR scanner returns the texts of its codes here; scanned by the user, they are added without a question. */
    private val scan = registerForActivityResult(ScannerActivity.Scan()) { texts ->
        texts?.let(add::importTexts)
    }

    /** The clipboard is looked at once each time the interface comes to the front, when its window has the focus. */
    private var clipboardCheck = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Subscription defaults of the simple UI (HWID, the provider's interval), once per install.
        lifecycleScope.launch(Dispatchers.IO) { SimpleDefaults.apply(applicationContext) }
        // The notification channels of the subscription messages exist from the start, so Android lists them to set up.
        SubscriptionAlerts.ensureChannels(applicationContext)
        if (savedInstanceState == null) requestedSection.value = sectionOf(intent)
        if (savedInstanceState == null) {
            openAnnouncement(intent)
            openProvider(intent)
        }
        setContent {
            SimpleTheme {
                SimpleApp(
                    home = home,
                    modes = modes,
                    add = add,
                    servers = servers,
                    settings = settings,
                    repair = repair,
                    notices = notices,
                    openRepairTarget = ::openRepairTarget,
                    launchers = AddLaunchers(::pasteClipboard, ::openScanner, ::chooseFile),
                    openAppList = ::openAppList,
                    requestStart = ::requestStart,
                    onOpenClassic = { SimpleUi.switchTo(this, simple = false) },
                    openClassicScreen = ::openClassicScreen,
                    openEditor = { editor.launch(it) },
                    requestedSection = requestedSection.value,
                    onSectionShown = { requestedSection.value = null },
                )
                if (batteryPrompt.value) {
                    BatteryPromptDialog(onAllow = ::allowBackground, onLater = { batteryPrompt.value = false })
                }
            }
        }
        // «Починка» asks for the VPN consent before starting the VPN for its checks through it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repair.consentRequests.collect { askRepairConsent() }
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
                            // The import may have switched automatic subscription updates on (never set before).
                            settings.refresh()
                            // Its provider may have sent an announcement or a warning with the first download.
                            notices.reload()
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
                            notices.reload()
                        }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            // The end of a visit to a classic screen (EXTRA_SECTION) creates a new instance: not an entry.
            if (!askNotificationsOnce() && sectionOf(intent) == null) askBattery()
            handleImportIntent(intent)
        } else {
            batteryPrompt.value = savedInstanceState.getBoolean(STATE_BATTERY_PROMPT)
            leftApp = savedInstanceState.getBoolean(STATE_LEFT_APP)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_BATTERY_PROMPT, batteryPrompt.value)
        // Called before onStop below Android 9: count the leaving already, so a process killed meanwhile keeps it.
        outState.putBoolean(STATE_LEFT_APP, leftApp || leaving())
    }

    /** Every launch goes through here (startActivity too, and the activity result launchers). */
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        val before = ownLaunch
        ownLaunch = true
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (e: RuntimeException) {
            // Nothing opened (no such screen): the next leave is a real one.
            ownLaunch = before
            throw e
        }
    }

    /** A link, file, share or QR scan for the running interface (singleTask), handed over by MainActivity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sectionOf(intent)?.let { requestedSection.value = it }
        openAnnouncement(intent)
        openProvider(intent)
        handleImportIntent(intent)
    }

    /** A section to show, asked by [EXTRA_SECTION] (the end of a visit to a classic screen: settings). */
    private val requestedSection = mutableStateOf<SimpleSection?>(null)

    private fun sectionOf(intent: Intent?): SimpleSection? =
        intent?.getStringExtra(EXTRA_SECTION)?.let { name -> SimpleSection.entries.firstOrNull { it.name == name } }

    /** A provider message notification's «Подробнее»: the announcement opens over the home screen, once. */
    private fun openAnnouncement(intent: Intent?) {
        val gid = intent?.getLongExtra(SubscriptionAlerts.EXTRA_ANNOUNCE, 0L) ?: 0L
        if (gid <= 0L) return
        intent?.removeExtra(SubscriptionAlerts.EXTRA_ANNOUNCE)
        requestedSection.value = SimpleSection.HOME
        notices.openAnnouncement(gid)
    }

    /**
     * A subscription notification's «Продлить» / «Личный кабинет»: the provider's page opens, and the subscription is
     * checked again when the user comes back, as from the home card.
     */
    private fun openProvider(intent: Intent?) {
        val gid = intent?.getLongExtra(SubscriptionAlerts.EXTRA_PROVIDER_GROUP, 0L) ?: 0L
        val url = intent?.getStringExtra(SubscriptionAlerts.EXTRA_PROVIDER_URL).orEmpty()
        intent?.removeExtra(SubscriptionAlerts.EXTRA_PROVIDER_GROUP)
        intent?.removeExtra(SubscriptionAlerts.EXTRA_PROVIDER_URL)
        if (gid <= 0L || url.isEmpty()) return
        requestedSection.value = SimpleSection.HOME
        // The extras can come from any app (MainActivity passes them on): only a page this subscription itself names,
        // as its buttons may open it, is opened.
        lifecycleScope.launch {
            val allowed = withContext(Dispatchers.IO) {
                runCatching {
                    GroupRepo.get(gid)?.takeIf { it.isSubscription }?.let { group ->
                        SubscriptionState.factsOf(group, 0, emptyList()).let { url == it.webUrl || url == it.supportUrl }
                    } == true
                }.getOrDefault(false)
            }
            if (allowed && openLink(this@SimpleActivity, url)) notices.leavingFor(gid)
        }
    }

    override fun onStart() {
        super.onStart()
        home.setForeground(true)
        modes.refresh()
        // Groups added meanwhile in :bg (a subscription added in the classic interface) join the «Авто» selectors.
        modes.syncGroups()
        servers.reload()
        // A subscription the user left to renew is checked again; the messages may have changed meanwhile.
        notices.resumed()
        // Auto connect, updates and the theme may have been changed in the classic interface meanwhile.
        settings.refresh()
        clipboardCheck = true
        // Each time the user comes back to the app, until it runs without battery optimization.
        if (leftApp) {
            leftApp = false
            askBattery()
        }
        // A subscription whose import was interrupted (process killed during its first download) updates itself again.
        lifecycleScope.launch {
            val healed = withContext(Dispatchers.IO) {
                runCatching { SimpleImport.healInterrupted() }
                    .onFailure { Logs.w("simple add: interrupted imports", it) }.getOrDefault(false)
            }
            // The heal may have turned the global automatic updates on (an interrupted first import).
            if (healed) settings.refresh()
        }
    }

    override fun onPause() {
        // The provider's page now covers the app. Armed here, before the state is saved: on Android 8.1 and older that
        // happens before onStop, and a process ended while the page is open must still check the subscription.
        notices.stopped()
        super.onPause()
    }

    override fun onStop() {
        home.setForeground(false)
        if (leaving()) leftApp = true
        // A page opened as the activity starts can hide it before its first onResume, with no onPause to arm it.
        notices.stopped()
        super.onStop()
    }

    /** Stopping now leaves the app: not a rotation, a screen opened here or the screen turning off (app in front). */
    private fun leaving() = !isChangingConfigurations && !ownLaunch &&
        getSystemService(PowerManager::class.java)?.isInteractive != false

    override fun onResume() {
        super.onResume()
        ownLaunch = false
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
     * VIEW links and files, SEND texts and streams, as MainActivity took them before batch 4 — now without the classic
     * choice dialogs. They come from a browser or another app: a subscription or servers are added only after the user
     * confirms them. The QR scanner's texts come here only from the app itself ([SimpleUi.isOwn]): the scanner of the
     * launcher shortcut. Reopened from Recents (this activity may have become the task's root), nothing is imported again.
     */
    private fun handleImportIntent(intent: Intent?) {
        intent ?: return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.getStringExtra(SimpleUi.EXTRA_UNREADABLE_FILE)?.let {
            if (SimpleUi.isOwn(intent)) add.fileUnreadable(it)
            return
        }
        intent.getStringArrayListExtra(MainActivity.EXTRA_IMPORT_TEXTS)?.let {
            if (SimpleUi.isOwn(intent)) add.importTexts(it)
            return
        }
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val scheme = uri.scheme?.lowercase()
                // A file opened from a file manager is sent by another app too: what it would add is confirmed alike.
                if (scheme == "content" || scheme == "file") {
                    add.openFile(uri, external = true)
                } else {
                    CrownLinks.viewLink(uri.toString())?.let { add.importText(it, external = true) }
                }
            }

            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) {
                    add.openFile(stream, external = true)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { add.importText(it, external = true) }
                }
            }
        }
    }

    /**
     * A backup: MainActivity takes it as a VIEW of the file, like one opened from a file manager, and BackupEntry.open
     * there brings up the stock restore (Tools › Backup). The classic flag on this very intent, sent by the app itself,
     * keeps the redirect from sending it back here.
     */
    private fun openBackup(uri: Uri) {
        try {
            startActivity(
                SimpleUi.markOwn(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
                )
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
            scan.launch(Unit)
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
            SimpleUi.markOwn(
                Intent(this, MainActivity::class.java)
                    .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
                    .putExtra(SimpleUi.EXTRA_ROUTE_LINK, text)
            )
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

    /**
     * A classic screen for this visit (the choice of interface stays). This interface closes meanwhile, as for the
     * switch to the classic one: a window of it left below the classic start, which can be slow, would run into the
     * input timeout waiting to lose its focus. Leaving the visit opens it again on the settings section.
     */
    private fun openClassicScreen(screen: ClassicScreen) {
        try {
            startActivity(ClassicVisit.intent(this, screen))
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
            return
        }
        finish()
        // No transition, as SimpleUi.switchTo: with an animation the finishing window stays (and must still lose its
        // focus) until the classic window is drawn, which is what timed out; without, it is removed right away.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * A system settings screen «Починка» points to (connection-repair "Системные настройки только по действию
     * пользователя"); the general settings when the device has no such screen.
     */
    private fun openRepairTarget(target: RepairAction) {
        val intent = when (target) {
            RepairAction.DATE_SETTINGS -> Intent(AndroidSettings.ACTION_DATE_SETTINGS)
            RepairAction.NETWORK_SETTINGS -> Intent(AndroidSettings.ACTION_WIRELESS_SETTINGS)
            RepairAction.VPN_SETTINGS -> Intent(AndroidSettings.ACTION_VPN_SETTINGS)
            RepairAction.WIFI_SETTINGS -> Intent(AndroidSettings.ACTION_WIFI_SETTINGS)
            // Android TV has no battery screens (the request does nothing there): its app info has the permissions.
            RepairAction.BACKGROUND -> if (SagerNet.isTv) {
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            } else if (BatteryOptimizations.isBackgroundRestricted(this)) {
                BatteryOptimizations.detailsIntent(this)
            } else {
                BatteryOptimizations.requestIntent(this)
            }

            RepairAction.SERVERS -> null
        } ?: Intent(AndroidSettings.ACTION_SETTINGS)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
            try {
                startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
            } catch (e: ActivityNotFoundException) {
                Logs.w(e)
            }
        }
    }

    /** Shows the system's VPN consent dialog for «Починка»; a device without it counts as a refusal. */
    private fun askRepairConsent() {
        // A request whose check has ended meanwhile opens no dialog.
        if (!repair.consentWaited) return
        val intent = try {
            VpnService.prepare(this)
        } catch (e: Exception) {
            Logs.w(e)
            repair.consentAnswered(false)
            return
        }
        if (intent == null) {
            repair.consentAnswered(true)
            return
        }
        try {
            repairConsent.launch(intent)
            repair.consentDialogShown()
        } catch (e: ActivityNotFoundException) {
            Logs.w(e)
            repair.consentAnswered(false)
        } catch (e: SecurityException) {
            Logs.w(e)
            repair.consentAnswered(false)
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
     * True when the system request is shown: its answer then leads to [askBattery].
     */
    private fun askNotificationsOnce(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || SagerNet.isTv) return false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return false
        val prefs = getSharedPreferences(SimpleUi.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return false
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
        if (classicFirstStartShown()) return false
        return try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /**
     * The battery-optimization exemption on the first start and on every entry until it is given («Не сейчас» only
     * closes the prompt): Android otherwise stops or delays the VPN in the background and it disconnects after a
     * while. Skipped when exempt and on Android TV (no such screen, as in «Починка»); a prompt left open closes once
     * the exemption was given meanwhile (in Android's settings).
     */
    private fun askBattery() {
        if (isFinishing || isDestroyed) return
        batteryPrompt.value = !SagerNet.isTv && !BatteryOptimizations.isIgnoring(this) &&
            BatteryOptimizations.requestIntent(this) != null
    }

    /** «Разрешить»: the system's own request (else the list of apps), where only the user decides. */
    private fun allowBackground() {
        batteryPrompt.value = false
        if (!BatteryOptimizations.launch(this, BatteryOptimizations.requestIntent(this))) {
            Logs.w("No screen for the battery-optimization exemption")
        }
    }

    private fun classicFirstStartShown() = getSharedPreferences(CLASSIC_FIRST_START_PREFS, Context.MODE_PRIVATE)
        .getBoolean(CLASSIC_FIRST_START_SHOWN, false)

    private fun excludeFromRecents() {
        try {
            getSystemService(ActivityManager::class.java)?.appTasks?.firstOrNull()?.setExcludeFromRecents(true)
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    companion object {
        /** The section to open ([SimpleSection] name): the end of a visit to a classic screen asks for settings. */
        const val EXTRA_SECTION = "io.nekohasekai.sagernet.simple.SECTION"

        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
        private const val STATE_BATTERY_PROMPT = "battery_prompt"
        private const val STATE_LEFT_APP = "left_app"

        /** FirstStartPrompts' own flag: the classic first start has asked for the permission. */
        private const val CLASSIC_FIRST_START_PREFS = "first_start"
        private const val CLASSIC_FIRST_START_SHOWN = "prompts_shown"

        /** The clip last read in this process (ClipDescription.getTimestamp), see [clipboardText]. */
        @Volatile
        private var lastClipStamp = 0L
    }
}

/** What only the activity can do for the add flow: read the clipboard, open the scanner, pick a file. */
class AddLaunchers(val paste: () -> Unit, val scan: () -> Unit, val file: () -> Unit)

enum class SimpleSection(@StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.simple_nav_home, Icons.Filled.Home),
    SERVERS(R.string.simple_nav_servers, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.simple_nav_settings, Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class, ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SimpleApp(
    home: HomeViewModel,
    modes: ModesViewModel,
    add: AddViewModel,
    servers: ServersViewModel,
    settings: SettingsViewModel,
    repair: RepairViewModel,
    notices: NoticesViewModel,
    /** A system settings screen «Починка» leads to (only the user changes Android's settings). */
    openRepairTarget: (RepairAction) -> Unit,
    launchers: AddLaunchers,
    openAppList: (AppList) -> Unit,
    requestStart: () -> Unit,
    onOpenClassic: () -> Unit,
    openClassicScreen: (ClassicScreen) -> Unit,
    /** A classic editor of a server or a subscription ([SimpleActivity.editor]). */
    openEditor: (Intent) -> Unit,
    /** A section asked for from outside (the end of a classic visit); [onSectionShown] once it is shown. */
    requestedSection: SimpleSection? = null,
    onSectionShown: () -> Unit = {},
) {
    var section by rememberSaveable { mutableStateOf(requestedSection ?: SimpleSection.HOME) }
    LaunchedEffect(requestedSection) {
        if (requestedSection != null) {
            section = requestedSection
            onSectionShown()
        }
    }
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
    // The subscription messages (simple-ui "Сообщения подписки"): a page of the provider is checked again on return.
    val noticeActions = remember(notices, context) {
        NoticeActions(
            recheck = notices::recheck,
            later = notices::later,
            hide = notices::hide,
            more = { notices.openAnnouncement(it.groupId) },
            openProvider = { gid, url -> if (openLink(context, url)) notices.leavingFor(gid) },
        )
    }
    LaunchedEffect(add) {
        add.notices.collect { notice -> snackbar.showSnackbar(notice.resolve(context)) }
    }

    // «Починка» (batch 7): from the settings' top button and the home banner; a run starts as it opens. Batch 8: it
    // starts the VPN for its checks through it (with the consent the activity asks for) and stops it like the button.
    val repairEnvironment = remember(home, repair) {
        RepairEnvironment(
            vpnConnected = { home.vpnConnected },
            serviceOff = { home.serviceOff },
            testConnection = { timeoutMs -> home.testConnection(timeoutMs) },
            requestConsent = { repair.requestConsent() },
            startVpn = { SagerNet.startService() },
            stopVpn = { home.stop() },
            connects = { home.connects },
            awaitConnected = { after, timeoutMs -> home.awaitConnected(after, timeoutMs) },
        )
    }
    val openRepair = remember(repair, repairEnvironment) { { repair.open(repairEnvironment) } }
    val repairState by repair.state.collectAsStateWithLifecycle()
    // The report archive (batch 9): the share sheet opens only for the user's own press of «Экспортировать отчёт».
    // The archive stays pending until the sheet comes back with a result. It is opened while the activity is resumed,
    // and again at every resume while still pending. Android silently blocks an activity start from the background (the
    // user pressed Home just as the archive got ready): such a sheet is finished, cancelled, when the user returns, and
    // RepairViewModel.shareResult tells it from a dismissed one by the app leaving right after the start (ShareGuard).
    // A sheet that did open keeps this activity paused until it returns, and its result comes before the next resume.
    val shareSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        repair.shareResult(ok = result.resultCode == Activity.RESULT_OK)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // A rotation stops the activity too, with the sheet still up over it: not the user leaving.
            if (event == Lifecycle.Event.ON_STOP && (context as? Activity)?.isChangingConfigurations != true) {
                repair.appStopped()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repair, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            repair.state.map { it.pendingShare }.distinctUntilChanged().collect { file ->
                if (file == null) return@collect
                try {
                    shareSheet.launch(
                        RepairReportExport.shareIntent(
                            context, file, context.getString(R.string.simple_repair_export_share), repair.chosenTarget(),
                        )
                    )
                    repair.shareLaunched()
                } catch (_: ActivityNotFoundException) {
                    repair.shareDone()
                    Toast.makeText(context, R.string.simple_repair_export_no_target, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    LaunchedEffect(repair) {
        repair.exportFailures.collect {
            Toast.makeText(context, R.string.simple_repair_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    if (repairState.open) {
        val homeState by home.state.collectAsStateWithLifecycle()
        RepairScreen(
            state = repairState,
            detail = (homeState.problem as? HomeProblem.ConnectionLost)?.detail,
            actions = RepairActions(
                stop = repair::stop,
                again = { repair.start(repairEnvironment) },
                close = repair::close,
                export = repair::export,
                open = { target ->
                    if (target == RepairAction.SERVERS) {
                        repair.close()
                        section = SimpleSection.SERVERS
                    } else {
                        // The user goes to change what the checks look at: the run stops, the screen stays for «Проверить снова».
                        repair.stop()
                        openRepairTarget(target)
                    }
                },
            ),
        )
        return
    }

    // Rail whenever the window is not narrow, by width only: the library default keeps the bottom bar on any window
    // of compact height, i.e. on every phone in landscape (simple-ui spec: bar on narrow, side panel on medium/wide)
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = if (adaptiveInfo.windowPosture.isTabletop ||
        adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT
    ) NavigationSuiteType.NavigationBar else NavigationSuiteType.NavigationRail
    // Home and the server list side by side (simple-ui "Планшет или развёрнутый складной телефон") on a wide window
    // that is not low: a tablet, an unfolded foldable, a TV. A phone in landscape is as wide but low; it keeps one
    // section at a time with the button beside the cards, as the two panes would leave the button no room.
    val twoPane = adaptiveInfo.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED &&
        adaptiveInfo.windowSizeClass.windowHeightSizeClass != WindowHeightSizeClass.COMPACT
    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            SimpleSection.entries.forEach { item ->
                item(
                    selected = item == section,
                    onClick = { section = item },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label)) },
                    modifier = Modifier.focusRing(FocusShapes.NavItem, outset = (-3).dp),
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
            val homeSection = @Composable { focus: Boolean, showSnackbar: Boolean ->
                HomeSection(
                    home,
                    modes,
                    HomeActions(
                        requestStart = requestStart,
                        openServers = { section = SimpleSection.SERVERS },
                        add = addActions,
                        openMode = { section = SimpleSection.SETTINGS },
                        openRepair = openRepair,
                    ),
                    snackbar,
                    focus = focus,
                    showSnackbar = showSnackbar,
                    notices = notices,
                    noticeActions = noticeActions,
                )
            }
            val serversSection = @Composable { focus: Boolean, showSnackbar: Boolean ->
                ServersSection(
                    servers, onAdd = { addSheet = true }, snackbar, focus = focus, showSnackbar = showSnackbar,
                    openProvider = noticeActions.openProvider, announce = notices::openAnnouncement, openEditor = openEditor,
                )
            }
            when {
                // The section chosen gets the focus of a remote; the other pane keeps its place.
                twoPane && section != SimpleSection.SETTINGS -> HomeAndServers(
                    directive = calculatePaneScaffoldDirective(adaptiveInfo),
                    home = { homeSection(section == SimpleSection.HOME, false) },
                    servers = { serversSection(section == SimpleSection.SERVERS, false) },
                    snackbar = snackbar,
                )

                section == SimpleSection.HOME -> homeSection(true, true)
                section == SimpleSection.SERVERS -> serversSection(true, true)
                else -> SettingsSection(
                    modes,
                    settings,
                    openAppList,
                    onOpenClassic,
                    openClassicScreen,
                    openRepair,
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
    val confirm = addState.confirm
    when {
        busy != null -> AddBusyDialog(busy)
        confirm != null -> LinkConfirmDialog(confirm, onAccept = add::confirmLink, onCancel = add::cancelLink)
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

    // The whole announcement, from the home card, the subscription card or the notification.
    val noticesState by notices.state.collectAsStateWithLifecycle()
    noticesState.sheet?.let { announcement ->
        AnnouncementSheet(
            announcement,
            onProvider = noticeActions.openProvider,
            onDismiss = notices::closeAnnouncement,
        )
    }
}

private fun Notice.resolve(context: Context): String =
    if (plural != 0) context.resources.getQuantityString(plural, count, count) else context.getString(text)

@Composable
private fun HomeSection(
    home: HomeViewModel,
    modes: ModesViewModel,
    actions: HomeActions,
    snackbar: SnackbarHostState,
    /** A remote starts on the connect button as the section opens. */
    focus: Boolean,
    /** The section shows the snackbar itself (not when it shares the window with the server list). */
    showSnackbar: Boolean,
    notices: NoticesViewModel,
    noticeActions: NoticeActions,
) {
    val state by home.state.collectAsStateWithLifecycle()
    val noticesState by notices.state.collectAsStateWithLifecycle()
    val modesState by modes.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(home) {
        home.messages.collect { snackbar.showSnackbar(context.getString(it)) }
    }
    // A mode applied while the service starts or reloads: read what is in effect once the state settles.
    LaunchedEffect(state.phase) { modes.refresh() }
    // «Авто» following the network switches the route and the managed selector together: when the server card shows
    // the other selector, the mode card reads the route again, so the two cards never disagree for long.
    LaunchedEffect(state.server?.name, state.server?.localizedName) { modes.refresh() }
    // A mode applied (also from Settings while the VPN is off) may have switched the managed auto-selector.
    LaunchedEffect(modesState.applied) { if (modesState.applied > 0) home.refreshServer() }
    SectionScaffold(SimpleSection.HOME, snackbar.takeIf { showSnackbar }) { modifier ->
        Column(modifier) {
            HomeScreen(
                state = state,
                modeCard = modesState.card,
                actions = actions,
                onToggle = { home.toggle(actions.requestStart) },
                onDismissProblem = home::dismissProblem,
                onLeaveWhitelists = { mode ->
                    home.leaveWhitelists(mode) { done ->
                        modes.refresh()
                        // A server for whitelists with no «Авто» selector to take over: the user picks another one.
                        if (!done) actions.openServers()
                    }
                },
                modifier = Modifier.weight(1f),
                focus = focus,
                notice = noticesState.home?.let { notice ->
                    { noticeModifier -> NoticeCard(notice, noticeActions, noticeModifier) }
                },
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
private fun ServersSection(
    servers: ServersViewModel,
    onAdd: () -> Unit,
    snackbar: SnackbarHostState,
    focus: Boolean,
    showSnackbar: Boolean,
    openProvider: (Long, String) -> Unit,
    announce: (Long) -> Unit,
    openEditor: (Intent) -> Unit,
) {
    val state by servers.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(servers) {
        servers.messages.collect { message ->
            val text = message.arg?.let { context.getString(message.text, it) } ?: context.getString(message.text)
            val action = message.action
            if (action == null) {
                snackbar.showSnackbar(text)
            } else {
                // Its own coroutine: the next message waits for this one, but the list keeps collecting.
                launch {
                    val result = snackbar.showSnackbar(
                        text, actionLabel = context.getString(message.actionLabel), duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) action()
                }
            }
        }
    }
    // Opening the section reads the list again and starts the background delay check (simple-ui 5.3).
    LaunchedEffect(servers) { servers.opened() }
    SectionScaffold(
        SimpleSection.SERVERS,
        snackbar.takeIf { showSnackbar },
        actions = {
            IconButton(onClick = onAdd, modifier = Modifier.focusRing(FocusShapes.Pill, outset = (-3).dp)) {
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
                manage = remember(servers, context) {
                    ManageActions(
                        update = servers::update,
                        openProvider = openProvider,
                        renameSubscription = servers::renameSubscription,
                        shareSubscription = servers::shareSubscription,
                        deleteSubscription = servers::deleteSubscription,
                        subscriptionSettings = { gid ->
                            openEditor(
                                Intent(context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, gid)
                            )
                        },
                        connect = servers::chooseServer,
                        testServer = servers::testServer,
                        renameServer = servers::renameServer,
                        shareServer = servers::shareServer,
                        deleteServer = servers::deleteServer,
                        editServer = { id -> scope.launch { openServerEditor(context, id, openEditor) } },
                        closeShare = servers::closeShare,
                    )
                },
                announce = announce,
            ),
            modifier = modifier,
            focus = focus,
        )
    }
}

/**
 * The wide layout (simple-ui "Адаптивная навигация и раскладка"): home as the list pane and «Серверы» as the detail
 * pane of ListDetailPaneScaffold, both shown; one snackbar for the two, over the whole width.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun HomeAndServers(
    directive: PaneScaffoldDirective,
    home: @Composable () -> Unit,
    servers: @Composable () -> Unit,
    snackbar: SnackbarHostState,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>(scaffoldDirective = directive)
    Box {
        ListDetailPaneScaffold(
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = { AnimatedPane { home() } },
            detailPane = { AnimatedPane { servers() } },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun SettingsSection(
    modes: ModesViewModel,
    settings: SettingsViewModel,
    openAppList: (AppList) -> Unit,
    onOpenClassic: () -> Unit,
    openClassicScreen: (ClassicScreen) -> Unit,
    openRepair: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val state by modes.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                setUpdateCheck = settings::setUpdateCheck,
                setBetaUpdates = settings::setBetaUpdates,
                openClassicScreen = openClassicScreen,
                openClassic = onOpenClassic,
                openRepair = openRepair,
                openVpnSettings = {
                    if (!AlwaysOnVpn.openSettings(context)) {
                        scope.launch { snackbar.showSnackbar(context.getString(R.string.vpn_settings_missing)) }
                    }
                },
                checkUpdates = if (BuildConfig.IN_APP_UPDATER) {
                    { UpdateActivity.start(context) }
                } else {
                    null
                },
            ),
            modifier = modifier,
        )
    }
}

/**
 * The request to run without battery optimization (simple-ui «Работа в фоне без оптимизации батареи»):
 * otherwise Android stops or delays the VPN in the background and it disconnects on its own after a while.
 */
@Composable
private fun BatteryPromptDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    val button = Modifier.focusRing(FocusShapes.Pill)
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.simple_battery_prompt_title)) },
        text = { Text(stringResource(R.string.simple_battery_prompt_text)) },
        confirmButton = {
            TextButton(onClick = onAllow, modifier = button.focusWhenShown()) {
                Text(stringResource(R.string.simple_battery_prompt_allow))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = button) { Text(stringResource(R.string.simple_offer_later)) }
        },
    )
}

/** The classic editor of an own server («Изменить параметры», advanced mode); back returns to the list. */
private suspend fun openServerEditor(context: Context, id: Long, launch: (Intent) -> Unit) {
    val profile = withContext(Dispatchers.IO) { ProfileManager.getProfile(id) } ?: return
    try {
        launch(profile.profileSettingsIntent(context, false))
    } catch (e: ActivityNotFoundException) {
        Logs.w(e)
    }
}
