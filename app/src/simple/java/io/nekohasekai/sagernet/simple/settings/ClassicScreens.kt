package io.nekohasekai.sagernet.simple.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.simple.SimpleActivity
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.settings.CoreSettingsFragment
import io.nekohasekai.sagernet.ui.settings.DiagnosticsFragment
import io.nekohasekai.sagernet.ui.settings.DnsSettingsFragment
import io.nekohasekai.sagernet.ui.settings.PresetSettingsFragment
import io.nekohasekai.sagernet.ui.settings.TestingSettingsFragment
import io.nekohasekai.sagernet.ui.settings.TunSettingsFragment
import java.util.WeakHashMap

/**
 * A screen of the classic interface the simple settings open: one of [MainActivity]'s drawer destinations ([nav]), or
 * one sub-screen of the classic settings ([settingsScreen], shown with [classicTitle] in the classic toolbar).
 * [advanced]: listed only in the advanced mode (simple-ui "Расширенный режим").
 */
enum class ClassicScreen(
    @IdRes val nav: Int,
    val settingsScreen: Class<out Fragment>?,
    @StringRes val classicTitle: Int,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val advanced: Boolean,
) {
    /** «Перенос на другое устройство»: the classic Tools screen is the backup (local file, share, WebDAV). */
    BACKUP(R.id.nav_tools, null, 0, R.string.simple_settings_transfer, R.string.simple_settings_transfer_summary, false),
    ROUTES(R.id.nav_route, null, 0, R.string.simple_advanced_routes, R.string.simple_advanced_routes_summary, true),
    DNS(
        R.id.nav_settings, DnsSettingsFragment::class.java, R.string.settings_dns,
        R.string.simple_advanced_dns, R.string.simple_advanced_dns_summary, true,
    ),
    NETWORK(
        R.id.nav_settings, TunSettingsFragment::class.java, R.string.settings_tun,
        R.string.simple_advanced_network, R.string.simple_advanced_network_summary, true,
    ),
    PRESETS(
        R.id.nav_settings, PresetSettingsFragment::class.java, R.string.settings_presets,
        R.string.simple_advanced_presets, R.string.simple_advanced_presets_summary, true,
    ),
    CORE(
        R.id.nav_settings, CoreSettingsFragment::class.java, R.string.settings_core,
        R.string.simple_advanced_core, R.string.simple_advanced_core_summary, true,
    ),
    TESTING(
        R.id.nav_settings, TestingSettingsFragment::class.java, R.string.settings_testing,
        R.string.simple_advanced_testing, R.string.simple_advanced_testing_summary, true,
    ),
    LOG(R.id.nav_logcat, null, 0, R.string.simple_advanced_log, R.string.simple_advanced_log_summary, true),
    GRAPHS(R.id.nav_live_graphs, null, 0, R.string.simple_advanced_graphs, R.string.simple_advanced_graphs_summary, true),
    TOOLS(
        R.id.nav_settings, DiagnosticsFragment::class.java, R.string.settings_diagnostics,
        R.string.simple_advanced_tools, R.string.simple_advanced_tools_summary, true,
    ),
    ALL_SETTINGS(R.id.nav_settings, null, 0, R.string.simple_advanced_all, R.string.simple_advanced_all_summary, true),
}

/**
 * A visit to a classic screen from the simple settings (design.md D3): [MainActivity] opens with the classic flag (the
 * redirect lets it stay) and [EXTRA_SCREEN]; [attach], called by [SimpleUi.redirect] for such an intent, puts the
 * screen up and makes back from it lead to the simple interface again instead of the classic home. Nothing of
 * MainActivity changes: it is driven through its public [MainActivity.displayFragmentWithId] and
 * [MainActivity.openSettingsScreen], and back goes through its own dispatcher.
 *
 * - Back where the classic screen itself would not handle it (MainActivity's own callback would show the classic home)
 *   finishes MainActivity, so the simple interface below it in the task comes back. Screens with their own back
 *   handling (a nested settings screen, search, selection) keep it: their callbacks are added at their start, above
 *   this one, which is added before the first fragment starts.
 * - A settings sub-screen sits on the classic settings root ([SettingsFragment.forScreen]); leaving the sub-screen it
 *   opened (back or the toolbar arrow) finishes the visit straight away; the root is never shown on the way out.
 * - The drawer stays: the visit may go on to any classic screen, and back from there also returns.
 */
