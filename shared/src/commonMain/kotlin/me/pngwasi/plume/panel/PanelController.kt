package me.pngwasi.plume.panel

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pngwasi.plume.ai.AiException
import me.pngwasi.plume.ai.AiHttp
import me.pngwasi.plume.ai.TextEngine
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages

/** What the action will be applied to, surfaced so the user is never surprised by the scope. */
enum class ActionScope { Selection, WholeField }

/**
 * [Field] rewrites what the user is writing. [Clipboard] only displays its result: writing it back
 * would overwrite the draft reply with the message being read.
 */
enum class TranslationSubject { Field, Clipboard }

sealed interface PanelState {
    /** Waiting for the user. [scope] is null when there is nothing usable in the field. */
    data class Ready(
        val scope: ActionScope?,
        val preview: String,
        val confirmation: String? = null,
        /** Enables the clipboard action; false when there is nothing copied to translate. */
        val hasClipboard: Boolean = false,
    ) : PanelState

    data class PickLanguage(
        val favorites: List<String>,
        val recents: List<String>,
        val subject: TranslationSubject = TranslationSubject.Field,
    ) : PanelState

    /** A translated incoming message, shown in the panel and never written to the field. */
    data class Reading(
        val original: String,
        val translated: String,
        val language: String,
    ) : PanelState

    data class Working(val note: String) : PanelState

    data class Failed(
        val message: String,
        val settingsFix: Boolean,
        val retry: Retry?,
    ) : PanelState

    /** What a Retry button should re-run. */
    sealed interface Retry {
        data object Revise : Retry
        data class Translate(val code: String) : Retry
        data class ReadClipboard(val code: String) : Retry
    }
}

/**
 * Drives the keyboard panel: reads the field, runs an action, writes the result back. Kept free of
 * Android UI types so the flow is testable against a fake editor and a local HTTP server.
 */
