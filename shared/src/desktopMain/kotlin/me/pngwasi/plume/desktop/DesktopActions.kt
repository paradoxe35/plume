package me.pngwasi.plume.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.pngwasi.plume.ai.AiException
import me.pngwasi.plume.ai.TextEngine
import me.pngwasi.plume.data.AppSettings
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean

/** What the tray shows, and what a notification says once an action finishes. */
sealed interface ActionOutcome {
    data object Idle : ActionOutcome
    data class Working(val label: String) : ActionOutcome
    data class Done(val label: String, val original: String, val result: String) : ActionOutcome
    data class Failed(val message: String) : ActionOutcome
}

/** One entry of the desktop's answer to undo. */
data class HistoryEntry(
    val label: String,
    val original: String,
    val result: String,
    val at: Long = System.currentTimeMillis(),
)

/**
 * The three things a desktop hotkey can do.
 *
 * The paste lands in another application, which Plume cannot reach back into, so two things follow:
 * the outcome has to be reported somewhere visible, and the original text has to be kept so the
 * user can put it back themselves. Both are here rather than in the UI.
 */
class DesktopActions(
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val secrets: SecretStore,
    private val captureFactory: () -> TextCapture?,
    private val maxHistory: Int = 20,
) {

    private val _outcome = MutableStateFlow<ActionOutcome>(ActionOutcome.Idle)
    val outcome: StateFlow<ActionOutcome> = _outcome.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    /**
     * One action at a time. Two overlapping runs would fight over the clipboard — a single global
     * resource that each of them saves and restores — and interleave their paste and restore steps.
     * MyReviser guarded this with a plain bool under a mutex; the hotkey listener is its own thread,
     * so this needs to be atomic rather than merely mutexed around a read and a write.
     */
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun reviseSelection() = run("Revise") { engine, text -> engine.revise(text) }

    fun reviseEverything() = run("Revise all", selectAll = true) { engine, text -> engine.revise(text) }

    fun translateSelection(code: String) =
        run("Translate to ${Languages.resolve(code).displayName()}") { engine, text ->
            repository.recordTranslationTarget(code)
            engine.translate(text, code)
        }

    private fun run(
        label: String,
        selectAll: Boolean = false,
        block: suspend (TextEngine, String) -> String,
    ) {
        if (!running.compareAndSet(false, true)) {
            _outcome.value = ActionOutcome.Failed("Plume is already working on something.")
            return
        }
        scope.launch {
            try {
                execute(label, selectAll, block)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun execute(
        label: String,
        selectAll: Boolean,
        block: suspend (TextEngine, String) -> String,
    ) {
        val capture = captureFactory()
        if (capture == null) {
            _outcome.value = ActionOutcome.Failed(
                "Plume cannot reach the keyboard and clipboard. Check the permissions in Settings.",
            )
            return
        }

        _outcome.value = ActionOutcome.Working(label)

        // The clipboard calls block, and they must not sit on whichever thread the hotkey callback
        // arrived on.
        val captured = withContext(Dispatchers.IO) {
            if (selectAll) capture.captureAll() else capture.captureSelection()
        }

        val text = when (captured) {
            is Capture.Text -> captured.value
            Capture.NothingSelected -> {
                _outcome.value = ActionOutcome.Failed("Select some text first.")
                return
            }
            Capture.CopyFailed -> {
                _outcome.value = ActionOutcome.Failed(
                    "Plume could not copy from that window. Some applications block it.",
                )
                return
            }
            is Capture.Failed -> {
                _outcome.value = ActionOutcome.Failed(captured.reason)
                return
            }
        }

        val settings: AppSettings = try {
            repository.current()
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { capture.abandon() }
            _outcome.value = ActionOutcome.Failed("Could not read Plume settings.")
            return
        }

        val result = try {
            block(TextEngine(settings, secrets), text)
        } catch (e: AiException) {
            withContext(Dispatchers.IO) { capture.abandon() }
            _outcome.value = ActionOutcome.Failed(e.message ?: "Something went wrong.")
            return
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { capture.abandon() }
            _outcome.value = ActionOutcome.Failed(e.message ?: "Unexpected error.")
            return
        }

        val written = withContext(Dispatchers.IO) { capture.replaceSelection(result) }
        if (!written) {
            _outcome.value = ActionOutcome.Failed("Could not paste the result back.")
            return
        }

        remember(HistoryEntry(label, text, result))
        _outcome.value = ActionOutcome.Done(label, text, result)
    }

    private fun remember(entry: HistoryEntry) {
        _history.value = (listOf(entry) + _history.value).take(maxHistory)
    }

    fun clearOutcome() {
        _outcome.value = ActionOutcome.Idle
    }
}
