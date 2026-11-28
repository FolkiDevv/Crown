package io.nekohasekai.sagernet.simple.modes

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.RouteRepo
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteDns
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RouteShare
import io.nekohasekai.sagernet.simple.config.SimpleConfig
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.regime.RegimeStore
import io.nekohasekai.sagernet.simple.servers.CountryChoice
import io.nekohasekai.sagernet.simple.servers.ServerCatalog
import io.nekohasekai.sagernet.simple.servers.ServerList
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Callable

/**
 * The state of the traffic modes and the "managed" entities behind them (design.md D5). Everything lives in DataStore
 * keys with the `simple_` prefix (design.md D3), which the `:bg` process reads the same way; the managed entities are
 * ordinary route profiles and auto-selectors, so the service needs nothing new to run them.
 *
 * Blocking: call it off the main thread.
 */
object TrafficModes {

    /** The mode the user chose ([TrafficMode.key]); absent until the simple UI first applied a mode. */
    const val KEY_MODE = "simple_mode"

    /** Counts the user's mode changes, so a network check started before one of them is not applied (design.md D6). */
    const val KEY_MODE_REV = "simple_mode_rev"

    /** The apps of «Только выбранные приложения», one package per line; kept whatever the mode. */
    const val KEY_APPS_SELECTED = "simple_apps_selected"

    /** «Приложения без VPN», one package per line; kept whatever the mode. */
    const val KEY_APPS_BYPASS = "simple_apps_bypass"

    /** «Только выбранные приложения» lost its last app and «Авто» took over: the settings say so until seen. */
    const val KEY_MODE_FALLBACK = "simple_mode_fallback"

    const val KEY_MANAGED_SMART_ROUTE = "simple_managed_route_smart"
    const val KEY_MANAGED_WHITELIST_ROUTE = "simple_managed_route_whitelist"
    const val KEY_MANAGED_AUTO = "simple_managed_auto"
    const val KEY_MANAGED_AUTO_WHITELIST = "simple_managed_auto_whitelist"

    /** The auto-selector that runs the best server of the country chosen in «Серверы» (batch 5). */
    const val KEY_MANAGED_COUNTRY = "simple_managed_country"

    /** That country, [CountryChoice.encode]d; absent while «Авто» or a single server is chosen. */
    const val KEY_COUNTRY_CHOICE = "simple_country_choice"

    /** The «Bypass Russia» entry of the routeprofiles snapshot's `Profile_Russia` set (traffic-modes "Умный режим"). */
    const val SMART_ROUTE_URL = "https://raw.githubusercontent.com/throneproj/routeprofiles/profile/Bypass_Russia"
    private const val SMART_ROUTE_NAME = "Bypass Russia"
    private const val SMART_ROUTE_BUNDLE = "Russia"


    private val store get() = DataStore.configurationStore

    fun isModeStored(): Boolean = store.getString(KEY_MODE) != null

    fun mode(): TrafficMode = TrafficMode.of(store.getString(KEY_MODE))

    fun setMode(mode: TrafficMode) = store.putString(KEY_MODE, mode.key)

    fun modeRevision(): Long = store.getLong(KEY_MODE_REV) ?: 0L

    fun bumpModeRevision() = store.putLong(KEY_MODE_REV, modeRevision() + 1)

    fun selectedApps(): List<String> = TrafficModePlan.parseList(store.getString(KEY_APPS_SELECTED))

    fun setSelectedApps(apps: List<String>) = store.putString(KEY_APPS_SELECTED, TrafficModePlan.clean(apps).joinToString("\n"))

    fun bypassApps(): List<String> = TrafficModePlan.parseList(store.getString(KEY_APPS_BYPASS))

    fun setBypassApps(apps: List<String>) = store.putString(KEY_APPS_BYPASS, TrafficModePlan.clean(apps).joinToString("\n"))

    fun fallbackNotice(): Boolean = store.getBoolean(KEY_MODE_FALLBACK) ?: false

    fun setFallbackNotice(value: Boolean) = if (value) store.putBoolean(KEY_MODE_FALLBACK, true) else store.remove(KEY_MODE_FALLBACK)

