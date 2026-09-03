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
                    "is the one on screen.",
            ),
        ),
        AboutSection(
            title = "If Plume does not appear",
            paragraphs = listOf(
                "Add it in Settings → General → Keyboard → Keyboards → Add New Keyboard, then " +
                    "choose Plume.",
                "Then open Plume under that list and turn on Allow Full Access. Without it iOS " +
                    "blocks the keyboard from reaching the network, so the provider can never be " +
                    "called and every action fails.",
            ),
        ),
        AboutSection(
            title = "Your data",
            paragraphs = listOf(
                "Your text goes to the AI provider you configured, and nowhere else. Plume has no " +
                    "backend and no analytics. API keys are held in the iOS Keychain and never " +
                    "leave the device.",
            ),
        ),
    ),
    aboutSubtitle = "What the keyboard can reach, and what it needs",
    themeNote = "The theme applies to this app and to the Plume keyboard.",
    systemThemeIcon = PlumeIcons.PhoneAndroid,
)
