package io.nekohasekai.sagernet.simple.servers

import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.simple.modes.WhitelistKeywords
import java.util.regex.Pattern

/**
 * Reads the groups and servers «Серверы» shows from the database (blocking: call it off the main thread). Auto-selectors
 * are left out (the simple interface has its own «Авто»), and so are archived groups.
 */
object ServerCatalog {

    class Snapshot(val groups: List<ProxyGroup>, val entries: List<GroupEntry>, val servers: List<ServerEntry>)

    /**
     * [detecting]: servers whose exit IP check runs now; [unnamed] stands in for a server without a name (its address
     * is technical detail the simple interface does not show).
     */
    fun load(keywords: List<String>, detecting: Set<Long>, unnamed: String): Snapshot {
        val whitelist = whitelistPattern(keywords)
        val groups = GroupRepo.all().filter { !it.archive }
        val entries = groups.map { GroupEntry(it.id, it.displayName(), it.isSubscription) }
        val servers = groups.flatMap { group ->
            SagerDatabase.proxyDao.getByGroup(group.id).filter { it.type != AutoSelectorProfiles.TYPE }.map { profile ->
                val name = nameOf(profile, unnamed)
                ServerEntry(
                    id = profile.id,
                    groupId = group.id,
                    name = name,
                    latency = profile.latency,
                    country = CountryFromName.resolve(nameOf(profile), profile.testCountry, profile.id in detecting),
                    whitelist = whitelist.matcher(nameOf(profile)).find(),
                )
            }
        }
        return Snapshot(groups, entries, servers)
    }

    /**
     * The names of [choice]'s servers as the auto-selector matches them (the outbound's display name): the servers of
     * that subscription in that country, none for whitelists.
     */
    fun countryMembers(choice: CountryChoice, keywords: List<String>): List<String> {
        val whitelist = whitelistPattern(keywords)
        return SagerDatabase.proxyDao.getByGroup(choice.groupId)
            .filter { it.type != AutoSelectorProfiles.TYPE }
            .filter { !whitelist.matcher(nameOf(it)).find() }
            .filter { CountryFromName.resolve(nameOf(it), it.testCountry).code == choice.code }
            .map { it.outbound.displayName() }
    }

    /** The name the user gave the server, "" when none. */
    private fun nameOf(profile: ProxyEntity): String = profile.outbound.name.trim()

    /** The name as the simple interface shows it: tidied, [unnamed] for none (never the address). */
    fun nameOf(profile: ProxyEntity, unnamed: String): String = CountryFromName.tidy(profile.outbound.name).ifEmpty { unnamed }

    private fun whitelistPattern(keywords: List<String>): Pattern =
        Pattern.compile(WhitelistKeywords.regex(keywords), WhitelistKeywords.FLAGS)
}
