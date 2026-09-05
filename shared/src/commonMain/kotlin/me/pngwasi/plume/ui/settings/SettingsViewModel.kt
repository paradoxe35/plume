package me.pngwasi.plume.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.data.TranslateSettings
import kotlin.coroutines.cancellation.CancellationException

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

/**
 * Everything the settings screens do that is the same on every platform.
 *
 * Open, so Android can add the keyboard-integration state no other platform has. The stores are
 * injected rather than built from a `Context`, which is what lets this live in common code.
 */
open class SettingsViewModel(
    protected val repository: SettingsRepository,
    protected val secrets: SecretStore,
    /** Lets Android mirror the theme where a cold start can read it synchronously. */
    private val onThemeChanged: (ThemeMode) -> Unit = {},
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe.asStateFlow()

    private val _models = MutableStateFlow<ModelsState>(ModelsState.Idle)
    val models: StateFlow<ModelsState> = _models.asStateFlow()

    /**
     * Which providers currently hold a key. Read from the encrypted store rather than settings, and
     * refreshed explicitly because a keychain is not a flow.
     */
    private val _keyed = MutableStateFlow<Set<String>>(emptySet())
    val keyedProviders: StateFlow<Set<String>> = _keyed.asStateFlow()

    private var modelJob: Job? = null

    init {
        viewModelScope.launch { refreshKeyed() }
    }

    protected suspend fun refreshKeyed() {
        val ids = repository.current().providers.keys
        _keyed.value = withContext(Dispatchers.IO) {
            ids.filterTo(mutableSetOf()) { secrets.hasKey(it) && secrets.getKey(it).isNotBlank() }
        }
    }

    /** A desktop keychain read is a subprocess, and a locked one prompts. Never on the UI thread. */
    suspend fun apiKey(providerId: String): String =
        withContext(Dispatchers.IO) { secrets.getKey(providerId) }

    fun setDefaultProvider(id: String) = viewModelScope.launch {
        repository.setDefaultProvider(id)
    }

    fun setActionProvider(action: Action, providerId: String?) = viewModelScope.launch {
        repository.setActionProvider(action, providerId)
    }

    fun saveProvider(id: String, config: ProviderConfig, apiKey: String?) = viewModelScope.launch {
        repository.putProvider(id, config)
        apiKey?.let { key -> withContext(Dispatchers.IO) { secrets.setKey(id, key) } }
        refreshKeyed()
    }

    fun deleteProvider(id: String) = viewModelScope.launch {
        repository.deleteProvider(id)
        withContext(Dispatchers.IO) { secrets.removeKey(id) }
        refreshKeyed()
    }

    fun updateRevise(transform: (ReviseSettings) -> ReviseSettings) = viewModelScope.launch {
        repository.update { it.copy(revise = transform(it.revise)) }
    }

    fun updateTranslate(transform: (TranslateSettings) -> TranslateSettings) = viewModelScope.launch {
        repository.update { it.copy(translate = transform(it.translate)) }
    }

    fun setProviderMentions(enabled: Boolean) = viewModelScope.launch {
        repository.update { it.copy(providerMentions = enabled) }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch {
        repository.update { it.copy(theme = mode) }
        onThemeChanged(mode)
    }

    fun toggleFavoriteLanguage(code: String) = viewModelScope.launch {
        repository.toggleFavoriteLanguage(code)
    }

    fun setDefaultTarget(code: String?) = viewModelScope.launch {
        repository.update { it.copy(translate = it.translate.copy(defaultTarget = code)) }
    }

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
            } catch (e: CancellationException) {
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
        _probe.value = withContext(Dispatchers.IO) {
            try {
                val engine = TextEngine(scoped, secrets)
                ProbeState.Ok(engine.translate("Bonjour", Languages.resolve("en").code).take(80))
            } catch (e: AiException) {
                ProbeState.Failed(e.message ?: "Failed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: "Failed")
            }
        }
    }

    fun clearProbe() {
        _probe.value = ProbeState.Idle
    }
}
