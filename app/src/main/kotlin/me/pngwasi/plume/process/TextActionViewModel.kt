package me.pngwasi.plume.process

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pngwasi.plume.ai.AiException
import me.pngwasi.plume.ai.TextEngine
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository

sealed interface ActionState {
    /** Settings are loading. Usually too brief to see; it exists so nothing flashes. */
    data object Preparing : ActionState

    data class PickLanguage(
        val favorites: List<String>,
        val recents: List<String>,
    ) : ActionState

    data class Working(val note: String) : ActionState

    data class Done(val output: String) : ActionState

    data class Failed(val message: String, val settingsFix: Boolean) : ActionState
}

/**
 * Drives one invocation of Revise or Translate. Survives rotation so a slow call is not restarted
 * when the keyboard closes underneath the overlay.
 */
class TextActionViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository.get(app)
    private val secrets = SecretStore(app)

    private val _state = MutableStateFlow<ActionState>(ActionState.Preparing)
    val state: StateFlow<ActionState> = _state.asStateFlow()

    private var settings: AppSettings? = null
    private var running: Job? = null

    /** Set once a target is chosen, so Retry re-runs the same translation. */
    var chosenTarget: String? = null
        private set

    fun revise(text: String) {
        run("Revising") { engine -> engine.revise(text) }
    }

    fun translate(text: String, targetCode: String) {
        chosenTarget = targetCode
        viewModelScope.launch { repository.recordTranslationTarget(targetCode) }
        run("Translating") { engine -> engine.translate(text, targetCode) }
    }

    /**
     * Loads settings and either routes to the language picker or straight to translation when the
     * user pinned a default target.
     */
    fun prepareTranslation(text: String) {
        viewModelScope.launch {
            val loaded = settings ?: repository.current().also { settings = it }
            val preset = loaded.translate.defaultTarget
            if (!preset.isNullOrBlank()) {
                translate(text, preset)
            } else {
                _state.value = ActionState.PickLanguage(
                    favorites = loaded.translate.favorites,
                    recents = loaded.translate.recents,
                )
            }
        }
    }

    fun reopenPicker() {
        val loaded = settings ?: return
        running?.cancel()
        _state.value = ActionState.PickLanguage(
            favorites = loaded.translate.favorites,
            recents = loaded.translate.recents,
        )
    }

    private fun run(note: String, block: suspend (TextEngine) -> String) {
        running?.cancel()
        running = viewModelScope.launch {
            _state.value = ActionState.Working(note)
            val loaded = settings ?: runCatching { repository.current() }.getOrNull()
            if (loaded == null) {
                _state.value = ActionState.Failed("Could not read Plume settings.", settingsFix = true)
                return@launch
            }
            settings = loaded

            try {
                val engine = TextEngine(loaded, secrets)
                _state.value = ActionState.Done(block(engine))
            } catch (e: AiException) {
                _state.value = ActionState.Failed(
                    message = e.message ?: "Something went wrong.",
                    settingsFix = e.kind == AiException.Kind.NotConfigured ||
                        e.kind == AiException.Kind.Auth,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ActionState.Failed(
                    message = e.message ?: "Unexpected error.",
                    settingsFix = false,
                )
            }
        }
    }
}
