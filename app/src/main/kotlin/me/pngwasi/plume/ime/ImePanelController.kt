package me.pngwasi.plume.ime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pngwasi.plume.ai.AiException
import me.pngwasi.plume.ai.TextEngine
import me.pngwasi.plume.data.AppSettings

/** What the action will be applied to, surfaced so the user is never surprised by the scope. */
enum class ActionScope { Selection, WholeField }

sealed interface ImeState {
    /** Waiting for the user. [scope] is null when there is nothing usable in the field. */
    data class Ready(
        val scope: ActionScope?,
        val preview: String,
        val confirmation: String? = null,
    ) : ImeState

    data class PickLanguage(
        val favorites: List<String>,
        val recents: List<String>,
    ) : ImeState

    data class Working(val note: String) : ImeState

    data class Failed(
        val message: String,
        val settingsFix: Boolean,
        val retry: Retry?,
    ) : ImeState

    /** What a Retry button should re-run. */
    sealed interface Retry {
        data object Revise : Retry
        data class Translate(val code: String) : Retry
    }
}

/**
 * Drives the keyboard panel: reads the field, runs an action, writes the result back.
 *
 * Deliberately free of Android UI types — the service owns the view, this owns the behaviour, and
 * the split is what makes the flow testable against a fake editor and a local HTTP server.
 */
class ImePanelController(
    private val scope: CoroutineScope,
    private val bridge: EditorBridge,
    private val loadSettings: suspend () -> AppSettings,
    private val apiKeyFor: (String) -> String,
    private val onTargetUsed: suspend (String) -> Unit = {},
) {

    private val _state = MutableStateFlow<ImeState>(ImeState.Ready(null, ""))
    val state: StateFlow<ImeState> = _state.asStateFlow()

    private var running: Job? = null
    private var cached: AppSettings? = null

    /**
     * Re-reads the field. Called whenever the panel is shown or the input target changes, so the
     * scope indicator always describes the field the user is actually looking at.
     */
    fun refresh(confirmation: String? = null) {
        running?.cancel()
        _state.value = readyState(confirmation)
    }

    private fun readyState(confirmation: String? = null): ImeState.Ready {
        val text = bridge.read()
        if (text == null || text.isEmpty) {
            return ImeState.Ready(scope = null, preview = "", confirmation = confirmation)
        }
        return ImeState.Ready(
            scope = if (text.hasSelection) ActionScope.Selection else ActionScope.WholeField,
            preview = text.target.collapseWhitespace(),
            confirmation = confirmation,
        )
    }

    fun revise() = launchAction("Revising", ImeState.Retry.Revise) { engine, text ->
        engine.revise(text)
    }

    /** Opens the language picker, or goes straight to translating when a default target is pinned. */
    fun startTranslate() {
        running?.cancel()
        running = scope.launch {
            val settings = settingsOrNull() ?: return@launch
            val preset = settings.translate.defaultTarget
            if (preset.isNullOrBlank()) {
                _state.value = ImeState.PickLanguage(
                    favorites = settings.translate.favorites,
                    recents = settings.translate.recents,
                )
            } else {
                // Executed inline rather than delegating to translate(), which would cancel the very
                // job this is running in and leave the action orphaned from `running`.
                recordTarget(preset)
                execute("Translating", ImeState.Retry.Translate(preset)) { engine, text ->
                    engine.translate(text, preset)
                }
            }
        }
    }

    fun translate(code: String) {
        launchAction("Translating", ImeState.Retry.Translate(code)) { engine, text ->
            recordTarget(code)
            engine.translate(text, code)
        }
    }

    /** Leaves the picker without running anything. */
    fun cancelPicker() = refresh()

    private suspend fun recordTarget(code: String) {
        runCatching { onTargetUsed(code) }
    }

    /** Visible for tests: the action currently in flight, so a test can await it. */
    internal val inFlight: Job? get() = running

    private fun launchAction(
        note: String,
        retry: ImeState.Retry,
        block: suspend (TextEngine, String) -> String,
    ) {
        running?.cancel()
        running = scope.launch { execute(note, retry, block) }
    }

    private suspend fun execute(
        note: String,
        retry: ImeState.Retry,
        block: suspend (TextEngine, String) -> String,
    ) {
        val text = bridge.read()
        if (text == null || text.isEmpty) {
            _state.value = ImeState.Failed(
                message = "There is no text in this field yet.",
                settingsFix = false,
                retry = null,
            )
            return
        }

        _state.value = ImeState.Working(note)
        val settings = settingsOrNull() ?: return

        try {
            val engine = TextEngine(settings, apiKeyFor)
            val result = block(engine, text.target)
            // Re-read before writing: the user may have kept typing while the call was in flight,
            // and replacing a field we no longer understand would destroy their edit.
            val latest = bridge.read()
            if (latest == null || latest.target != text.target) {
                _state.value = ImeState.Failed(
                    message = "The text changed while Plume was working. Try again.",
                    settingsFix = false,
                    retry = retry,
                )
                return
            }
            val applied = bridge.apply(result)
            _state.value = if (applied) {
                readyState(confirmation = if (retry is ImeState.Retry.Revise) "Revised" else "Translated")
            } else {
                ImeState.Failed("Could not write back to this field.", false, retry)
            }
        } catch (e: AiException) {
            _state.value = ImeState.Failed(
                message = e.message ?: "Something went wrong.",
                settingsFix = e.kind == AiException.Kind.NotConfigured ||
                    e.kind == AiException.Kind.Auth,
                retry = retry,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = ImeState.Failed(e.message ?: "Unexpected error.", false, retry)
        }
    }

    private suspend fun settingsOrNull(): AppSettings? {
        cached?.let { return it }
        return runCatching { loadSettings() }
            .onSuccess { cached = it }
            .onFailure {
                _state.value = ImeState.Failed("Could not read Plume settings.", true, null)
            }
            .getOrNull()
    }

    /** Settings may have changed while the panel was hidden. */
    fun invalidateSettings() {
        cached = null
    }
}

/** Field text goes into a one-line preview, so newlines and runs of spaces would break the layout. */
internal fun String.collapseWhitespace(): String =
    trim().replace(Regex("\\s+"), " ")