    /** The managed ids as stored, without creating anything. */
    fun managedIds(): Managed = Managed(
        smartRoute = store.getLong(KEY_MANAGED_SMART_ROUTE) ?: 0L,
        whitelistRoute = store.getLong(KEY_MANAGED_WHITELIST_ROUTE) ?: 0L,
        auto = store.getLong(KEY_MANAGED_AUTO) ?: 0L,
        autoWhitelist = store.getLong(KEY_MANAGED_AUTO_WHITELIST) ?: 0L,
        country = store.getLong(KEY_MANAGED_COUNTRY) ?: 0L,
    )

    fun countryChoice(): CountryChoice? = CountryChoice.parse(store.getString(KEY_COUNTRY_CHOICE))

    fun setCountryChoice(choice: CountryChoice?) =
        if (choice == null) store.remove(KEY_COUNTRY_CHOICE) else store.putString(KEY_COUNTRY_CHOICE, choice.encode())

    /**
     * The first start of the modes: the classic per-app list is taken over as the matching simple list, so applying
     * a mode never loses it, and an include list that was in use makes «Только выбранные приложения» the mode.
     */
    fun seedFromClassic() {
        if (isModeStored()) return
        var mode = TrafficMode.AUTO
        if (DataStore.proxyApps) {
            val classic = TrafficModePlan.parseList(DataStore.individual)
            if (DataStore.bypass) {
                if (store.getString(KEY_APPS_BYPASS) == null) setBypassApps(classic)
            } else if (classic.isNotEmpty()) {
                if (store.getString(KEY_APPS_SELECTED) == null) setSelectedApps(classic)
                mode = TrafficMode.APPS
            }
        }
        setMode(mode)
    }

    /**
     * What [ensureManaged] found or made: the ids, the routing profiles whose content it rewrote, and whether the
     * country selector now runs other servers (a subscription update renamed or replaced them).
     */
    data class Ensured(
        val ids: Managed,
        val changedRoutes: Set<Long>,
        val countryChanged: Boolean = false,
        /** The chosen country has no server left: «Авто» was selected instead of the country selector. */
        val countryGone: Boolean = false,
    )

    /**
     * Creates the managed entities that are missing (or were deleted in the classic interface) and brings the
     * generated ones up to date with [config]: the whitelist route's rules and DNS, the auto-selectors' tiers and their
     * groups. A running core keeps a rewritten route until it reloads; TrafficModeApplier takes care of that.
     */
    suspend fun ensureManaged(context: Context, config: SimpleConfig = SimpleConfigRepo.current()): Ensured {
        val smart = guarded(0L) { ensureSmartRoute() }
        val (whitelist, whitelistChanged) = guarded(0L to false) { ensureWhitelistRoute(context, config) }
        val (auto, autoWhitelist) = guarded(0L to 0L) { ensureSelectors(context, config) }
        val country = guarded(CountrySync.NONE) { syncCountrySelector(config) }
        return Ensured(
            Managed(smart, whitelist, auto, autoWhitelist, store.getLong(KEY_MANAGED_COUNTRY) ?: 0L),
            if (whitelistChanged && whitelist > 0L) setOf(whitelist) else emptySet(),
            countryChanged = country == CountrySync.CHANGED,
            countryGone = country == CountrySync.GONE,
        )
    }

    // ------------------------------------------------------------------------------------------------ routes

    /**
     * «Умный»: the «Bypass Russia» remote profile, added without a prompt like "Download profiles" adds it. It is
     * filled from the bundled snapshot at once (so it works offline on the first start), then fetched and kept up to
     * date by RemoteRouteUpdater like any auto-updated remote profile.
     */
    private fun ensureSmartRoute(): Long {
        val stored = store.getLong(KEY_MANAGED_SMART_ROUTE) ?: 0L
        if (stored > 0L && RouteManager.get(stored)?.takeIf { !it.is_raw } != null) return stored
        val entry = RouteRepo.bundles().firstOrNull { it.country == SMART_ROUTE_BUNDLE }
            ?.entries?.firstOrNull { it.url == SMART_ROUTE_URL }
            ?: RouteShare.RemoteEntry(SMART_ROUTE_URL, SMART_ROUTE_NAME)
        val id = RemoteRouteUpdater.addRemote(listOf(entry), autoUpdate = true).firstOrNull() ?: 0L
        if (id > 0L) {
            store.putLong(KEY_MANAGED_SMART_ROUTE, id)
            Logs.i("simple modes: added the smart routing profile $id")
        }
        return id
    }

