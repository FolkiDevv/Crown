package io.nekohasekai.sagernet.simple.modes

import java.util.regex.Pattern

/**
 * Servers for whitelists (traffic-modes spec "Распознавание серверов для белых списков"): a server is one when its
 * name holds one of the configured keywords as a whole word, ignoring case. There is no other way to mark a server.
 *
 * The patterns are plain Java regular expressions, the dialect the auto-selector's name and tier filters use
 * (AutoSelectorPlanner compiles them with CASE_INSENSITIVE | UNICODE_CASE and matches with `find`), so the managed
 * auto-selectors take them as they are.
 */
object WhitelistKeywords {

    /** The flags the auto-selector compiles its filters with; [isWhitelistServer] uses the same. */
    const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** Matches nothing: no keyword configured means no server for whitelists. */
    private const val NOTHING = "(?!)"

    /**
     * A pattern found in a name that holds any of [keywords] as a word: the keyword quoted, not preceded or followed
     * by a letter, so «БС» matches in «RU LTE обход БС» and «БС-2» but not inside «ОБСЕРВАТОРИЯ», and «LTE» not in
     * «SPLTEST». Digits and punctuation count as word boundaries («LTE2»).
     */
    fun regex(keywords: Collection<String>): String {
        val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (words.isEmpty()) return NOTHING
        return "(?<!\\p{L})(?:" + words.joinToString("|") { Pattern.quote(it) } + ")(?!\\p{L})"
    }

    /** A pattern found in exactly the names [regex] is not found in: the ordinary servers. */
    fun notRegex(keywords: Collection<String>): String = "(?s)^(?!.*" + regex(keywords) + ")"

    fun isWhitelistServer(name: String, keywords: Collection<String>): Boolean =
        Pattern.compile(regex(keywords), FLAGS).matcher(name).find()
}
