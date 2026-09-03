package me.pngwasi.plume.data

import java.util.Locale

internal actual fun localeDisplayName(languageTag: String, inLocaleTag: String): String? =
    Locale.forLanguageTag(languageTag)
        .getDisplayName(Locale.forLanguageTag(inLocaleTag))
        .takeIf { it.isNotBlank() }

actual fun currentLocaleTag(): String = Locale.getDefault().toLanguageTag()
