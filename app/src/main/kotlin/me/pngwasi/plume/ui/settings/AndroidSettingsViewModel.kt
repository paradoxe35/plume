package me.pngwasi.plume.ui.settings

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.PlumeStores
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeCache
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ime.KeyboardComponent
import me.pngwasi.plume.ime.TypingKeyboard

/**
 * [SettingsViewModel] plus the companion-keyboard state, which exists only on Android.
 *
 * Everything else — providers, prompts, models, the connection probe — is inherited, so the
 * settings screens are the same code on every platform.
 */
class AndroidSettingsViewModel(
    application: Application,
    repository: SettingsRepository = PlumeStores.settings(application),
    secrets: SecretStore = PlumeStores.secrets(application),
) : SettingsViewModel(
    repository = repository,
    secrets = secrets,
    onThemeChanged = { mode -> ThemeCache.write(application, mode) },
) {

    private val context: Application = application

    private val _keyboardStatus = MutableStateFlow(readKeyboardStatus())
    val keyboardStatus: StateFlow<KeyboardStatus> = _keyboardStatus.asStateFlow()

    init {
        viewModelScope.launch { reconcileKeyboardComponent() }
    }

    private fun readKeyboardStatus() = KeyboardStatus(
        available = KeyboardComponent.isAvailable(context),
        enabledInSystem = KeyboardComponent.isEnabledInSystem(context),
        isCurrent = KeyboardComponent.isCurrentInputMethod(context),
    )

    /**
     * The component's enabled state is what the system acts on, so it — not the stored flag — is
     * the truth. A restore to a new device brings settings across but not component state, which
     * would otherwise leave the toggle on and the keyboard missing.
     */
    private suspend fun reconcileKeyboardComponent() {
        val wanted = repository.current().keyboardEnabled
        if (KeyboardComponent.isAvailable(context) != wanted) {
            KeyboardComponent.setAvailable(context, wanted)
        }
        _keyboardStatus.value = readKeyboardStatus()
    }

    /** System state changes outside the app, so it is re-read whenever the screen is shown. */
    fun refreshKeyboardStatus() {
        // Opening Plume usually happens while the user's own keyboard is selected — the moment to
        // note where the panel's "Keyboard" button should return to.
        TypingKeyboard.noteCurrent(context)
        _keyboardStatus.value = readKeyboardStatus()
    }

    fun setKeyboardEnabled(enabled: Boolean) = viewModelScope.launch {
        KeyboardComponent.setAvailable(context, enabled)
        repository.update { it.copy(keyboardEnabled = enabled) }
        _keyboardStatus.value = readKeyboardStatus()
    }

    fun showKeyboardPicker() {
        // They are on their own keyboard right now and about to switch away from it.
        TypingKeyboard.noteCurrent(context)
        KeyboardComponent.showPicker(context)
    }
}
