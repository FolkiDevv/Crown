package io.nekohasekai.sagernet.simple.servers

import java.util.Locale

/** Where a server's country comes from (simple-ui spec "Определение страны сервера"). */
sealed interface ServerCountry {

    /** [code]: ISO 3166-1 alpha-2, upper case. [fromName]: found in the name, else the exit IP check's result. */
    data class Known(override val code: String, val fromName: Boolean) : ServerCountry

    /** Not in the name, and the exit IP check of this server is running. */
    data object Detecting : ServerCountry

    /** Neither the name nor an exit IP check names a country. */
    data object Unknown : ServerCountry

    /** The ISO code of a known country, null otherwise. */
    val code: String? get() = null
}

/**
 * The country of a server by its name: a flag emoji, then a country name in English or Russian, then an ISO code
 * written as a separate upper-case word («DE-1», «[NL]»); the earliest match in the name wins. When the name names
 * no country, the result of the exit IP check (`test_country` of the profile, written by IpTestRunner) stands in.
 * No Android types, so JVM unit tests cover it.
 */
object CountryFromName {

    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF

    /** ISO 3166-1 alpha-2, plus Kosovo's widely used XK. */
    private val ISO: Set<String> = Locale.getISOCountries().toHashSet().apply { add("XK") }

    /**
     * Two-letter words that mean something else in server names far more often than the country: transports and
     * protocols (WS, SS), «BS» for «БС» (whitelists), Cloudflare, Telegram, YouTube, ads, N/A, AI, US states and
     * cities (LA, GA, VA) and the like.
     */
    private val AMBIGUOUS_CODES = setOf(
        "AD", "AI", "AS", "BS", "BT", "CC", "CF", "GA", "GG", "ID", "LA", "MA", "MO", "MS", "NA", "NC", "PM", "PS", "RE",
        "SC", "SS", "ST", "TG", "TO", "TV", "VA", "WS", "YT",
    )

    /**
     * Names and common aliases in English and Russian of the countries VPN providers name their servers by; the
     * other countries are matched by their English and Russian names from the locale data ([generatedNames]).
     */
    private val NAMES: Map<String, String> = buildMap {
        fun add(code: String, vararg names: String) = names.forEach { put(it.lowercase(Locale.ROOT), code) }
        add("AE", "United Arab Emirates", "UAE", "Emirates", "Dubai", "ОАЭ", "Объединённые Арабские Эмираты", "Объединенные Арабские Эмираты", "Эмираты")
        add("AM", "Armenia", "Армения")
        add("AR", "Argentina", "Аргентина")
        add("AT", "Austria", "Австрия")
        add("AU", "Australia", "Австралия")
        add("AZ", "Azerbaijan", "Азербайджан")
        add("BE", "Belgium", "Бельгия")
        add("BG", "Bulgaria", "Болгария")
        add("BR", "Brazil", "Бразилия")
        add("BY", "Belarus", "Беларусь", "Белоруссия")
        add("CA", "Canada", "Канада")
        add("CH", "Switzerland", "Швейцария")
        add("CL", "Chile", "Чили")
        add("CN", "China", "Китай")
        add("CY", "Cyprus", "Кипр")
        add("CZ", "Czechia", "Czech Republic", "Czech", "Чехия")
        add("DE", "Germany", "Deutschland", "Германия")
        add("DK", "Denmark", "Дания")
        add("EE", "Estonia", "Эстония")
        add("EG", "Egypt", "Египет")
        add("ES", "Spain", "Испания")
        add("FI", "Finland", "Финляндия")
        add("FR", "France", "Франция")
        add("GB", "United Kingdom", "Great Britain", "Britain", "England", "UK", "Великобритания", "Британия", "Англия")
        add("GE", "Georgia", "Грузия")
        add("GR", "Greece", "Греция")
        add("HK", "Hong Kong", "Hongkong", "Гонконг")
        add("HR", "Croatia", "Хорватия")
        add("HU", "Hungary", "Венгрия")
        add("ID", "Indonesia", "Индонезия")
        add("IE", "Ireland", "Ирландия")
        add("IL", "Israel", "Израиль")
        add("IN", "India", "Индия")
        add("IR", "Iran", "Иран")
        add("IS", "Iceland", "Исландия")
        add("IT", "Italy", "Италия")
        add("JP", "Japan", "Япония")
        add("KG", "Kyrgyzstan", "Киргизия", "Кыргызстан")
        add("KR", "South Korea", "Korea", "Южная Корея", "Корея")
        add("KZ", "Kazakhstan", "Казахстан")
        add("LT", "Lithuania", "Литва")
        add("LU", "Luxembourg", "Люксембург")
        add("LV", "Latvia", "Латвия")
        add("MD", "Moldova", "Молдова", "Молдавия")
        add("MX", "Mexico", "Мексика")
        add("MY", "Malaysia", "Малайзия")
        add("NL", "Netherlands", "The Netherlands", "Holland", "Нидерланды", "Голландия")
        add("NO", "Norway", "Норвегия")
        add("NZ", "New Zealand", "Новая Зеландия")
        add("PH", "Philippines", "Филиппины")
        add("PL", "Poland", "Польша")
        add("PT", "Portugal", "Португалия")
        add("RO", "Romania", "Румыния")
        add("RS", "Serbia", "Сербия")
        add("RU", "Russia", "Russian Federation", "Россия", "РФ")
        add("SA", "Saudi Arabia", "Саудовская Аравия")
        add("SE", "Sweden", "Швеция")
        add("SG", "Singapore", "Сингапур")
        add("SI", "Slovenia", "Словения")
        add("SK", "Slovakia", "Словакия")
        add("TH", "Thailand", "Таиланд", "Тайланд")
        add("TR", "Turkey", "Türkiye", "Turkiye", "Турция")
        add("TW", "Taiwan", "Тайвань")
        add("UA", "Ukraine", "Украина")
        add("US", "United States", "United States of America", "USA", "America", "США", "Америка", "Соединённые Штаты", "Соединенные Штаты")
        add("UZ", "Uzbekistan", "Узбекистан")
        add("VN", "Vietnam", "Viet Nam", "Вьетнам")
        add("ZA", "South Africa", "ЮАР", "Южная Африка")
    }