    /**
     * «Белые списки»: DNS hijacked into the DNS module, private networks direct, everything else through the proxy;
     * its own DNS keeps remote lookups in the tunnel and resolves the server addresses with the whitelisted DNS servers
     * of the configuration. The user's global DNS settings are left alone. Any edit made in the classic interface (a
     * rule field such as `invert`, the default outbound, the DNS) is put back. Returns the id and whether the stored
     * content changed.
     */
    private fun ensureWhitelistRoute(context: Context, config: SimpleConfig): Pair<Long, Boolean> {
        val stored = store.getLong(KEY_MANAGED_WHITELIST_ROUTE) ?: 0L
        val existing = if (stored > 0L) RouteManager.get(stored)?.takeIf { !it.is_raw } else null
        val profile = existing ?: RouteProfile().apply { name = context.getString(R.string.simple_route_whitelist_name) }
        val desired = RouteProfile().apply {
            default_outbound_id = OutboundIds.PROXY
            custom_dns = true
            dns = RouteDns(remoteDns = DataStore.remoteDns, directDns = config.whitelistDns.joinToString(", "))
            rules = whitelistRules()
        }
        // The fingerprint of a remote route update covers every persisted rule column, `invert` included.
        val upToDate = existing != null && existing.custom_dns && existing.dns == desired.dns &&
            RemoteRouteUpdater.routeFingerprint(existing) == RemoteRouteUpdater.routeFingerprint(desired)
        if (upToDate) return existing!!.id to false
        profile.default_outbound_id = desired.default_outbound_id
        profile.is_raw = false
        profile.raw_route = ""
        profile.custom_dns = true
        profile.dns = desired.dns
        profile.rules = desired.rules
        val id = RouteManager.save(profile)
        if (id != stored) store.putLong(KEY_MANAGED_WHITELIST_ROUTE, id)
        return id to true
    }

    private fun whitelistRules(): MutableList<RouteRule> = mutableListOf(
        RouteRule().apply {
            name = "Route DNS"
            action = "hijack-dns"
            protocol = "dns"
        },
        RouteRule().apply {
            name = "Private networks"
            action = "route"
            ip_is_private = true
            outbound_id = OutboundIds.DIRECT
        },
    )

    // ------------------------------------------------------------------------------------------------ auto-selectors

    /**
     * «Авто» and «Авто · белые списки» (design.md D5): over every group, in the priority strategy. «Авто» puts the
     * ordinary servers in its first tier, so servers for whitelists only run when no ordinary one works; «Авто · белые
     * списки» the other way round.
     */
    private suspend fun ensureSelectors(context: Context, config: SimpleConfig): Pair<Long, Long> {
        val groups = GroupRepo.ids()
        val keywords = config.wlKeywords
        val auto = ensureSelector(
            context, KEY_MANAGED_AUTO, R.string.simple_selector_auto_name, groups,
            WhitelistKeywords.notRegex(keywords),
        )
        val autoWhitelist = ensureSelector(
            context, KEY_MANAGED_AUTO_WHITELIST, R.string.simple_selector_auto_whitelist_name, groups,
            WhitelistKeywords.regex(keywords),
        )
        return auto to autoWhitelist
    }