object ClassicVisit {

    const val EXTRA_SCREEN = "io.nekohasekai.sagernet.simple.CLASSIC_SCREEN"

    /** Visits in progress, one per MainActivity instance. */
    private val visits = WeakHashMap<Activity, Visit>()

    fun intent(context: Context, screen: ClassicScreen): Intent =
        Intent(context, MainActivity::class.java)
            .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
            .putExtra(EXTRA_SCREEN, screen.name)

    fun screenOf(name: String?): ClassicScreen? = ClassicScreen.entries.firstOrNull { it.name == name }

    /**
     * [activity] stays in the classic interface for [intent]. Called from MainActivity.onCreate (after
     * super.onCreate, before its content and back callback) and from its onNewIntent (a running instance).
     */
    fun attach(activity: Activity, intent: Intent?) {
        val main = activity as? MainActivity ?: return
        val screen = screenOf(intent?.getStringExtra(EXTRA_SCREEN)) ?: return
        val running = visits[main]
        if (running != null) {
            running.show(screen)
            return
        }
        val creating = main.lifecycle.currentState == Lifecycle.State.INITIALIZED
        // A recreated activity (rotation, process death) has its screen restored by the fragment manager already.
        val restored = creating && main.supportFragmentManager.findFragmentById(R.id.fragment_holder) != null
        val visit = Visit(main, screen, show = !restored)
        visits[main] = visit
        main.application.registerActivityLifecycleCallbacks(visit)
        main.supportFragmentManager.registerFragmentLifecycleCallbacks(visit.settingsWatch, false)
        if (restored && screen.settingsScreen != null) visit.expectSettings = true
        // onNewIntent of a started instance: no onActivityStarted comes before the screen should show.
        if (!creating) visit.start()
    }

    private class Visit(
        private val main: MainActivity,
        private val screen: ClassicScreen,
        private val show: Boolean,
    ) : Application.ActivityLifecycleCallbacks {

        private var started = false

        /** The next [SettingsFragment] to come up is the one [show] opened with a sub-screen. */
        var expectSettings = false

        private val back = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = closeDrawerOrLeave()
        }

        /**
         * The settings sub-screen this visit opened: back and the toolbar arrow there leave the visit at once, so the
         * settings root below it never shows. Deeper screens (DNS › advanced) go back to it as usual.
         */
        val settingsWatch = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f !is SettingsFragment || !expectSettings) return
                expectSettings = false
                val children = f.childFragmentManager
                val leaveSubScreen = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() = closeDrawerOrLeave()
                }
                // Registered after SettingsFragment's own callback (its onViewCreated) for the same view lifecycle,
                // so it is added to the dispatcher after it on every start and ranks above it.
                main.onBackPressedDispatcher.addCallback(f.viewLifecycleOwner, leaveSubScreen)
                fun sync() {
                    val depth = children.backStackEntryCount
                    leaveSubScreen.isEnabled = depth <= 1
                    // Runs after SettingsFragment's own listener, which has just set the arrow to pop the stack.
                    if (depth == 1) f.toolbar?.setNavigationOnClickListener { leave() }
                }
                children.addOnBackStackChangedListener { sync() }
                sync()
            }
        }

        private fun closeDrawerOrLeave() {
            val drawer = main.binding.drawerLayout
            if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers() else leave()
        }

        /** MainActivity's first start: before its fragments start, so their back callbacks rank above [back]. */
        override fun onActivityStarted(activity: Activity) {
            if (activity === main) start()
        }

        fun start() {
            if (started) return
            started = true
            main.onBackPressedDispatcher.addCallback(back)
            if (show) show(screen)
        }

        fun show(screen: ClassicScreen) {
            val settings = screen.settingsScreen
            if (settings != null) {
                expectSettings = true
                main.openSettingsScreen(settings.name, main.getString(screen.classicTitle))
            } else {
                main.displayFragmentWithId(screen.nav)
            }
        }

        /** Back to the simple interface, which is below in the task (or comes up, if this is the task's root). */
        private fun leave() {
            if (main.isFinishing) return
            if (main.isTaskRoot) main.startActivity(Intent(main, SimpleActivity::class.java))
            main.finish()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity !== main) return
            main.application.unregisterActivityLifecycleCallbacks(this)
            visits.remove(main)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }
}
