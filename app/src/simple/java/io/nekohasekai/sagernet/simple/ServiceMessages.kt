package io.nekohasekai.sagernet.simple

import android.content.Context
import android.content.res.Configuration
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import java.util.Locale

/**
 * The service's own message of a failed start (BaseService.failRunner) in the language the interface shows now. The
 * service writes it once, in the language of its process at that time, and the home banner keeps it (DataStore
 * serviceError) until the next start: after a switch of the app's language it stayed in the old one. The messages
 * are built from a few known strings, so the text is matched against those strings in every language of the app and
 * built again from the current one; anything else (a core error) stays as it is.
 */
object ServiceMessages {

    /**
     * One message the service builds: its [variants] in every language (format strings), and the [target] format in
     * the current one.
     */
    class Template(val variants: Collection<String>, val target: String)

    /**
     * The format strings of BaseService's messages ([R.string.service_failed] is a prefix: `"<it> <error>"`), and the
     * Xray geo asset messages (XrayGeoAssets.describeFailure) with their own inner string.
     */
    private val FORMATS = intArrayOf(
        R.string.rule_set_deferred_failed,
        R.string.local_dns_failed,
        R.string.local_dns_failed_system,
        R.string.invalid_server,
        R.string.profile_empty,
        R.string.xray_geo_start_download_failed,
        R.string.xray_geo_missing_category,
        R.string.xray_geo_missing_file,
        R.string.xray_geo_some_category,
    )

    @Volatile
    private var cached: Pair<Locale, List<Template>>? = null

    /** [message] in the language of [context] where it is one of the service's own messages. Not on the main thread. */
    fun localize(context: Context, message: String): String = try {
        translate(message, templates(context))
    } catch (e: Exception) {
        Logs.w(e)
        message
    }

    private fun templates(context: Context): List<Template> {
        val locale = context.resources.configuration.locales[0]
        cached?.let { (at, templates) -> if (at == locale) return templates }
        val resources = (context.assets.locales.mapNotNull { tag -> tag.takeIf { it.isNotEmpty() } } + "en")
            .distinct()
            .map { tag ->
                val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
                context.createConfigurationContext(configuration).resources
            }

        fun variants(id: Int, prefix: Boolean): Set<String> =
            resources.mapTo(LinkedHashSet()) { res -> res.getString(id).let { if (prefix) "$it %s" else it } }

        val templates = FORMATS.map { id -> Template(variants(id, false), context.getString(id)) } +
            Template(variants(R.string.service_failed, true), context.getString(R.string.service_failed) + " %s")
        cached = locale to templates
        return templates
    }

    /**
     * [message] built again from the [Template.target] of the template one of whose variants it matches, with its
     * arguments translated in turn ([depth] levels: a geo asset message carries «a category» inside); else as it is.
     */
    fun translate(message: String, templates: List<Template>, depth: Int = 2): String {
        if (depth < 0) return message
        for (template in templates) {
            for (variant in template.variants) {
                if (variant == template.target) continue
                val args = match(variant, message) ?: continue
                val translated = args.map { translate(it, templates, depth - 1) }
                return try {
                    String.format(Locale.ROOT, template.target, *translated.toTypedArray())
                } catch (_: IllegalArgumentException) {
                    message
                }
            }
        }
        return message
    }

    /**
     * [text] of at most about [max] characters: cut after the last space, line break or separator before the limit
     * (not inside a word or an address), with an ellipsis; as it is when short enough.
     */
    fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val cut = head.indexOfLast { it.isWhitespace() || it == '|' || it == ',' || it == ';' }
        // No break at all in the last part: cut at the limit rather than keep almost nothing.
        val end = if (cut >= max / 2) cut else max
        return head.substring(0, end).trimEnd(' ', '\n', '|', ',', ';') + "…"
    }

    /** A format specifier: `%s`, `%d`, `%1$s`; `%%` is a percent sign. */
    private val SPECIFIER = Regex("%(?:(\\d+)\\$)?([sd%])")

    /** The arguments of [message] built from [format], in argument order; null when it was not built from it. */
    internal fun match(format: String, message: String): List<String>? {
        val pattern = StringBuilder()
        val order = mutableListOf<Int>()
        var next = 1
        var end = 0
        var specifiers = 0
        for (m in SPECIFIER.findAll(format)) {
            pattern.append(Regex.escape(format.substring(end, m.range.first)))
            end = m.range.last + 1
            if (m.groupValues[2] == "%") {
                pattern.append("%")
                continue
            }
            order += m.groupValues[1].toIntOrNull() ?: next++
            pattern.append(if (m.groupValues[2] == "d") "(-?\\d+)" else "(.*?)")
            specifiers++
        }
        pattern.append(Regex.escape(format.substring(end)))
        // A format without arguments matches only itself; an empty literal part would match anything.
        if (specifiers > 0 && format.length - specifiers * 2 < 4) return null
        val result = Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL).matchEntire(message) ?: return null
        val args = arrayOfNulls<String>(order.maxOrNull() ?: 0)
        order.forEachIndexed { index, position -> args[position - 1] = result.groupValues[index + 1] }
        return args.map { it.orEmpty() }
    }
}
