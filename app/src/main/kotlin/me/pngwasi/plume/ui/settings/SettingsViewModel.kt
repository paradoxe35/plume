package me.pngwasi.plume.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.pngwasi.plume.ai.AiException
import me.pngwasi.plume.ai.ModelCatalog
import me.pngwasi.plume.ai.TextEngine
import me.pngwasi.plume.data.Action
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.ProviderConfig
import me.pngwasi.plume.data.ReviseSettings
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeCache
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.data.TranslateSettings
import me.pngwasi.plume.ime.KeyboardComponent
import me.pngwasi.plume.ime.TypingKeyboard

/** Outcome of the "Test connection" button on a provider. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Ok(val sample: String) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/**
 * Live model list for the provider being edited.
 *
 * [Unavailable] is not an error state: plenty of gateways have no `/models` endpoint, and the user
 * simply types the model name instead.
 */
sealed interface ModelsState {
    data object Idle : ModelsState
    data object Loading : ModelsState
    data class Loaded(val models: List<String>) : ModelsState
    data class Unavailable(val reason: String) : ModelsState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository.get(app)
    private val secrets = SecretStore(app)

    /** `app` is a constructor parameter, not a property, so member functions go through this. */
    private val context: Application get() = getApplication()

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    private val _models = MutableStateFlow<ModelsState>(ModelsState.Idle)
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    /**
     * Which providers currently hold a key. Read from the encrypted store rather than settings, and
     * refreshed explicitly because SharedPreferences is not a flow.
     */
    private val _keyed = MutableStateFlow<Set<String>>(emptySet())
    val keyedProviders: StateFlow<Set<String>> = _keyed.asStateFlow()

    private var modelJob: Job? = null

    private val _keyboardStatus = MutableStateFlow(readKeyboardStatus())
    val keyboardStatus: StateFlow<KeyboardStatus> = _keyboardStatus.asStateFlow()

    init {
        viewModelScope.launch {
            refreshKeyed()
            reconcileKeyboardComponent()
        }
    }

    private fun readKeyboardStatus() = KeyboardStatus(
        available = KeyboardComponent.isAvailable(context),
        enabledInSystem = KeyboardComponent.isEnabledInSystem(context),
        isCurrent = KeyboardComponent.isCurrentInputMethod(context),
    )

    /**
     * The component's enabled state is what the system actually acts on, so it — not the stored
     * flag — is the truth. A restore to a new device brings settings across but not component
     * state, which would otherwise leave the toggle on and the keyboard missing.
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

    private suspend fun refreshKeyed() {
        val ids = repository.current().providers.keys
        _keyed.value = ids.filter { secrets.hasKey(it) && secrets.getKey(it).isNotBlank() }.toSet()
    }

    fun apiKey(providerId: String): String = secrets.getKey(providerId)

    fun setDefaultProvider(id: String) = viewModelScope.launch {
        repository.setDefaultProvider(id)
    }

    fun setActionProvider(action: Action, providerId: String?) = viewModelScope.launch {
        repository.setActionProvider(action, providerId)
    }

    fun saveProvider(id: String, config: ProviderConfig, apiKey: String?) = viewModelScope.launch {
        repository.putProvider(id, config)
        apiKey?.let { secrets.setKey(id, it) }
        refreshKeyed()
    }

    fun deleteProvider(id: String) = viewModelScope.launch {
        repository.deleteProvider(id)
        secrets.removeKey(id)
        refreshKeyed()
    }

    fun updateRevise(transform: (ReviseSettings) -> ReviseSettings) = viewModelScope.launch {
        repository.update { it.copy(revise = transform(it.revise)) }
    }

    fun updateTranslate(transform: (TranslateSettings) -> TranslateSettings) = viewModelScope.launch {
        repository.update { it.copy(translate = transform(it.translate)) }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        repository.update { it.copy(theme = mode) }
        // Mirrored immediately so the next overlay draws the new theme on its first frame.
        ThemeCache.write(context, mode)
    }

    fun toggleFavoriteLanguage(code: String) = viewModelScope.launch {
        repository.toggleFavoriteLanguage(code)
    }

    fun setDefaultTarget(code: String?) = viewModelScope.launch {
        repository.update { it.copy(translate = it.translate.copy(defaultTarget = code)) }
    }

    // --- model catalogue ---------------------------------------------------------------------

    /**
     * Loads the provider's model list. Called when the editor opens and whenever the key or base
     * URL changes, so the picker fills in as soon as the credentials become valid.
     */
    fun loadModels(config: ProviderConfig, apiKey: String) {
        if (config.baseUrl.isBlank()) {
            _models.value = ModelsState.Unavailable("Enter a base URL to load the model list.")
            return
        }
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            _models.value = ModelsState.Loading
            _models.value = try {
                val fetched = ModelCatalog.list(config, apiKey)
                if (fetched.isEmpty()) {
                    ModelsState.Unavailable("This provider returned no models. Type one instead.")
                } else {
                    ModelsState.Loaded(fetched)
                }
            } catch (e: AiException) {
                ModelsState.Unavailable(e.message ?: "Could not load models.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ModelsState.Unavailable("Could not load models. Type one instead.")
            }
        }
    }

    fun resetModels() {
        modelJob?.cancel()
        _models.value = ModelsState.Idle
    }

    // --- connection probe --------------------------------------------------------------------

    /**
     * Sends one tiny real request through the given provider. Cheaper and more honest than
     * validating the key format — it catches wrong base URLs and unavailable models too.
     */
    fun testConnection(providerId: String) = viewModelScope.launch {
        _probe.value = ProbeState.Running
        val current = repository.current()
        // Probe the provider being edited, not whichever one the actions happen to use.
        val scoped = current.copy(
            defaultProvider = providerId,
            reviseProvider = null,
            translateProvider = null,
        )
        _probe.value = try {
            val engine = TextEngine(scoped) { id -> secrets.getKey(id) }
            ProbeState.Ok(engine.translate("Bonjour", Languages.resolve("en").code).take(80))
        } catch (e: AiException) {
            ProbeState.Failed(e.message ?: "Failed")
        } catch (e: Exception) {
            ProbeState.Failed(e.message ?: "Failed")
        }
    }

    fun clearProbe() {
        _probe.value = ProbeState.Idle
    }
}
