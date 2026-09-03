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
import me.pngwasi.plume.data.Languages

/** What the action will be applied to, surfaced so the user is never surprised by the scope. */
enum class ActionScope { Selection, WholeField }

/**
 * Which text a translation is for.
 *
 * [Field] rewrites what the user is writing. [Clipboard] translates what someone sent them and
 * shows it in the panel — it must never touch the input field, or replying would overwrite the
 * message they were trying to read.
 */
enum class TranslationSubject { Field, Clipboard }

sealed interface ImeState {
    /** Waiting for the user. [scope] is null when there is nothing usable in the field. */
    data class Ready(
        val scope: ActionScope?,
        val preview: String,
        val confirmation: String? = null,
        /** Enables the clipboard action; false when there is nothing copied to translate. */
        val hasClipboard: Boolean = false,
    ) : ImeState

    data class PickLanguage(
        val favorites: List<String>,
        val recents: List<String>,
        val subject: TranslationSubject = TranslationSubject.Field,
    ) : ImeState

    /**
     * A translated incoming message, shown in the panel rather than written anywhere. Reading is
     * the whole point here; the user is mid-conversation and has not asked to change their draft.
     */
    data class Reading(
        val original: String,
        val translated: String,
        val language: String,
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
        data class ReadClipboard(val code: String) : Retry
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
    /** Null on platforms with no readable clipboard; the action is then simply never offered. */
    private val clipboard: ClipboardSource? = null,
) {

    private val _state = MutableStateFlow<ImeState>(ImeState.Ready(null, ""))
    val state: StateFlow<ImeState> = _state.asStateFlow()

    private var running: Job? = null

    /**
     * Re-reads the field. Called whenever the panel is shown or the input target changes, so the
     * scope indicator always describes the field the user is actually looking at.
     */
    fun refresh(confirmation: String? = null) {
        running?.cancel()
        _state.value = readyState(confirmation)
    }

    private fun readyState(confirmation: String? = null): ImeState.Ready {
        val copied = runCatching { clipboard?.read() }.getOrNull()
        val text = bridge.read()
        if (text == null || text.isEmpty) {
            return ImeState.Ready(
                scope = null,
                preview = "",
                confirmation = confirmation,
                hasClipboard = !copied.isNullOrBlank(),
            )
        }
        return ImeState.Ready(
            scope = if (text.hasSelection) ActionScope.Selection else ActionScope.WholeField,
            preview = text.target.collapseWhitespace(),
            confirmation = confirmation,
            hasClipboard = !copied.isNullOrBlank(),
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

    /** Offers the language picker for whatever is on the clipboard. */
    fun startReadClipboard() {
        val copied = runCatching { clipboard?.read() }.getOrNull()
        if (copied.isNullOrBlank()) {
            _state.value = ImeState.Failed("There is no copied text yet.", settingsFix = false, retry = null)
            return
        }
        running?.cancel()
        running = scope.launch {
            val settings = settingsOrNull() ?: return@launch
            val preset = settings.translate.defaultTarget
            if (preset.isNullOrBlank()) {
                _state.value = ImeState.PickLanguage(
                    favorites = settings.translate.favorites,
                    recents = settings.translate.recents,
                    subject = TranslationSubject.Clipboard,
                )
            } else {
                recordTarget(preset)
                readClipboard(preset)
            }
        }
    }

    /**
     * Translates the clipboard and shows the result in the panel.
     *
     * Deliberately never writes: the user is reading someone else's message, not editing their own
     * draft, and replacing their half-typed reply would be the opposite of helpful.
     */
    fun readClipboard(code: String) {
        running?.cancel()
        running = scope.launch {
            val copied = runCatching { clipboard?.read() }.getOrNull()
            if (copied.isNullOrBlank()) {
                _state.value = ImeState.Failed("There is no copied text yet.", settingsFix = false, retry = null)
                return@launch
            }

            _state.value = ImeState.Working("Translating")
            val settings = settingsOrNull() ?: return@launch
            recordTarget(code)

            try {
                val engine = TextEngine(settings, apiKeyFor)
                _state.value = ImeState.Reading(
                    original = copied,
                    translated = engine.translate(copied, code),
                    language = Languages.resolve(code).displayName(),
                )
            } catch (e: AiException) {
                _state.value = ImeState.Failed(
                    message = e.message ?: "Something went wrong.",
                    settingsFix = e.kind == AiException.Kind.NotConfigured ||
                        e.kind == AiException.Kind.Auth,
                    retry = ImeState.Retry.ReadClipboard(code),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImeState.Failed(
                    e.message ?: "Unexpected error.",
                    false,
                    ImeState.Retry.ReadClipboard(code),
                )
            }
        }
    }

    /** Empties the field the user is writing in. */
    fun clearField() {
        running?.cancel()
        bridge.clearAll()
        refresh()
    }

    /** Leaves the picker without running anything. */
    fun cancelPicker() = refresh()

    /**
     * Re-reads the field after the host app reports a change, without disturbing work in progress
     * or a picker the user is looking at.
     *
     * The confirmation is carried over: replacing text is itself a selection change, so clearing it
     * here would make "Revised" flash and vanish the instant it appeared.
     */
    fun onFieldChanged() {
        val current = _state.value
        if (current !is ImeState.Ready) return
        refresh(confirmation = current.confirmation)
    }

    /** Leaves the translated message and returns to the actions. */
    fun closeReading() = refresh()

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

    /**
     * Always read through rather than cached: settings can change in the app while the panel is
     * open, and a stale snapshot showed languages the user had just unpinned. DataStore holds the
     * value in memory, so this costs nothing worth saving.
     */
    private suspend fun settingsOrNull(): AppSettings? =
        runCatching { loadSettings() }
            .onFailure {
                _state.value = ImeState.Failed("Could not read Plume settings.", true, null)
            }
            .getOrNull()
}

/**
 * Targets offered by the keyboard picker: the pinned languages, most recently used first.
 *
 * Pinning is the only thing that decides what appears here. An earlier version also offered recent
 * languages, which meant unpinning something you had just used left it on screen — the settings
 * screen said it was gone and the keyboard still showed it. Recency now only sorts; it never adds.
 * That also makes the promise on the settings screen literally true.
 *
 * The fallback is what stops the picker dead-ending. There is no room in a keyboard panel for a
 * search field, and no way to reach settings from inside the picker, so a user with nothing pinned
 * would otherwise have no way to translate at all.
 */
fun pickerOptions(
    recents: List<String>,
    favorites: List<String>,
    fallback: List<String> = Languages.defaultFavorites(),
    max: Int = 12,
): List<String> {
    if (favorites.isEmpty()) return fallback.distinctBy { it.lowercase() }.take(max)
    val recency = recents.mapIndexed { index, code -> code.lowercase() to index }.toMap()
    return favorites
        .distinctBy { it.lowercase() }
        .sortedBy { recency[it.lowercase()] ?: Int.MAX_VALUE }
        .take(max)
}

/** Field text goes into a one-line preview, so newlines and runs of spaces would break the layout. */
internal fun String.collapseWhitespace(): String =
    trim().replace(Regex("\\s+"), " ")
