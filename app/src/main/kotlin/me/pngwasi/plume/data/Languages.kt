package me.pngwasi.plume.data

import java.util.Locale

/**
 * A translation target, identified by a BCP-47 tag.
 *
 * Display names come from the platform's ICU data rather than a bundled table, so they follow the
 * user's own locale and stay correct as ICU updates. [fallbackName] only covers tags ICU renders
 * as the raw code (older API levels, script-qualified tags).
 */
data class Language(
    val code: String,
    val fallbackName: String,
) {
    /** Name in the user's language, e.g. "German" for an English user, "Allemand" for a French one. */
    fun displayName(inLocale: Locale = Locale.getDefault()): String =
        resolve(inLocale) ?: fallbackName

    /** Name as its own speakers write it, e.g. "Deutsch". Used as the secondary line in the picker. */
    fun endonym(): String = resolve(locale()) ?: fallbackName

    /** The name handed to the model in the prompt. English keeps prompts stable across UI locales. */
    fun promptName(): String = resolve(Locale.ENGLISH) ?: fallbackName

    private fun locale(): Locale = Locale.forLanguageTag(code)

    private fun resolve(inLocale: Locale): String? {
        val name = locale().getDisplayName(inLocale)
        // ICU echoes the tag back when it has no data for it; treat that as a miss.
        if (name.isBlank() || name.equals(code, ignoreCase = true)) return null
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(inLocale) else it.toString() }
    }
}

object Languages {

    /**
     * Curated rather than exhaustive: the languages people actually translate between, kept short
     * enough that the picker stays scannable. Users reach the rest through search.
     */
    val all: List<Language> = listOf(
        Language("fr", "French"),
        Language("en", "English"),
        Language("es", "Spanish"),
        Language("de", "German"),
        Language("it", "Italian"),
        Language("pt", "Portuguese"),
        Language("pt-BR", "Portuguese (Brazil)"),
        Language("nl", "Dutch"),
        Language("pl", "Polish"),
        Language("ro", "Romanian"),
        Language("sv", "Swedish"),
        Language("da", "Danish"),
        Language("nb", "Norwegian"),
        Language("fi", "Finnish"),
        Language("is", "Icelandic"),
        Language("cs", "Czech"),
        Language("sk", "Slovak"),
        Language("hu", "Hungarian"),
        Language("el", "Greek"),
        Language("bg", "Bulgarian"),
        Language("uk", "Ukrainian"),
        Language("ru", "Russian"),
        Language("sr", "Serbian"),
        Language("hr", "Croatian"),
        Language("sl", "Slovenian"),
        Language("lt", "Lithuanian"),
        Language("lv", "Latvian"),
        Language("et", "Estonian"),
        Language("tr", "Turkish"),
        Language("ar", "Arabic"),
        Language("he", "Hebrew"),
        Language("fa", "Persian"),
        Language("ur", "Urdu"),
        Language("hi", "Hindi"),
        Language("bn", "Bengali"),
        Language("ta", "Tamil"),
        Language("te", "Telugu"),
        Language("mr", "Marathi"),
        Language("gu", "Gujarati"),
        Language("pa", "Punjabi"),
        Language("th", "Thai"),
        Language("vi", "Vietnamese"),
        Language("id", "Indonesian"),
        Language("ms", "Malay"),
        Language("tl", "Filipino"),
        Language("zh-Hans", "Chinese (Simplified)"),
        Language("zh-Hant", "Chinese (Traditional)"),
        Language("ja", "Japanese"),
        Language("ko", "Korean"),
        Language("sw", "Swahili"),
        Language("am", "Amharic"),
        Language("ha", "Hausa"),
        Language("yo", "Yoruba"),
        Language("ig", "Igbo"),
        Language("zu", "Zulu"),
        Language("af", "Afrikaans"),
        Language("mg", "Malagasy"),
        Language("rw", "Kinyarwanda"),
        Language("ln", "Lingala"),
        Language("ca", "Catalan"),
        Language("eu", "Basque"),
        Language("gl", "Galician"),
        Language("cy", "Welsh"),
        Language("ga", "Irish"),
        Language("sq", "Albanian"),
        Language("hy", "Armenian"),
        Language("ka", "Georgian"),
        Language("az", "Azerbaijani"),
        Language("kk", "Kazakh"),
        Language("uz", "Uzbek"),
        Language("mn", "Mongolian"),
        Language("ne", "Nepali"),
        Language("si", "Sinhala"),
        Language("km", "Khmer"),
        Language("lo", "Lao"),
        Language("my", "Burmese"),
        Language("eo", "Esperanto"),
        Language("la", "Latin"),
    )

    private val byCode: Map<String, Language> = all.associateBy { it.code.lowercase(Locale.ROOT) }

    fun find(code: String): Language? = byCode[code.trim().lowercase(Locale.ROOT)]

    /**
     * Resolves a stored code even if it left the catalog, so a saved favourite never turns into a
     * blank row after an app update.
     */
    fun resolve(code: String): Language = find(code) ?: Language(code, code)

    /** Matches on code, localised name and endonym so "Deutsch", "German" and "de" all land. */
    fun search(query: String, inLocale: Locale = Locale.getDefault()): List<Language> {
        val q = query.trim()
        if (q.isEmpty()) return all
        val needle = q.lowercase(Locale.ROOT)
        return all.filter {
            it.code.lowercase(Locale.ROOT).startsWith(needle) ||
                it.displayName(inLocale).lowercase(Locale.ROOT).contains(needle) ||
                it.endonym().lowercase(Locale.ROOT).contains(needle) ||
                it.fallbackName.lowercase(Locale.ROOT).contains(needle)
        }
    }

    /** Seeds a fresh install from the device locale, always leaving French and English reachable. */
    fun defaultFavorites(deviceLocale: Locale = Locale.getDefault()): List<String> {
        val device = find(deviceLocale.toLanguageTag()) ?: find(deviceLocale.language)
        return buildList {
            add("fr")
            add("en")
            device?.code?.let { if (it !in this) add(it) }
            if (size < 3) add("es")
        }
    }
}
