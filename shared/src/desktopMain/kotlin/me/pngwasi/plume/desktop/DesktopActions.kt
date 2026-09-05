package me.pngwasi.plume.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/**
 * How many changes are kept.
 *
 * Each entry holds the text it replaced, so this is a memory bound as much as a UI one: twenty is
 * enough to undo something noticed a few actions later, and small enough that even at the largest
 * character limit the whole list stays under a megabyte.
 */
const val MAX_HISTORY = 20

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
    private val maxHistory: Int = MAX_HISTORY,
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
            // Not reported as a failure: the tray already says what is running, and overwriting its
            // outcome turns a shortcut pressed twice into an error about the action that is fine.
            PlumeLog.info("$label ignored while another action holds the clipboard")
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
        PlumeLog.info("$label requested")
        val capture = captureFactory()
        if (capture == null) {
            PlumeLog.error("$label: no clipboard or keyboard access")
            _outcome.value = ActionOutcome.Failed(
                "Plume cannot reach the keyboard and clipboard. Check the permissions in Settings.",
            )
            return
        }

        // The clipboard is borrowed from here on. `replaceSelection` gives it back itself; every
        // other way out of this block, cancellation included, goes through the finally.
        var handedBack = false
        try {
            _outcome.value = ActionOutcome.Working(label)

            // The clipboard calls block, and they must not sit on whichever thread the hotkey
            // callback arrived on.
            val captured = withContext(Dispatchers.IO) {
                if (selectAll) capture.captureAll() else capture.captureSelection()
            }

            val text = when (captured) {
                is Capture.Text -> captured.value
                Capture.NothingSelected -> {
                    PlumeLog.info("$label: nothing was selected")
                    _outcome.value = ActionOutcome.Failed("Select some text first.")
                    return
                }
                Capture.CopyFailed -> {
                    PlumeLog.error("$label: the copy never landed")
                    _outcome.value = ActionOutcome.Failed(
                        "Plume could not copy from that window. Some applications block it.",
                    )
                    return
                }
                is Capture.Failed -> {
                    PlumeLog.error("$label: ${captured.reason}")
                    _outcome.value = ActionOutcome.Failed(captured.reason)
                    return
                }
            }

            val settings: AppSettings = try {
                repository.current()
            } catch (e: Exception) {
                PlumeLog.error("$label: could not read settings", e)
                _outcome.value = ActionOutcome.Failed("Could not read Plume settings.")
                return
            }

            val result = try {
                block(TextEngine(settings, secrets), text)
            } catch (e: AiException) {
                PlumeLog.error("$label: ${e.kind} from the provider", e)
                // A desktop failure is reported in a system notification, which has nothing to
                // click, so the way out is spelled out here rather than in the shared message.
                val hint = if (e.kind == AiException.Kind.Auth || e.kind == AiException.Kind.NotConfigured) {
                    " Open Plume to fix it."
                } else {
                    ""
                }
                _outcome.value = ActionOutcome.Failed((e.message ?: "Something went wrong.") + hint)
                return
            } catch (e: Exception) {
                PlumeLog.error("$label failed", e)
                _outcome.value = ActionOutcome.Failed(e.message ?: "Unexpected error.")
                return
            }

            val written = withContext(Dispatchers.IO) { capture.replaceSelection(result) }
            handedBack = true
            if (!written) {
                PlumeLog.error("$label: could not paste the result back")
                _outcome.value = ActionOutcome.Failed("Could not paste the result back.")
                return
            }

            PlumeLog.info("$label finished")
            remember(HistoryEntry(label, text, result))
            _outcome.value = ActionOutcome.Done(label, text, result)
        } finally {
            // NonCancellable, or a cancelled action would leave the user's clipboard holding their
            // selection instead of what they had.
            if (!handedBack) {
                withContext(NonCancellable + Dispatchers.IO) { capture.abandon() }
            }
        }
    }

    private fun remember(entry: HistoryEntry) {
        _history.value = (listOf(entry) + _history.value).take(maxHistory)
    }
}
