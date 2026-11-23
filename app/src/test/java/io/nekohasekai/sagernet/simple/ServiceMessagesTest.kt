package io.nekohasekai.sagernet.simple

import io.nekohasekai.sagernet.simple.ServiceMessages.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMessagesTest {

    // The strings as the app has them (values / values-ru), service_failed as BaseService joins it: "<it> <error>".
    private val ruleSets = Template(
        listOf("Could not download the rule-sets: %s", "Не удалось загрузить наборы правил: %s"),
        "Could not download the rule-sets: %s",
    )
    private val failed = Template(listOf("Failed:  %s", "Не удалось: %s"), "Failed:  %s")
    private val missingCategory = Template(
        listOf(
            "The Xray config \"%1\$s\" needs \"%2\$s\", but the installed %3\$s does not contain it.",
            "Конфигурации Xray «%1\$s» требуется «%2\$s», но в установленном файле %3\$s этого нет.",
        ),
        "The Xray config \"%1\$s\" needs \"%2\$s\", but the installed %3\$s does not contain it.",
    )
    private val someCategory = Template(listOf("a category", "некая категория"), "a category")
    private val templates = listOf(ruleSets, failed, missingCategory, someCategory)

    @Test
    fun messageOfTheOldLanguageIsBuiltAgainInTheCurrentOne() {
        val error = "geosite-ru: Get \"https://example.com/a.srs\": context deadline exceeded"
        assertEquals(
            "Could not download the rule-sets: $error",
            ServiceMessages.translate("Не удалось загрузить наборы правил: $error", templates),
        )
        assertEquals("Failed:  dial tcp: i/o timeout", ServiceMessages.translate("Не удалось: dial tcp: i/o timeout", templates))
    }

    @Test
    fun positionalArgumentsAndInnerStringsAreTranslated() {
        assertEquals(
            "The Xray config \"Мой сервер\" needs \"a category\", but the installed geosite.dat does not contain it.",
            ServiceMessages.translate(
                "Конфигурации Xray «Мой сервер» требуется «некая категория», но в установленном файле geosite.dat этого нет.",
                templates,
            ),
        )
    }

    @Test
    fun otherTextStaysAsItIs() {
        val core = "start service: initialize rule-set[2]: context deadline exceeded"
        assertEquals(core, ServiceMessages.translate(core, templates))
        // Already in the current language.
        assertEquals("Failed:  x", ServiceMessages.translate("Failed:  x", templates))
    }

    @Test
    fun matchKeepsArgumentOrder() {
        assertEquals(listOf("b", "a"), ServiceMessages.match("%2\$s then %1\$s", "a then b"))
        assertEquals(listOf("50"), ServiceMessages.match("%s%% done", "50% done"))
        assertNull(ServiceMessages.match("%s", "anything"))
    }

    @Test
    fun shortenCutsBetweenWords() {
        val text = "word ".repeat(30) + "geosite-ru-available-only-inside"
        val short = ServiceMessages.shorten(text, 100)
        assertTrue(short.endsWith("word…"))
        assertTrue(short.length <= 101)
        assertEquals("short", ServiceMessages.shorten("short", 100))
        // No break near the limit: cut at it rather than keep almost nothing.
        assertEquals("a".repeat(10) + "…", ServiceMessages.shorten("a".repeat(30), 10))
    }
}
