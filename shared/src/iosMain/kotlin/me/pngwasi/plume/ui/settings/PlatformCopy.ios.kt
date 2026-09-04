package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.ui.icons.PlumeIcons

actual fun platformCopy(): PlatformCopy = PlatformCopy(
    about = listOf(
        AboutSection(
            title = "Using Plume",
            steps = listOf(
                "Switch to the Plume keyboard with the globe key, in whichever app you are typing in.",
                "Tap Revise or Translate on the Plume bar above the keys.",
                "Plume rewrites what is in the field and leaves the keyboard where it was.",
            ),
        ),
        AboutSection(
            title = "Why it is a keyboard",
            paragraphs = listOf(
                "iOS has no equivalent of an app that can reach into another app's text. A " +
                    "keyboard extension is the one place the system lets Plume both read what you " +
                    "typed and replace it, so that is where it lives.",
                "It only ever sees the field you are typing in, and only while the Plume keyboard " +
                    "is on screen.",
            ),
        ),
        AboutSection(
            title = "How much Plume can see",
            paragraphs = listOf(
                "iOS hands a keyboard only the text near the cursor — a few hundred characters, " +
                    "often cut at a sentence boundary — and there is no way to ask for the rest. " +
                    "Selecting the text you want is what makes the difference: a selection is " +
                    "given in full, and it is replaced exactly.",
                "Without a selection Plume works on what it was shown, which is what the panel " +
                    "previews before you tap. Anything further up the field is left untouched.",
            ),
        ),
        AboutSection(
            title = "If Plume does not appear",
            paragraphs = listOf(
                "Add it in Settings → General → Keyboard → Keyboards → Add New Keyboard, then " +
                    "choose Plume.",
                "Then open Plume under that list and turn on Allow Full Access. Without it iOS " +
                    "blocks the keyboard from reaching the network and the clipboard, so every " +
                    "action fails.",
            ),
        ),
        AboutSection(
            title = "Your data",
            paragraphs = listOf(
                "Your text goes to the AI provider you configured, and nowhere else. Plume has no " +
                    "backend and no analytics. API keys are held in the iOS Keychain and never " +
                    "leave the device.",
                "iOS asks before any app reads the clipboard, so Plume only reads it when you " +
                    "choose the clipboard action — never to decide whether to offer it.",
            ),
        ),
    ),
    aboutSubtitle = "What the keyboard can reach, and what it needs",
    replacementNote = "The result replaces what is in the field you are typing in.",
    keyStorageNote = "Held in the iOS Keychain.",
    characterLimitNote = "iOS hands a keyboard only a few hundred characters at a time, so this " +
        "limit rarely comes into play. Select the text you want instead.",
    themeNote = "The theme applies to this app and to the Plume keyboard.",
    systemThemeIcon = PlumeIcons.PhoneAndroid,
)
