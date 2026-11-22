package io.nekohasekai.sagernet.simple.modes

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class WhitelistKeywordsTest {

    private val keywords = listOf("БС", "белые списки", "LTE", "WL", "white list")

    private fun isWl(name: String, words: List<String> = keywords) = WhitelistKeywords.isWhitelistServer(name, words)

    /** How the auto-selector's tier filter sees a name: the same flags, `find`. */
    private fun tierMatches(pattern: String, name: String) =
        Pattern.compile(pattern, WhitelistKeywords.FLAGS).matcher(name).find()

    @Test
    fun specExample() {
        assertTrue(isWl("RU LTE обход БС"))
    }

    @Test
    fun caseIsIgnored() {
        assertTrue(isWl("ru lte"))
        assertTrue(isWl("обход бс"))
        assertTrue(isWl("Сервер Бс"))
        assertTrue(isWl("БЕЛЫЕ СПИСКИ · Москва"))
        assertTrue(isWl("Germany wl"))
        assertTrue(isWl("Mts WHITE LIST"))
    }

    @Test
    fun cyrillicKeywordsMatchAsWords() {
        assertTrue(isWl("🇷🇺 Москва [БС]"))
        assertTrue(isWl("БС-2"))
        assertTrue(isWl("Москва БС2"))
        assertTrue(isWl("белые списки"))
        assertFalse(isWl("ОБСЕРВАТОРИЯ"))
        assertFalse(isWl("Обслуживание"))
        assertFalse(isWl("сервер РБС"))
    }

    @Test
    fun noFalseMatchesInsideWords() {
        assertFalse(isWl("SPLTEST 1"))
        assertFalse(isWl("Delta Netherlands"))
        assertFalse(isWl("Bowling"))
        assertFalse(isWl("WLAN Finland"))
        assertFalse(isWl("Germany 2"))
        assertFalse(isWl(""))
    }

    @Test
    fun digitsAndPunctuationAreBoundaries() {
        assertTrue(isWl("LTE1"))
        assertTrue(isWl("RU-LTE-1"))
        assertTrue(isWl("(WL) Kazan"))
        assertTrue(isWl("ru_lte"))
    }

    @Test
    fun regexMetacharactersAreEscaped() {
        val words = listOf("a.b", "c+", "[x]", "\\E(")
        assertTrue(isWl("server a.b", words))
        assertFalse(isWl("server axb", words))
        assertTrue(isWl("c+ 1", words))
        assertFalse(isWl("cc 1", words))
        assertTrue(isWl("srv [x]", words))
        assertFalse(isWl("srv x", words))
        assertTrue(isWl("srv \\E( 1", words))
    }

    @Test
    fun noKeywordsMatchNothing() {
        assertFalse(isWl("RU LTE обход БС", emptyList()))
        assertFalse(isWl("anything", listOf("  ", "")))
        assertTrue(tierMatches(WhitelistKeywords.notRegex(emptyList()), "RU LTE обход БС"))
    }

    @Test
    fun notRegexSelectsTheOrdinaryServers() {
        val ordinary = WhitelistKeywords.notRegex(keywords)
        val whitelist = WhitelistKeywords.regex(keywords)
        for (name in listOf("RU LTE обход БС", "Москва БС", "germany wl", "ru lte\nline two")) {
            assertTrue(name, tierMatches(whitelist, name))
            assertFalse(name, tierMatches(ordinary, name))
        }
        for (name in listOf("Germany 2", "ОБСЕРВАТОРИЯ", "SPLTEST", "WLAN", "")) {
            assertFalse(name, tierMatches(whitelist, name))
            assertTrue(name, tierMatches(ordinary, name))
        }
    }
}