    private suspend fun ensureSelector(context: Context, key: String, name: Int, groups: List<Long>, firstTier: String): Long {
        val stored = store.getLong(key) ?: 0L
        // Without a server of its own a selector would only stand in for one: the home screen keeps offering to add
        // a server, and the selectors appear with the first one.
        if (stored <= 0L && !hasServers()) return 0L
        // The `:bg` runtime saves the same selector JSON (pool, pin, last build, history) under its own in-process
        // AutoSelectorProfiles.writes. The read and the write here run in one database write transaction, which
        // excludes every other writer of the database, the other process included, so this save starts from the
        // latest stored JSON and keeps whatever `:bg` wrote before it. Only the fields the runtime never writes
        // (groups, tiers, strategy) are changed, and only when they differ; should a stale `:bg` save put older
        // values back, the next ensure (every application, app start and group change) sets them again. The check
        // settings move once per ManagedAuto.TUNING_VERSION, so a classic edit stays; a running core takes them up at
        // its next start or reload, never through a restart of its own.
        val tuningKey = key + TUNING_SUFFIX
        val kept = AutoSelectorProfiles.writes.withLock {
            if (stored <= 0L) return@withLock false
            SagerDatabase.instance.runInTransaction(Callable {
                val existing = AutoSelectorProfiles.load(stored) ?: return@Callable false
                val tuning = store.getLong(tuningKey) ?: 0L
                val update = ManagedAuto.update(existing, groups, firstTier, tuning)
                if (update.save) AutoSelectorProfiles.save(stored, existing)
                if (update.tuning != tuning) store.putLong(tuningKey, update.tuning)
                true
            })
        }
        if (kept) return stored
        val selector = ManagedAuto.create(context.getString(name), groups, firstTier)
        val profile = ProfileManager.addProfile(selector, selectorGroup())
        store.putLong(key, profile.id)
        store.putLong(tuningKey, ManagedAuto.TUNING_VERSION)
        Logs.i("simple modes: added the managed auto-selector ${profile.id}")
        return profile.id
    }

    /**
     * Where a new managed auto-selector goes: next to the other one, else the first group that is not a subscription
     * (a subscription update could remove what it did not bring), else a new local group.
     */
    private suspend fun selectorGroup(): Long {
        for (key in listOf(KEY_MANAGED_AUTO, KEY_MANAGED_AUTO_WHITELIST)) {
            val id = store.getLong(key) ?: continue
            val group = ProfileManager.getProfile(id)?.groupId?.let { GroupRepo.get(it) }
            if (group != null && !group.isSubscription) return group.id
        }
        GroupRepo.all().firstOrNull { !it.isSubscription }?.let { return it.id }
        return GroupRepo.add(ProxyGroup(name = SELECTOR_GROUP_NAME)).id
    }

    private const val SELECTOR_GROUP_NAME = "Auto"

    /** After a managed selector's key: how far its check settings moved (the marker of ManagedAuto.update). */
    private const val TUNING_SUFFIX = "_tuning"

    // ------------------------------------------------------------------------------------------------ country

    /**
     * The managed country selector (batch 5, simple-ui "Выбор сервера в списке"): an auto-selector over the chosen
     * subscription [CountryChoice.groupId] whose name filter lets exactly [members] (the names of that country's
     * servers, none for whitelists) through, in the latency strategy, so the best working server of the country runs
     * and another one takes over when it stops answering. One selector serves every choice; it is created on the
     * first one. Returns its id (0 when it could not be made) and whether its servers changed.
     */
    suspend fun ensureCountrySelector(context: Context, choice: CountryChoice, members: List<String>): Pair<Long, Boolean> {
        val filter = exactNames(members)
        val stored = store.getLong(KEY_MANAGED_COUNTRY) ?: 0L
        val changed = if (stored > 0L) updateCountrySelector(stored, choice.groupId, filter) else null
        if (changed != null) return stored to changed
        val selector = AutoSelector().apply {
            name = context.getString(R.string.simple_selector_country_name)
            gids = arrayListOf(choice.groupId)
            nameFilter = filter
            strategy = AutoSelector.STRATEGY_LATENCY
        }
        val profile = ProfileManager.addProfile(selector, selectorGroup())
        store.putLong(KEY_MANAGED_COUNTRY, profile.id)
        Logs.i("simple servers: added the managed country selector ${profile.id}")
        return profile.id to true
    }

