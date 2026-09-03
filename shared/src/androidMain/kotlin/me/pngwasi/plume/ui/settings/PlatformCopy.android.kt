package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.ui.icons.PlumeIcons

actual fun platformCopy(): PlatformCopy = PlatformCopy(
    about = listOf(
        AboutSection(
            title = "Using Plume",
            steps = listOf(
                "Select text in any app — WhatsApp, Messages, Gmail, your browser.",
                "Tap Revise or Translate in the selection toolbar. They may sit behind the ⋮ overflow.",
                "Revise replaces your text in place. Translate asks for a language first.",
            ),
        ),
        AboutSection(
            title = "Where text can be replaced",
            paragraphs = listOf(
                "When you select text you are writing — a message box, a compose field, a note — " +
                    "Plume hands the result straight back and it replaces your selection.",
                "When you select text you are only reading — a received message, a web page, a " +
                    "PDF — Android gives apps no way to write back. Plume shows the result and " +
                    "offers Copy instead. This is a platform limit, not a missing feature.",
                "Some apps report even their own input fields as read-only, so Plume offers Copy " +
                    "there too. The Plume keyboard can still replace text in those fields, because " +
                    "it works through a different channel.",
            ),
        ),
        AboutSection(
            title = "If the menu does not appear",
            paragraphs = listOf(
                "A few apps draw their own selection toolbar and ignore third-party actions. Most " +
                    "do not. Check the ⋮ overflow first — Android shows only a few actions inline.",
            ),
        ),
        AboutSection(
            title = "Your data",
            paragraphs = listOf(
                "Selected text goes to the AI provider you configured, and nowhere else. Plume has " +
                    "no backend and no analytics. API keys are encrypted with a key held in the " +
                    "Android Keystore and never leave the device.",
            ),
        ),
    ),
    aboutSubtitle = "Where the menu appears, and where it can't",
    themeNote = "The theme also applies to the overlays Plume shows on top of other apps.",
    systemThemeIcon = PlumeIcons.PhoneAndroid,
)
