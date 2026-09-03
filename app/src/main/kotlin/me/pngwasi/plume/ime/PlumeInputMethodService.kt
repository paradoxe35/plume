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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.pngwasi.plume.MainActivity
import me.pngwasi.plume.data.SecretStore
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeCache
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
    // Compose state, not a plain field: a var read inside setContent never triggers recomposition,
    // so the panel would keep whatever theme it was built with.
    private var themeMode by mutableStateOf(ThemeMode.System)
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
        // the rest of Plume rather than the host app's. Seeded synchronously to avoid a flash, then
        // collected rather than read once: this service outlives any single settings change.
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
                        onPickLanguage = controller::translate,
                        onCancelPicker = controller::cancelPicker,
                        onOpenSettings = ::openSettings,
                        onBackToKeyboard = ::switchAway,
                    )
                }
            }
        }

        // Compose resolves its recomposer from the window's *root* view, not from the ComposeView,
        // so the owners have to live on the IME window's decor as well. Attaching them only to the
        // view returned here throws "ViewTreeLifecycleOwner not found from ...parentPanel" the
        // first time the panel is shown — and since the IME shares the app's process, that takes
        // the whole app down with it.
        window?.window?.decorView?.let(owner::attachTo)
        owner.attachTo(view)

        // Composition only runs once the lifecycle is resumed, and onCreateInputView can be
        // followed by a show before onStartInputView lands.
        owner.onStart()
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        owner.onStart()
        // Settings may have changed since the panel was last shown.
        controller.invalidateSettings()
        controller.refresh()
    }

    /**
     * Fires whenever the cursor, selection or text changes in the host app. Without this the panel
     * shows whatever the field held when it opened, so typing or selecting leaves the actions
     * disabled against text that is plainly there.
     */
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

    /**
     * Returns the user to whichever keyboard they type with.
     *
     * Android's own switching history is tried first, since it knows exactly where the user came
     * from. It can come up empty — notably on the first switch after a restart — so the keyboard
     * Plume noted for itself stands behind it. If neither knows, the picker is shown: guessing at a
     * keyboard the user never chose would be worse than asking.
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
            @Suppress("DEPRECATION")
            val token = window?.window?.attributes?.token
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
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

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }
}
