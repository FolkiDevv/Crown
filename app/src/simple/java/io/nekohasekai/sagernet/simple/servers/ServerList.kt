package io.nekohasekai.sagernet.simple.servers

/** One server of the list: no protocol, address or port (simple-ui spec "Скрытие технических деталей"). */
data class ServerEntry(
    val id: Long,
    val groupId: Long,
    val name: String,
    /** The latest URL test: > 0 ms, < 0 failed, 0 not tested. */
    val latency: Int,
    val country: ServerCountry,
    val whitelist: Boolean,
    /** A subscription's placeholder (simple-ui "Сообщения подписки"): in no section, no check, no country. */
    val placeholder: Boolean = false,
    /** When [latency] was measured (epoch seconds, `latency_at`), 0 never. */
    val testedAt: Long = 0L,
)

/** A group as the list needs it, in the user's group order. */
data class GroupEntry(val id: Long, val name: String, val subscription: Boolean)

/**
 * The servers of one subscription in one country; [country] is [ServerCountry.Detecting] or [ServerCountry.Unknown]
 * for the servers whose country is not known (yet). Only a known country can be chosen as a whole.
 */
data class CountryRow(val groupId: Long, val country: ServerCountry, val servers: List<ServerEntry>) {
    val code: String? get() = country.code

    /** The best measured delay of the country's servers, null when none answered. */
    val bestLatency: Int? get() = servers.mapNotNull { s -> s.latency.takeIf { it > 0 } }.minOrNull()

    val key: String get() = "$groupId:${code ?: country.javaClass.simpleName}"
}

data class SubscriptionSection(val group: GroupEntry, val countries: List<CountryRow>)

/** The sections of «Серверы» (simple-ui spec "Структура списка серверов"), «Авто» aside. */
data class ServerSections(
    val subscriptions: List<SubscriptionSection>,
    /** «Мои серверы»: the servers of groups that are no subscription, flat, by name. */
    val mine: List<ServerEntry>,
    /** «Для белых списков»: every server recognised by the keywords, whatever its group, and in no other section. */
    val whitelist: List<ServerEntry>,
) {
    val isEmpty: Boolean get() = subscriptions.all { it.countries.isEmpty() } && mine.isEmpty() && whitelist.isEmpty()

    val allServers: List<ServerEntry>
        get() = subscriptions.flatMap { s -> s.countries.flatMap { it.servers } } + mine + whitelist
}

/** The pure layout of the server list; JVM unit tests cover it. */
object ServerList {

    /**
     * Lays [servers] out by [groups] (their order is kept; servers of a group not listed are left out). Countries are
     * ordered by [countryName] (the name in the interface language), the servers without a known country come last;
     * servers are ordered by name, numbers in names by value («Germany 2» before «Germany 10»).
     */
    fun build(
        groups: List<GroupEntry>,
        servers: List<ServerEntry>,
        countryName: (String) -> String,
    ): ServerSections {
        val real = servers.filter { !it.placeholder }
        val byGroup = real.groupBy { it.groupId }
        val whitelist = real.filter { it.whitelist && groups.any { g -> g.id == it.groupId } }.sortedWith(byName)
        val subscriptions = ArrayList<SubscriptionSection>()
        val mine = ArrayList<ServerEntry>()
        for (group in groups) {
            val members = byGroup[group.id].orEmpty().filter { !it.whitelist }
            if (!group.subscription) {
                mine += members
                continue
            }
            val known = members.filter { it.country is ServerCountry.Known }
                .groupBy { it.country.code!! }
                .map { (code, list) -> CountryRow(group.id, ServerCountry.Known(code, true), list.sortedWith(byName)) }
                .sortedWith(compareBy(NaturalOrder) { countryName(it.code!!) })
            val detecting = members.filter { it.country == ServerCountry.Detecting }
            val unknown = members.filter { it.country == ServerCountry.Unknown }
            val rows = known +
                listOfNotNull(
                    detecting.takeIf { it.isNotEmpty() }
                        ?.let { CountryRow(group.id, ServerCountry.Detecting, it.sortedWith(byName)) },
                    unknown.takeIf { it.isNotEmpty() }
                        ?.let { CountryRow(group.id, ServerCountry.Unknown, it.sortedWith(byName)) },
                )
            subscriptions += SubscriptionSection(group, rows)
        }
        return ServerSections(subscriptions, mine.sortedWith(byName), whitelist)
    }

    /** By the name as the row shows it: without its leading flag (the flag column shows that) and blanks. */
    /**
     * The names of [chosen] that none of [others] has (compared exactly), once each: the members a name filter can
     * admit without admitting a server it must not.
     */
    fun exclusiveNames(chosen: List<String>, others: List<String>): List<String> {
        val taken = others.toHashSet()
        return chosen.filter { it.isNotEmpty() && it !in taken }.distinct()
    }

    /**
     * An auto-selector name filter that matches exactly [names]. The selector compiles its filters case-insensitively
     * (CASE_INSENSITIVE | UNICODE_CASE) and finds them anywhere in the name; the anchors `\A`/`\z` and the inline `(?-iu:…)`
     * make it an exact, case-sensitive match, so «Edge» does not admit «edge» or «Edge 2». No name matches nothing.
     */
    fun exactNameFilter(names: List<String>): String {
        val distinct = names.filter { it.isNotEmpty() }.distinct()
        if (distinct.isEmpty()) return "(?!)"
        // \A and \z: the very start and end of the name (`$` would also match before a final line break).
        return "(?-iu:\\A(?:" + distinct.joinToString("|") { java.util.regex.Pattern.quote(it) } + ")\\z)"
    }

    private val byName: Comparator<ServerEntry> =
        compareBy<ServerEntry, String>(NaturalOrder) { CountryFromName.withoutLeadingFlag(it.name) }.thenBy { it.id }

    /** Case-insensitive, with runs of digits compared by value. */
    object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = i
                    val startB = j
                    while (i < a.length && a[i].isDigit()) i++
                    while (j < b.length && b[j].isDigit()) j++
                    val numA = a.substring(startA, i).trimStart('0')
                    val numB = b.substring(startB, j).trimStart('0')
                    if (numA.length != numB.length) return numA.length - numB.length
                    val c = numA.compareTo(numB)
                    if (c != 0) return c
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (c != 0) return c
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}

/** A country chosen in «Серверы»: the servers of [code] in subscription [groupId] (DataStore `simple_country_choice`). */
data class CountryChoice(val groupId: Long, val code: String) {

    fun encode(): String = "$groupId:$code"

    companion object {
        fun parse(text: String?): CountryChoice? {
            val (gid, code) = text?.split(':')?.takeIf { it.size == 2 } ?: return null
            val id = gid.toLongOrNull()?.takeIf { it > 0L } ?: return null
            return if (code.length == 2) CountryChoice(id, code.uppercase()) else null
        }
    }
}
