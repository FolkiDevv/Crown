package io.nekohasekai.sagernet.simple.add

import io.nekohasekai.sagernet.simple.servers.CountryFromName

/** The counts in the summary of an added subscription (simple-ui "Сводка импорта"), apart from the database. */
object ImportCounts {

    /** A profile of the subscription as the summary reads it: its stored name, without its outbound data. */
    data class Row(val id: Long, val name: String, val testCountry: String?)

    /**
     * The servers among [rows]: a managed auto-selector ([managed], read once for all rows) stands in for no server of
     * its own.
     */
    fun servers(rows: List<Row>, managed: Set<Long>): List<Row> = rows.filter { it.id !in managed }

    /** The distinct countries of [servers], told by their names, else by their exit IP check. */
    fun countries(servers: List<Row>): Int =
        servers.mapNotNullTo(HashSet()) { CountryFromName.resolve(it.name, it.testCountry).code }.size
}