    /** Locale names that are everyday words or first names more often than countries in a server name. */
    private val NAME_STOPLIST = setOf("того", "chad", "jersey", "мали", "чад")

    /** The English and Russian names of every other ISO country, from the locale data of the runtime. */
    private val generatedNames: Map<String, String> by lazy {
        val russian = Locale.forLanguageTag("ru")
        buildMap {
            for (code in ISO) {
                val country = Locale("", code)
                for (locale in listOf(Locale.ENGLISH, russian)) {
                    val name = country.getDisplayCountry(locale).trim().lowercase(Locale.ROOT)
                    // A name the runtime does not know comes back as the code itself.
                    if (name.length > 2 && name !in NAME_STOPLIST && name !in NAMES) put(name, code)
                }
            }
        }
    }

    /** The country [name] names, null when it names none. */
    fun detect(name: String): String? {
        if (name.isBlank()) return null
        flag(name)?.let { return it }
        var bestStart = Int.MAX_VALUE
        var bestLength = 0
        var bestCode: String? = null
        val lower = name.lowercase(Locale.ROOT)
        fun consider(start: Int, length: Int, code: String) {
            if (start < bestStart || start == bestStart && length > bestLength) {
                bestStart = start
                bestLength = length
                bestCode = code
            }
        }
        for (table in listOf(NAMES, generatedNames)) {
            for ((candidate, code) in table) {
                val start = wordIndex(lower, candidate)
                if (start >= 0) consider(start, candidate.length, code)
            }
        }
        isoCode(name)?.let { (start, code) -> consider(start, 2, code) }
        return bestCode
    }

    /**
     * The country of a server: the name first, then the exit IP check ([testCountry]); [detecting] while that check
     * of this server runs.
     */
    fun resolve(name: String, testCountry: String?, detecting: Boolean = false): ServerCountry {
        detect(name)?.let { return ServerCountry.Known(it, fromName = true) }
        val tested = testCountry.orEmpty().trim().uppercase(Locale.ROOT)
        if (tested in ISO) return ServerCountry.Known(tested, fromName = false)
        return if (detecting) ServerCountry.Detecting else ServerCountry.Unknown
    }

    /** The flag emoji of [code] (each letter shifted to its regional indicator), "" for anything but two letters. */
    fun flagOf(code: String): String {
        if (code.length != 2 || !code.all { it in 'A'..'Z' || it in 'a'..'z' }) return ""
        val upper = code.uppercase(Locale.ROOT)
        return String(Character.toChars(REGIONAL_A + (upper[0] - 'A'))) +
            String(Character.toChars(REGIONAL_A + (upper[1] - 'A')))
    }

    /** The country of the first flag emoji in [name] that is a country (🇪🇺 and 🇺🇳 are not), null when none is. */
    private fun flag(name: String): String? {
        var i = 0
        var previous = -1
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (cp in REGIONAL_A..REGIONAL_Z) {
                if (previous >= 0) {
                    val code = String(charArrayOf('A' + (previous - REGIONAL_A), 'A' + (cp - REGIONAL_A)))
                    if (code in ISO) return code
                    previous = -1
                } else {
                    previous = cp
                }
            } else {
                previous = -1
            }
            i += Character.charCount(cp)
        }
        return null
    }

    /** The first upper-case two-letter word of [name] that is an ISO code, with its position. */
    private fun isoCode(name: String): Pair<Int, String>? {
        var i = 0
        while (i < name.length) {
            if (!name[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < name.length && name[i].isLetter()) i++
            if (i - start != 2) continue
            val word = name.substring(start, i)
            if (word.all { it in 'A'..'Z' } && word in ISO && word !in AMBIGUOUS_CODES) return start to word
        }
        return null
    }

    /** The first position of [word] in [text] as a whole word (no letter right before or after it), -1 when none. */
    private fun wordIndex(text: String, word: String): Int {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return -1
            val end = at + word.length
            val before = at == 0 || !text[at - 1].isLetter()
            val after = end == text.length || !text[end].isLetter()
            if (before && after) return at
            from = at + 1
        }
    }
}
