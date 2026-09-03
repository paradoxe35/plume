package me.pngwasi.plume.ime

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.pngwasi.plume.MainActivity
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ui.theme.PlumeTheme

/**
 * Plume's companion keyboard: an action panel rather than a keyboard.
 *
 * An IME holds an [android.view.inputmethod.InputConnection], which is the only way to read and
 * rewrite a whole text field without a selection and without any permission. The user switches to
 * it for a moment, runs an action, and switches straight back to their real keyboard.
 *
 * It is optional and disabled by default — see [KeyboardComponent].
 */
class PlumeInputMethodService : android.inputmethodservice.InputMethodService() {

    private val owner = ImeViewOwner()
    private var scope: CoroutineScope? = null
    private lateinit var controller: ImePanelController
    private var themeMode: ThemeMode = ThemeMode.System
    private var themeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        owner.onCreate()

        val serviceScope = MainScope()
        scope = serviceScope

        val repository = SettingsRepository.get(this)
        val secrets = SecretStore(this)

        controller = ImePanelController(
            scope = serviceScope,
            bridge = InputConnectionBridge { currentInputConnection },
            loadSettings = { repository.current() },
            apiKeyFor = { id -> secrets.getKey(id) },
            onTargetUsed = { code -> repository.recordTranslationTarget(code) },
        )

        // The panel is drawn over other apps' input areas, so it follows the same theme setting as
        // the rest of Plume rather than the host app's.
        themeJob = serviceScope.launch {
            runCatching { themeMode = repository.settings.first().theme }
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
                        onPickLanguage = controller::translate,
                        onCancelPicker = controller::cancelPicker,
                        onOpenSettings = ::openSettings,
                        onBackToKeyboard = ::switchAway,
                    )
                }
            }
        }
        owner.attachTo(view)
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        owner.onStart()
        // Settings may have changed since the panel was last shown.
        controller.invalidateSettings()
        controller.refresh()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        owner.onStop()
    }

    /**
     * Fullscreen ("extract") mode replaces the host app's field with one owned by the IME, which
     * would hide the very text the user is acting on. The panel is short enough not to need it.
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        themeJob?.cancel()
        scope?.cancel()
        owner.onDestroy()
        super.onDestroy()
    }

    /** Returns the user to whichever keyboard they were using before. */
    private fun switchAway() {
        val switched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            @Suppress("DEPRECATION")
            val token = window?.window?.attributes?.token
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            token != null && imm?.switchToLastInputMethod(token) == true
        }
        // No previous method to go back to (single keyboard installed): let the user choose.
        if (!switched) {
            KeyboardComponent.showPicker(this)
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
