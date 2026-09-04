package me.pngwasi.plume.data

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localeIdentifier
import platform.Foundation.localizedStringForLanguageCode

internal actual fun localeDisplayName(languageTag: String, inLocaleTag: String): String? {
    val display = NSLocale(localeIdentifier = inLocaleTag.replace('-', '_'))
    // NSLocale resolves a language code, not a full tag, so a script-qualified target such as
    // zh-Hans falls back to the catalogue's own name rather than rendering as "zh".
    val name = display.localizedStringForLanguageCode(languageTag)
        ?: display.localizedStringForLanguageCode(languageTag.substringBefore('-'))
    return name?.takeIf { it.isNotBlank() }
}

actual fun currentLocaleTag(): String =
    NSLocale.currentLocale.localeIdentifier.replace('_', '-')
