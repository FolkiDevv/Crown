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
import kotlinx.coroutines.sync.withLock

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
    )

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
     * Creates the managed entities that are missing (or were deleted in the classic interface) and brings the
     * generated ones up to date with [config]: the whitelist route's DNS, the auto-selectors' tiers and their groups.
     */
    suspend fun ensureManaged(context: Context, config: SimpleConfig = SimpleConfigRepo.current()): Managed {
        val smart = guarded(0L) { ensureSmartRoute() }
        val whitelist = guarded(0L) { ensureWhitelistRoute(context, config) }
        val (auto, autoWhitelist) = guarded(0L to 0L) { ensureSelectors(context, config) }
        return Managed(smart, whitelist, auto, autoWhitelist)
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
     * of the configuration. The user's global DNS settings are left alone.
     */
    private fun ensureWhitelistRoute(context: Context, config: SimpleConfig): Long {
        val stored = store.getLong(KEY_MANAGED_WHITELIST_ROUTE) ?: 0L
        val existing = if (stored > 0L) RouteManager.get(stored)?.takeIf { !it.is_raw } else null
        val profile = existing ?: RouteProfile().apply { name = context.getString(R.string.simple_route_whitelist_name) }
        val dns = RouteDns(remoteDns = DataStore.remoteDns, directDns = config.whitelistDns.joinToString(", "))
        val rules = whitelistRules()
        val upToDate = existing != null && existing.custom_dns && existing.dns == dns &&
            existing.default_outbound_id == OutboundIds.PROXY && sameRules(existing.rules, rules)
        if (upToDate) return existing!!.id
        profile.default_outbound_id = OutboundIds.PROXY
        profile.custom_dns = true
        profile.dns = dns
        profile.rules = rules
        val id = RouteManager.save(profile)
        if (id != stored) store.putLong(KEY_MANAGED_WHITELIST_ROUTE, id)
        return id
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

    private fun sameRules(a: List<RouteRule>, b: List<RouteRule>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.action == y.action && x.protocol == y.protocol && x.ip_is_private == y.ip_is_private &&
                x.outbound_id == y.outbound_id && !x.disabled && x.isOnly(y)
        }

    /** Nothing else set on a rule the user may have edited in the classic interface. */
    private fun RouteRule.isOnly(expected: RouteRule): Boolean =
        domain.isEmpty() && domain_suffix.isEmpty() && domain_keyword.isEmpty() && domain_regex.isEmpty() &&
            ip_cidr.isEmpty() && rule_set.isEmpty() && name == expected.name

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
        AutoSelectorProfiles.writes.withLock {
            val existing = if (stored > 0L) AutoSelectorProfiles.load(stored) else null
            if (existing != null) {
                val tiers = listOf(AutoSelector.Tier(-1, firstTier))
                if (existing.gids != groups || existing.tiers != tiers || !existing.prioritized) {
                    existing.gids = ArrayList(groups)
                    existing.tiers = ArrayList(tiers)
                    existing.strategy = AutoSelector.STRATEGY_PRIORITY
                    AutoSelectorProfiles.save(stored, existing)
                }
                return stored
            }
        }
        val selector = AutoSelector().apply {
            this.name = context.getString(name)
            gids = ArrayList(groups)
            strategy = AutoSelector.STRATEGY_PRIORITY
            tiers = arrayListOf(AutoSelector.Tier(-1, firstTier))
        }
        val profile = ProfileManager.addProfile(selector, selectorGroup())
        store.putLong(key, profile.id)
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

    /** Whether a profile other than an auto-selector exists. */
    fun hasServers(): Boolean = SagerDatabase.instance.query(
        "SELECT 1 FROM `${ProxyEntity.TABLE}` WHERE `type` != ? LIMIT 1", arrayOf(AutoSelectorProfiles.TYPE),
    ).use { it.moveToFirst() }

    /** Whether [id] is one of the managed auto-selectors, which stand in for no server of their own. */
    fun isManagedSelector(id: Long): Boolean =
        id > 0L && (id == store.getLong(KEY_MANAGED_AUTO) || id == store.getLong(KEY_MANAGED_AUTO_WHITELIST))

    private inline fun <T> guarded(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Logs.w("simple modes: managed entity", e)
        fallback
    }
}
