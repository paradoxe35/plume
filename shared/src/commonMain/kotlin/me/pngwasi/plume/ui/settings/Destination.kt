package me.pngwasi.plume.ui.settings

/**
 * The settings screens, as a plain stack.
 *
 * Navigation-Compose would add a dependency and a startup cost to what is a handful of screens with
 * no deep links and no arguments beyond a provider id, so the stack is held in state instead.
 */
sealed interface Destination {
    val title: String

    data object Home : Destination {
        override val title = "Plume"
    }

    data object Providers : Destination {
        override val title = "AI providers"
    }

    data class ProviderEdit(val providerId: String) : Destination {
        override val title = "Provider"
    }

    data object Revise : Destination {
        override val title = "Revise"
    }

    data object Translate : Destination {
        override val title = "Translate"
    }

    data object TranslatePrompt : Destination {
        override val title = "Translation prompt"
    }

    /** Android only: the companion keyboard's setup checklist. */
    data object Keyboard : Destination {
        override val title = "Plume keyboard"
    }

    /** Desktop only: shortcuts, permissions and launch behaviour. */
    data object Hotkeys : Destination {
        override val title = "Shortcuts"
    }

    /** Desktop only: what Plume changed, so the user can put it back. */
    data object History : Destination {
        override val title = "Recent changes"
    }

    /** Desktop only: the log, for when a background application stops working. */
    data object Diagnostics : Destination {
        override val title = "Logs"
    }

    data object Appearance : Destination {
        override val title = "Appearance"
    }

    data object About : Destination {
        override val title = "How Plume works"
    }
}
