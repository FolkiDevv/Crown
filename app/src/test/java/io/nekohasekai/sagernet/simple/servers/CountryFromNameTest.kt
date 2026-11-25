package io.nekohasekai.sagernet.simple.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryFromNameTest {

    private fun detect(name: String) = CountryFromName.detect(name)

    @Test
    fun flags() {
        assertEquals("DE", detect("🇩🇪 Germany 3"))
        assertEquals("NL", detect("Amsterdam 🇳🇱"))
        // The first flag is the country; a second one right after it is not read as a pair across flags.
        assertEquals("FI", detect("🇫🇮🇸🇪 Helsinki"))
        // 🇪🇺 is no country: the name decides.
        assertEquals("FR", detect("🇪🇺 France 1"))
        // The flag wins over a name that comes first.
        assertEquals("US", detect("Germany relay 🇺🇸"))
    }

    @Test
    fun englishAndRussianNames() {
        assertEquals("DE", detect("Germany 3"))
        assertEquals("DE", detect("germany-fra-01"))
        assertEquals("DE", detect("Германия 2"))
        assertEquals("NL", detect("Netherlands #4"))
        assertEquals("NL", detect("Нидерланды"))
        assertEquals("FI", detect("Финляндия | Хельсинки"))
        assertEquals("US", detect("United States - New York"))
        assertEquals("US", detect("США 1"))
        assertEquals("GB", detect("UK London"))
        assertEquals("GB", detect("Великобритания"))
        assertEquals("KR", detect("South Korea"))
        assertEquals("KR", detect("Южная Корея"))
        assertEquals("TR", detect("Turkey Istanbul"))
        assertEquals("TR", detect("Турция"))
        assertEquals("KZ", detect("Казахстан LTE"))
        assertEquals("AE", detect("ОАЭ Дубай"))
        assertEquals("RU", detect("Россия обход БС"))
        // A country the hand-made table does not list: the locale data.
        assertEquals("PE", detect("Peru 1"))
    }

    @Test
    fun isoCodes() {
        assertEquals("DE", detect("DE-1"))
        assertEquals("NL", detect("[NL] Amsterdam"))
        assertEquals("US", detect("US02 premium"))
        assertEquals("RU", detect("RU LTE обход БС"))
        assertEquals("SE", detect("Fast SE"))
    }

    @Test
    fun earliestMatchWins() {
        assertEquals("DE", detect("DE → NL"))
        assertEquals("NL", detect("Netherlands via Germany"))
        // The longer name at the same place: «South Korea», not «Korea» — both KR, and «United Kingdom» is GB.
        assertEquals("GB", detect("United Kingdom 2"))
    }

    @Test
    fun noCountry() {
        assertNull(detect("MyConfig"))
        assertNull(detect(""))
        assertNull(detect("   "))
        // Two-letter words that are protocols or services, not countries.
        assertNull(detect("vless WS TLS"))
        assertNull(detect("SS fast"))
        assertNull(detect("Server BS"))
        // Lower-case two-letter words are words, not codes.
        assertNull(detect("in the cloud"))
        assertNull(detect("it works"))
        // A country name inside a longer word is no match.
        assertNull(detect("Indianapolis"))
        assertNull(detect("Германиями"))
        // Russian «того» is a word, not Togo.
        assertNull(detect("для того"))
    }

    @Test
    fun ipCheckFallback() {
        assertEquals(ServerCountry.Known("DE", fromName = true), CountryFromName.resolve("🇩🇪 Germany 3", "FI"))
        assertEquals(ServerCountry.Known("FI", fromName = false), CountryFromName.resolve("MyConfig", "FI"))
        assertEquals(ServerCountry.Known("FI", fromName = false), CountryFromName.resolve("MyConfig", " fi "))
        assertEquals(ServerCountry.Unknown, CountryFromName.resolve("MyConfig", null))
        assertEquals(ServerCountry.Unknown, CountryFromName.resolve("MyConfig", ""))
        // Not a country (a lookup service's placeholder).
        assertEquals(ServerCountry.Unknown, CountryFromName.resolve("MyConfig", "XX"))
        assertEquals(ServerCountry.Detecting, CountryFromName.resolve("MyConfig", null, detecting = true))
        // A stored result stands while the check runs again.
        assertEquals(ServerCountry.Known("FI", fromName = false), CountryFromName.resolve("MyConfig", "FI", detecting = true))
    }

    @Test
    fun namesAsShown() {
        assertEquals("🇩🇪 Германия #2 · Nova", CountryFromName.tidy("  🇩🇪 Германия #2  ·  Nova "))
        assertEquals("Германия #2", CountryFromName.withoutLeadingFlag(" 🇩🇪 Германия #2 "))
        assertEquals("R5->Fl", CountryFromName.withoutLeadingFlag("🇫🇮R5->Fl"))
        assertEquals("💫 Белые списки", CountryFromName.withoutLeadingFlag("💫 Белые списки"))
        assertEquals("Amsterdam 🇳🇱", CountryFromName.withoutLeadingFlag("Amsterdam 🇳🇱"))
        // Nothing but a flag stays as it is.
        assertEquals("🇩🇪", CountryFromName.withoutLeadingFlag("🇩🇪"))
        assertEquals("", CountryFromName.withoutLeadingFlag("  "))
        assertEquals("🇵🇱 R5->Pl", CountryFromName.spacedFlag("🇵🇱R5->Pl"))
        assertEquals("🇩🇪 Германия #2", CountryFromName.spacedFlag("  🇩🇪  Германия #2"))
        assertEquals("MyConfig", CountryFromName.spacedFlag("MyConfig"))
        assertEquals("🇩🇪", CountryFromName.spacedFlag("🇩🇪"))
        assertTrue(CountryFromName.startsWithFlag(" 🇩🇪 Germany"))
        assertFalse(CountryFromName.startsWithFlag("Germany 🇩🇪"))
        assertFalse(CountryFromName.startsWithFlag(""))
    }

    @Test
    fun flagOf() {
        assertEquals("🇩🇪", CountryFromName.flagOf("DE"))
        assertEquals("🇳🇱", CountryFromName.flagOf("nl"))
        assertEquals("", CountryFromName.flagOf("DEU"))
        assertEquals("", CountryFromName.flagOf(""))
    }
}
