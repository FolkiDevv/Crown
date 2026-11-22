package io.nekohasekai.sagernet.simple.servers

import java.util.Locale

/** Country names in the interface language, from the platform's locale data. */
object CountryLabels {

    /** The name of [code] in [locale]; the code itself when the platform does not know it. */
    fun name(code: String, locale: Locale): String =
        Locale("", code).getDisplayCountry(locale).takeIf { it.isNotBlank() } ?: code

    /** «🇩🇪 Германия». */
    fun label(code: String, locale: Locale): String =
        listOf(CountryFromName.flagOf(code), name(code, locale)).filter { it.isNotEmpty() }.joinToString(" ")
}
