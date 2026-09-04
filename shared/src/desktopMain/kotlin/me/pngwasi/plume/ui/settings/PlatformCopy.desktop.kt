package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.data.DesktopOs
import me.pngwasi.plume.desktop.MAX_HISTORY
import me.pngwasi.plume.ui.icons.PlumeIcons

actual fun platformCopy(): PlatformCopy = desktopCopy(DesktopOs.current)

/**
 * One desktop, three sets of facts.
 *
 * The shortcut mechanism is the same everywhere, but what stands in its way is not: macOS refuses
 * to deliver key presses without Accessibility, Wayland refuses without group membership, and
 * Windows asks for nothing. Where the keys are kept differs too. Someone reading this page is
 * usually reading it because something did not work, so it has to describe their machine rather
 * than desktops in general.
 *
 * The operating system is a parameter rather than read from [DesktopOs.current] inside, so all
 * three can be tested from one machine — the alternative is finding out on the macOS runner.
 */
internal fun desktopCopy(os: DesktopOs): PlatformCopy = PlatformCopy(
    about = listOf(
        AboutSection(
            title = "Using Plume",
            steps = listOf(
                "Select text in any application — an email, a document, a chat, a form field.",
                "Press a Plume shortcut. The defaults are listed under Shortcuts.",
                "Plume replaces the selection where it stands, and puts your clipboard back as it was.",
            ),
        ),
        AboutSection(
            title = "Plume keeps running",
            paragraphs = listOf(
                "The shortcuts only work while Plume is running, so closing this window does not " +
                    "quit it. Use Quit Plume, below the settings.",
                when (os) {
                    DesktopOs.MacOs -> "It waits in the menu bar."
                    DesktopOs.Windows ->
                        "It waits in the notification area. Windows hides new icons there behind " +
                            "the ⌃ arrow until you drag them out."
                    DesktopOs.Linux ->
                        "It waits in the tray. GNOME ships no tray by default, so without one " +
                            "Plume keeps this window rather than vanishing with no way back."
                },
            ),
        ),
        AboutSection(
            title = "If the shortcuts do nothing",
            paragraphs = when (os) {
                DesktopOs.MacOs -> listOf(
                    "macOS will not deliver key presses from other applications until you allow " +
                        "it: System Settings → Privacy & Security → Accessibility, then switch " +
                        "Plume on. The permission follows the copy you allowed, so moving or " +
                        "replacing the app means granting it again.",
                )

                DesktopOs.Windows -> listOf(
                    "Windows needs no permission, so the usual cause is another application " +
                        "already holding the combination. Anything refused is listed at the top " +
                        "of the Shortcuts page — give it a different one.",
                    "Plume also cannot type into a window running as administrator unless it is " +
                        "running as administrator too. There the shortcut fires and the " +
                        "replacement never arrives.",
                )

                DesktopOs.Linux -> listOf(
                    "On X11 they work as they are. On Wayland the compositor will not let an " +
                        "ordinary application watch the keyboard, so Plume reads the input devices " +
                        "directly, which needs your user in the input group: run " +
                        "sudo usermod -aG input \$USER, then log out and back in.",
                    "Combinations your desktop has already claimed are refused rather than shared. " +
                        "Anything refused is listed at the top of the Shortcuts page.",
                )
            },
        ),
        AboutSection(
            title = "Your data",
            paragraphs = listOf(
                "Selected text goes to the AI provider you configured, and nowhere else. Plume has " +
                    "no backend and no analytics.",
                when (os) {
                    DesktopOs.MacOs ->
                        "API keys are kept in your login keychain and never leave the machine."
                    DesktopOs.Windows ->
                        "API keys are encrypted with DPAPI, which ties them to your Windows " +
                            "account, and never leave the machine."
                    DesktopOs.Linux ->
                        "API keys are kept in your keyring through the Secret Service — GNOME " +
                            "Keyring or KWallet on most systems. With no keyring, Plume falls back " +
                            "to an encrypted file in its own configuration directory."
                },
                "Recent changes keeps the last $MAX_HISTORY originals so you can put one back. It " +
                    "is held in memory only and is gone when Plume quits. The log records what " +
                    "happened, never what you wrote.",
            ),
        ),
    ),
    aboutSubtitle = "Shortcuts, permissions and where your keys are kept",
    replacementNote = "The result replaces your selection where it stands, and your clipboard is " +
        "put back as it was.",
    keyStorageNote = when (os) {
        DesktopOs.MacOs -> "Kept in your login keychain."
        DesktopOs.Windows -> "Encrypted with DPAPI, tied to your Windows account."
        DesktopOs.Linux -> "Kept in your keyring, or an encrypted file where no keyring answers."
    },
    themeNote = "The theme applies to this window. The tray icon follows your desktop instead.",
    systemThemeIcon = PlumeIcons.Settings,
)
