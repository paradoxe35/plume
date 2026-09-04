package me.pngwasi.plume.ime

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.pngwasi.plume.MainActivity
import me.pngwasi.plume.data.PlumeStores
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeCache
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.panel.PanelController
import me.pngwasi.plume.panel.PanelState
import me.pngwasi.plume.panel.TranslationSubject
import me.pngwasi.plume.ui.theme.PlumeTheme

/**
 * Plume's companion keyboard: an action panel rather than a keyboard. Being an IME is what grants
 * the [android.view.inputmethod.InputConnection] needed to rewrite a whole field with no selection
 * and no permission. Optional and disabled by default — see [KeyboardComponent].
 */
class PlumeInputMethodService : android.inputmethodservice.InputMethodService() {

    private val owner = ImeViewOwner()
    private var scope: CoroutineScope? = null
    private lateinit var controller: PanelController
    // Compose state, not a plain var: a plain field read in setContent never recomposes, so the
    // panel would keep whatever theme it was built with.
    private var themeMode by mutableStateOf(ThemeMode.System)
    private var themeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        owner.onCreate()

        val serviceScope = MainScope()
        scope = serviceScope

        val repository = PlumeStores.settings(this)
        val secrets = PlumeStores.secrets(this)

        controller = PanelController(
            scope = serviceScope,
            bridge = InputConnectionBridge { currentInputConnection },
            loadSettings = { repository.current() },
            apiKeyFor = { id -> secrets.getKey(id) },
            onTargetUsed = { code -> repository.recordTranslationTarget(code) },
            clipboard = AndroidClipboardSource(this),
        )

        // Follows Plume's theme, not the host app's. Seeded synchronously to avoid a flash, then
        // collected because the service outlives any single settings change.
        themeMode = ThemeCache.read(this)
        themeJob = serviceScope.launch {
            runCatching {
                repository.settings
                    .map { it.theme }
                    .distinctUntilChanged()
                    .collect { themeMode = it }
            }
        }
    }

    override fun onCreateInputView(): View {
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by controller.state.collectAsState()
                PlumeTheme(mode = themeMode) {
                    ImePanel(
                        state = state,
                        onRevise = controller::revise,
                        onTranslate = controller::startTranslate,
                        onReadClipboard = controller::startReadClipboard,
                        onPickLanguage = { code ->
                            val picking = controller.state.value as? PanelState.PickLanguage
                            if (picking?.subject == TranslationSubject.Clipboard) {
                                controller.readClipboard(code)
                            } else {
                                controller.translate(code)
                            }
                        },
                        onCancelPicker = controller::cancelPicker,
                        onCloseReading = controller::closeReading,
                        onClearField = controller::clearField,
                        onCopy = ::copyToClipboard,
                        onOpenSettings = ::openSettings,
                        onBackToKeyboard = ::switchAway,
                    )
                }
            }
        }

        // Compose resolves its recomposer from the window's root view, so the owners must be on the
        // IME decor too; attaching only to `view` crashes the process with "ViewTreeLifecycleOwner
        // not found from ...parentPanel" on first show.
        window?.window?.decorView?.let(owner::attachTo)
        owner.attachTo(view)

        // Composition only runs once resumed, and a show can land before onStartInputView.
        owner.onStart()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        owner.onStart()
        controller.refresh()
    }

    /** The only signal that the host field changed; without it the panel stays stale after typing. */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        controller.onFieldChanged()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        owner.onStop()
    }

    /** Fullscreen ("extract") mode would replace the host field, hiding the text being acted on. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        themeJob?.cancel()
        scope?.cancel()
        owner.onDestroy()
        super.onDestroy()
    }

    /**
     * Returns the user to their typing keyboard. Android's switch history is tried first but comes
     * up empty after a restart, so the remembered keyboard backs it; the picker is the last resort.
     */
    private fun switchAway() {
        if (switchToPrevious()) return

        val target = TypingKeyboard.resolveTarget(this)
        if (target != null && switchTo(target)) return

        KeyboardComponent.showPicker(this)
    }

    private fun switchToPrevious(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            val token = window?.window?.attributes?.token
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            // The pre-28 equivalent, and the only one those versions have.
            @Suppress("DEPRECATION")
            token != null && imm?.switchToLastInputMethod(token) == true
        }
    }.getOrDefault(false)

    private fun switchTo(imeId: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchInputMethod(imeId)
        } else {
            @Suppress("DEPRECATION")
            val token = window?.window?.attributes?.token ?: return false
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
            @Suppress("DEPRECATION")
            imm.setInputMethod(token, imeId)
        }
        true
    }.getOrDefault(false)

    private fun copyToClipboard(text: String) {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        manager?.setPrimaryClip(android.content.ClipData.newPlainText("Plume", text))
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