class PanelController(
    private val scope: CoroutineScope,
    private val bridge: EditorBridge,
    private val loadSettings: suspend () -> AppSettings,
    private val apiKeyFor: (String) -> String,
    private val onTargetUsed: suspend (String) -> Unit = {},
    /** Null on platforms with no readable clipboard; the action is then simply never offered. */
    private val clipboard: ClipboardSource? = null,
    private val http: HttpClient = AiHttp.shared,
) {

    private val _state = MutableStateFlow<PanelState>(PanelState.Ready(null, ""))
    val state: StateFlow<PanelState> = _state.asStateFlow()

    private var running: Job? = null

    /** Re-reads the field so the scope indicator describes the field currently in focus. */
    fun refresh(confirmation: String? = null) {
        running?.cancel()
        _state.value = readyState(confirmation)
    }

    private fun readyState(confirmation: String? = null): PanelState.Ready {
        // Availability only. Reading here would prompt the user on iOS, and warn them on Android,
        // every single time the panel was shown.
        val copied = runCatching { clipboard?.hasText() }.getOrNull() == true
        val text = bridge.read()
        if (text == null || text.isEmpty) {
            return PanelState.Ready(
                scope = null,
                preview = "",
                confirmation = confirmation,
                hasClipboard = copied,
            )
        }
        return PanelState.Ready(
            scope = if (text.hasSelection) ActionScope.Selection else ActionScope.WholeField,
            preview = text.target.collapseWhitespace(),
            confirmation = confirmation,
            hasClipboard = copied,
        )
    }

    fun revise() = launchAction("Revising", PanelState.Retry.Revise) { engine, text ->
        engine.revise(text)
    }

    /** Opens the language picker, or goes straight to translating when a default target is pinned. */
    fun startTranslate() {
        running?.cancel()
        running = scope.launch {
            val settings = settingsOrNull() ?: return@launch
            val preset = settings.translate.defaultTarget
            if (preset.isNullOrBlank()) {
                _state.value = PanelState.PickLanguage(
                    favorites = settings.translate.favorites,
                    recents = settings.translate.recents,
                )
            } else {
                // Inline rather than translate(), which would cancel the very job this runs in and
                // orphan the action from `running`.
                recordTarget(preset)
                execute("Translating", PanelState.Retry.Translate(preset)) { engine, text ->
                    engine.translate(text, preset)
                }
            }
        }
    }

    fun translate(code: String) {
        launchAction("Translating", PanelState.Retry.Translate(code)) { engine, text ->
            recordTarget(code)
            engine.translate(text, code)
        }
    }

    /** Offers the language picker for whatever is on the clipboard. */
    fun startReadClipboard() {
        val copied = runCatching { clipboard?.read() }.getOrNull()
        if (copied.isNullOrBlank()) {
            _state.value = PanelState.Failed("There is no copied text yet.", settingsFix = false, retry = null)
            return
        }
        running?.cancel()
        running = scope.launch {
            val settings = settingsOrNull() ?: return@launch
            val preset = settings.translate.defaultTarget
            if (preset.isNullOrBlank()) {
                _state.value = PanelState.PickLanguage(
                    favorites = settings.translate.favorites,
                    recents = settings.translate.recents,
                    subject = TranslationSubject.Clipboard,
                )
            } else {
                // Inline rather than readClipboard(), which cancels `running` — this job. Self-
                // cancelling made the pinned-target path a race that failed under CI load.
                recordTarget(preset)
                readClipboardInto(preset)
            }
        }
    }

    /** Shows the translation in the panel and never writes it back over the user's draft reply. */
    fun readClipboard(code: String) {
        running?.cancel()
        running = scope.launch { readClipboardInto(code) }
    }

    /** Split out so callers already inside [running] can run it without cancelling themselves. */
    private suspend fun readClipboardInto(code: String) {
        val copied = runCatching { clipboard?.read() }.getOrNull()
        if (copied.isNullOrBlank()) {
            _state.value = PanelState.Failed("There is no copied text yet.", settingsFix = false, retry = null)
            return
        }

        _state.value = PanelState.Working("Translating")
        val settings = settingsOrNull() ?: return
        recordTarget(code)

        try {
            val engine = TextEngine(settings, apiKeyFor, http)
            _state.value = PanelState.Reading(
                original = copied,
                translated = engine.translate(copied, code),
                language = Languages.resolve(code).displayName(),
            )
        } catch (e: AiException) {
            _state.value = PanelState.Failed(
                message = e.message ?: "Something went wrong.",
                settingsFix = e.kind == AiException.Kind.NotConfigured ||
                    e.kind == AiException.Kind.Auth,
                retry = PanelState.Retry.ReadClipboard(code),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = PanelState.Failed(
                e.message ?: "Unexpected error.",
                false,
                PanelState.Retry.ReadClipboard(code),
            )
        }
    }

    fun clearField() {
        running?.cancel()
        bridge.clearAll()
        refresh()
    }

    fun cancelPicker() = refresh()

    /**
     * The confirmation is carried over because replacing text is itself a selection change, and
     * clearing it here would make "Revised" flash and vanish the instant it appeared.
     */
    fun onFieldChanged() {
        val current = _state.value
        if (current !is PanelState.Ready) return
        refresh(confirmation = current.confirmation)
    }

    fun closeReading() = refresh()

    private suspend fun recordTarget(code: String) {
        runCatching { onTargetUsed(code) }
    }

    /** Visible for tests, which await the in-flight action. */
    internal val inFlight: Job? get() = running

    private fun launchAction(
        note: String,
        retry: PanelState.Retry,
        block: suspend (TextEngine, String) -> String,
    ) {
        running?.cancel()
        running = scope.launch { execute(note, retry, block) }
    }

    private suspend fun execute(
        note: String,
        retry: PanelState.Retry,
        block: suspend (TextEngine, String) -> String,
    ) {
        val text = bridge.read()
        if (text == null || text.isEmpty) {
            _state.value = PanelState.Failed(
                message = "There is no text in this field yet.",
                settingsFix = false,
                retry = null,
            )
            return
        }

        _state.value = PanelState.Working(note)
        val settings = settingsOrNull() ?: return

        try {
            val engine = TextEngine(settings, apiKeyFor, http)
            val result = block(engine, text.target)
            // Re-read before writing: the user may have kept typing while the call was in flight,
            // and replacing a field we no longer understand would destroy their edit.
            val latest = bridge.read()
            if (latest == null || latest.target != text.target) {
                _state.value = PanelState.Failed(
                    message = "The text changed while Plume was working. Try again.",
                    settingsFix = false,
                    retry = retry,
                )
                return
            }
            val applied = bridge.apply(result)
            _state.value = if (applied) {
                readyState(confirmation = if (retry is PanelState.Retry.Revise) "Revised" else "Translated")
            } else {
                PanelState.Failed("Could not write back to this field.", false, retry)
            }
        } catch (e: AiException) {
            _state.value = PanelState.Failed(
                message = e.message ?: "Something went wrong.",
                settingsFix = e.kind == AiException.Kind.NotConfigured ||
                    e.kind == AiException.Kind.Auth,
                retry = retry,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = PanelState.Failed(e.message ?: "Unexpected error.", false, retry)
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
                _state.value = PanelState.Failed("Could not read Plume settings.", true, null)
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
