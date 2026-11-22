package io.nekohasekai.sagernet.simple.modes

/** The traffic modes of the simple UI (traffic-modes spec "Набор режимов трафика"); [key] is what DataStore keeps. */
enum class TrafficMode(val key: String) {
    AUTO("auto"),
    SMART("smart"),
    WHITELIST("whitelist"),
    APPS("apps");

    companion object {
        /** «Авто» by default and for anything unknown. */
        fun of(key: String?): TrafficMode = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/** The routing profile a mode runs with right now. */
enum class Routing { SMART, WHITELIST }

/**
 * The per-app scope written to the upstream keys (design.md D5): `proxyApps` = [enabled], `bypass` = [bypass],
 * `individual` = [packages] joined by newlines.
 */
data class PerAppScope(val enabled: Boolean, val bypass: Boolean, val packages: List<String>) {
    val individual: String get() = packages.joinToString("\n")
}

/** The states of the home screen's mode card (simple-ui spec "Карточки сервера и режима на главной"). */
enum class ModeCard {
    /** «Авто · умный режим». */
    AUTO_SMART,

    /** «Авто · белые списки», in the warning colour. */
    AUTO_WHITELIST,
    SMART,

    /** «Белые списки», in the warning colour. */
    WHITELIST,
    APPS,

    /** A routing profile of the classic interface is in effect, not one of the modes. */
    CUSTOM;

    val whitelists: Boolean get() = this == AUTO_WHITELIST || this == WHITELIST
}

/** What a change of the applied state asks of a running service. */
enum class ServiceChange {
    NONE,

    /** The routing profile or the server changed: the core is rebuilt in place. */
    RELOAD,

    /** The per-app scope changed: the TUN interface has to be established again with the new app list. */
    RESTART,
}

/**
 * The pure rules of applying a mode (design.md D5), kept apart from Android so JVM unit tests cover them (D10).
 */
object TrafficModePlan {

    /**
     * The mode that can actually be applied: «Только выбранные приложения» with no app chosen falls back to «Авто»
     * (traffic-modes spec "Пустой список выбранных приложений"), never to an include list without apps.
     */
    fun applicable(mode: TrafficMode, selectedApps: List<String>): TrafficMode =
        if (mode == TrafficMode.APPS && selectedApps.none { it.isNotBlank() }) TrafficMode.AUTO else mode

    /**
     * The routing of [mode]. «Авто» follows the network check ([whitelistsDetected], from batch 6; until then it is
     * always false and «Авто» routes like «Умный»); «Только выбранные приложения» uses the smart profile.
     */
    fun routing(mode: TrafficMode, whitelistsDetected: Boolean): Routing = when (mode) {
        TrafficMode.WHITELIST -> Routing.WHITELIST
        TrafficMode.AUTO -> if (whitelistsDetected) Routing.WHITELIST else Routing.SMART
        TrafficMode.SMART, TrafficMode.APPS -> Routing.SMART
    }

    /**
     * The per-app scope of [mode] from the two lists the simple UI keeps (design.md D5): only the chosen apps in
     * «Только выбранные приложения», every app but the «Приложения без VPN» list otherwise (or no per-app rule at all
     * when that list is empty). [ownPackage] always stays in the tunnel, so the app's own requests (subscription and
     * route profile updates) go through the VPN while it is on, in every mode.
     *
     * [mode] must be [applicable]: an empty selection throws rather than turning into an include list without apps.
     */
    fun scope(mode: TrafficMode, selectedApps: List<String>, bypassApps: List<String>, ownPackage: String): PerAppScope {
        if (mode == TrafficMode.APPS) {
            val selected = clean(selectedApps)
            require(selected.isNotEmpty()) { "no app selected" }
            return PerAppScope(enabled = true, bypass = false, packages = (selected + ownPackage).distinct())
        }
        val bypass = clean(bypassApps) - ownPackage
        return if (bypass.isEmpty()) {
            PerAppScope(enabled = false, bypass = true, packages = emptyList())
        } else {
            PerAppScope(enabled = true, bypass = true, packages = bypass)
        }
    }

    /**
     * The server to run: the user's own choice stays (traffic-modes spec "Ручной выбор сервера сохраняется"); only a
     * choice of one of the managed auto-selectors follows the routing.
     */
    fun server(selected: Long, auto: Long, autoWhitelist: Long, routing: Routing): Long {
        if (selected <= 0L || (selected != auto && selected != autoWhitelist)) return selected
        return if (routing == Routing.WHITELIST && autoWhitelist > 0L) autoWhitelist else if (auto > 0L) auto else selected
    }

    /**
     * What the running service has to do after the applied state went from [before] to [after].
     *
     * [changedRoutes]: routing profiles whose content was rewritten under the same id (the managed whitelist route
     * after a configuration update or a classic edit); the running core keeps the old content until it reloads.
     * [stagedScopeMayRun]: the service started while an app list edit had other per-app keys in place, so it may run
     * with those rather than with [before]'s; the TUN is then rebuilt whatever [before] says.
     */
    fun change(
        before: Applied,
        after: Applied,
        changedRoutes: Set<Long> = emptySet(),
        stagedScopeMayRun: Boolean = false,
    ): ServiceChange = when {
        stagedScopeMayRun || before.scope.normalized() != after.scope.normalized() -> ServiceChange.RESTART
        before.routeId != after.routeId || before.server != after.server || after.routeId in changedRoutes ->
            ServiceChange.RELOAD

        else -> ServiceChange.NONE
    }

    /**
     * Whether a core may have built its TUN from the per-app keys an app list edit staged (ModesViewModel.edit):
     * - [startsAtEdit] / [startsNow]: SimpleHooks' start counter when the list opened and when it closed (null when the
     *   value was lost). A restart in place is counted as soon as the old core stops, before the new one reads its
     *   settings; a completed fresh start is counted at Connected.
     * - [startInProgress]: the service is «Подключение…» right now. A fresh start reads its settings while connecting
     *   and counts itself only at Connected, so a list closed in between is caught here.
     */
    fun stagedScopeMayRun(startsAtEdit: Long?, startsNow: Long, startInProgress: Boolean): Boolean =
        startsAtEdit == null || startsNow != startsAtEdit || startInProgress

    /** The upstream keys a mode sets: `currentRouteId`, the per-app scope and `selectedProxy`. */
    data class Applied(val routeId: Long, val scope: PerAppScope, val server: Long)

    /** The scope as the TUN sees it: no list when per-app is off; the order of the packages does not matter. */
    private fun PerAppScope.normalized(): PerAppScope =
        if (!enabled) PerAppScope(false, true, emptyList()) else copy(packages = clean(packages).sorted())

    /**
     * What the mode card of the home screen shows: the mode actually in effect, read from the upstream keys, so a
     * routing profile picked in the classic interface shows as [ModeCard.CUSTOM] rather than as a mode that is not
     * running. [mode] is the stored choice; it only tells «Авто» from a mode picked by hand.
     */
    fun card(mode: TrafficMode, currentRouteId: Long, managed: Managed, scope: PerAppScope): ModeCard {
        val includeOnly = scope.enabled && !scope.bypass && clean(scope.packages).isNotEmpty()
        return when {
            managed.whitelistRoute > 0L && currentRouteId == managed.whitelistRoute ->
                if (mode == TrafficMode.AUTO) ModeCard.AUTO_WHITELIST else ModeCard.WHITELIST

            managed.smartRoute > 0L && currentRouteId == managed.smartRoute -> when {
                includeOnly -> ModeCard.APPS
                mode == TrafficMode.AUTO -> ModeCard.AUTO_SMART
                else -> ModeCard.SMART
            }

            else -> ModeCard.CUSTOM
        }
    }

    /** Package names as the lists keep them: trimmed, non-empty, once each. */
    fun clean(packages: List<String>): List<String> = packages.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** A list as DataStore keeps it (`individual`, `simple_apps_*`): one package per line. */
    fun parseList(text: String?): List<String> = clean(text.orEmpty().lines())
}

/**
 * The ids of the managed entities (design.md D5); 0 = missing (could not be created). [country] is the auto-selector
 * behind a country chosen in «Серверы» (batch 5), 0 until one was chosen.
 */
data class Managed(
    val smartRoute: Long,
    val whitelistRoute: Long,
    val auto: Long,
    val autoWhitelist: Long,
    val country: Long = 0L,
)
