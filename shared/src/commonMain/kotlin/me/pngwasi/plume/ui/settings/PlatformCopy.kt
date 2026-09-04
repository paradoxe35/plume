package me.pngwasi.plume.ui.settings

import androidx.compose.ui.graphics.vector.ImageVector

/** One block of "How Plume works": a heading, then numbered steps, then prose. */
data class AboutSection(
    val title: String,
    val steps: List<String> = emptyList(),
    val paragraphs: List<String> = emptyList(),
)

/**
 * The few strings that can only be written per platform.
 *
 * Everything else in these screens is the same everywhere and stays shared. This is the exception:
 * "select text, then tap Revise in the selection toolbar" is true on Android, false on a desktop
 * where the same thing is a keyboard shortcut, and false on iOS where it is a keyboard. Shipping
 * Android's wording everywhere told Windows users to look for a toolbar that does not exist, and
 * blamed the Android Keystore for keys held by DPAPI.
 */
data class PlatformCopy(
    val about: List<AboutSection>,
    /** The one-line summary under "How Plume works" on the home screen. */
    val aboutSubtitle: String,
    /** What happens to the text once a result comes back. */
    val replacementNote: String,
    /** Under the API key field, where that key is actually kept. */
    val keyStorageNote: String,
    /** Set where the platform cannot deliver enough text for the limit to matter. */
    val characterLimitNote: String? = null,
    /** Where the theme applies beyond this window. */
    val themeNote: String,
    /** "Follow system" is a phone on a phone and a machine on a desktop. */
    val systemThemeIcon: ImageVector,
)

expect fun platformCopy(): PlatformCopy
