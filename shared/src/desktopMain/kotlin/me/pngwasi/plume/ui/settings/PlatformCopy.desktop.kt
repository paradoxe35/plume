package me.pngwasi.plume.ui.settings

import me.pngwasi.plume.data.DesktopOs
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
                when (os) {
                    DesktopOs.MacOs ->
                        "Closing this window leaves Plume in the menu bar, because the shortcuts " +
                            "are the point and they only work while it is running. Quit from the " +
                            "menu bar, or with Quit Plume below the settings."
                    DesktopOs.Windows ->
                        "Closing this window leaves Plume in the notification area, because the " +
                            "shortcuts are the point and they only work while it is running. " +
                            "Windows hides new tray icons behind the ⌃ arrow until you drag them out."
                    DesktopOs.Linux ->
                        "Closing this window leaves Plume in the tray, because the shortcuts are " +
                            "the point and they only work while it is running. GNOME ships no tray " +
                            "by default: without one Plume keeps this window, so that it cannot " +
                            "disappear with no way back to it."
                },
            ),
        ),
        AboutSection(
            title = "If the shortcuts do nothing",
            paragraphs = when (os) {
                DesktopOs.MacOs -> listOf(
                    "macOS will not deliver key presses from other applications until you allow " +
                        "it. Open System Settings → Privacy & Security → Accessibility and switch " +
                        "Plume on.",
                    "The permission is granted to the copy of Plume you allowed. Moving or " +
                        "replacing the application means granting it again.",
                )

                DesktopOs.Windows -> listOf(
                    "Windows needs no permission for this, so the usual cause is another " +
                        "application already holding the same combination. Anything that was " +
                        "refused is listed at the top of the Shortcuts page — give it a different " +
                        "one.",
                    "Plume also cannot send keys to a window running as administrator unless Plume " +
                        "is running as administrator too. There the shortcut is received and the " +
                        "replacement never arrives.",
                )

                DesktopOs.Linux -> listOf(
                    "On X11 they work as they are. On Wayland the compositor will not let an " +
                        "ordinary application watch the keyboard, so Plume reads the input devices " +
                        "directly — and that needs your user in the input group: run " +
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
                            "Keyring or KWallet on most systems. Where no keyring answers, Plume " +
                            "falls back to an encrypted file in its own configuration directory."
                },
                "What Plume replaced is kept under Recent changes so you can put it back. That " +
                    "list holds the last twenty changes, in memory only, and is gone once Plume " +
                    "quits. The log records what happened and never what you wrote.",
            ),
        ),
    ),
    aboutSubtitle = "Shortcuts, permissions and where your keys are kept",
    themeNote = "The theme applies to this window. The tray icon follows your desktop instead.",
    systemThemeIcon = PlumeIcons.Settings,
)