    /**
     * Sets the group and name filter of the stored country selector (same transaction rules as [ensureSelector]);
     * null when it is gone, else whether anything changed.
     */
    private suspend fun updateCountrySelector(id: Long, groupId: Long, filter: String): Boolean? =
        AutoSelectorProfiles.writes.withLock {
            SagerDatabase.instance.runInTransaction(Callable {
                val existing = AutoSelectorProfiles.load(id) ?: return@Callable null
                val gids = listOf(groupId)
                val changed = existing.gids != gids || existing.nameFilter != filter ||
                    existing.strategy != AutoSelector.STRATEGY_LATENCY || existing.tiers.isNotEmpty() ||
                    existing.countryFilter.isNotEmpty()
                if (changed) {
                    existing.gids = ArrayList(gids)
                    existing.nameFilter = filter
                    existing.countryFilter = ""
                    existing.strategy = AutoSelector.STRATEGY_LATENCY
                    existing.tiers = ArrayList()
                    AutoSelectorProfiles.save(id, existing)
                }
                changed
            })
        }

    /**
     * Keeps the chosen country's selector on that country's servers after a subscription update renamed, added or
     * removed some. When none is left (or the subscription is gone) the last list stays, and the selector reports
     * that it has nothing to run, as the classic one does.
     */
    private suspend fun syncCountrySelector(config: SimpleConfig): CountrySync {
        val choice = countryChoice() ?: return CountrySync.NONE
        val id = store.getLong(KEY_MANAGED_COUNTRY) ?: return CountrySync.NONE
        val members = ServerCatalog.countryMembers(choice, config.wlKeywords)
        if (members.isEmpty()) {
            // The subscription has no server of that country any more (or is gone): rather than keep a server of
            // another country, or none at all, the choice goes back to «Авто».
            Logs.i("simple servers: no server of the chosen country is left, back to Auto")
            setCountryChoice(null)
            // The «Авто» of the routing in effect, as a choice of «Авто» in «Серверы» would select it.
            val managed = managedIds()
            val auto = TrafficModePlan.server(managed.auto, managed.auto, managed.autoWhitelist, routingNow(mode()))
            if (DataStore.selectedProxy == id && auto > 0L) {
                DataStore.selectedProxy = auto
                return CountrySync.GONE
            }
            return CountrySync.NONE
        }
        val changed = updateCountrySelector(id, choice.groupId, exactNames(members)) ?: false
        return if (changed) CountrySync.CHANGED else CountrySync.NONE
    }

    /**
     * The routing [mode] runs now, the one place of the rule: «Авто» follows the network check's result, but only one
     * found in the physical network the device is in now (design.md D6, RegimeStore); until there is one it routes like
     * «Умный».
     */
    fun routingNow(mode: TrafficMode): Routing =
        TrafficModePlan.routing(mode, whitelistsDetected = mode == TrafficMode.AUTO && RegimeStore.whitelistsDetected())

    /** What [syncCountrySelector] did to the chosen country. */
    enum class CountrySync { NONE, CHANGED, GONE }

    private fun exactNames(names: List<String>): String = ServerList.exactNameFilter(names)

    /** Whether a profile other than an auto-selector exists. */
    fun hasServers(): Boolean = SagerDatabase.instance.query(
        "SELECT 1 FROM `${ProxyEntity.TABLE}` WHERE `type` != ? LIMIT 1", arrayOf(AutoSelectorProfiles.TYPE),
    ).use { it.moveToFirst() }

    /**
     * The ids of the managed auto-selectors ([Managed.selectors]). Each read is a query of the uncached settings, so a
     * loop over profiles takes the set once and tests `id in` it instead of calling [isManagedSelector] per profile.
     */
    fun managedSelectors(): Set<Long> = Managed.selectorSet(
        store.getLong(KEY_MANAGED_AUTO) ?: 0L,
        store.getLong(KEY_MANAGED_AUTO_WHITELIST) ?: 0L,
        store.getLong(KEY_MANAGED_COUNTRY) ?: 0L,
    )

    /** Whether [id] is one of the managed auto-selectors, which stand in for no server of their own. */
    fun isManagedSelector(id: Long): Boolean = id > 0L && id in managedSelectors()

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Logs.w("simple modes: managed entity", e)
        fallback
    }
}
