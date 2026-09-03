package me.pngwasi.plume.process

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import me.pngwasi.plume.MainActivity
import me.pngwasi.plume.data.PlumeStores
import me.pngwasi.plume.data.SettingsRepository
import me.pngwasi.plume.data.ThemeCache
import me.pngwasi.plume.data.ThemeMode
import me.pngwasi.plume.ime.TypingKeyboard
import me.pngwasi.plume.ui.theme.PlumeTheme

/**
 * Shared plumbing for the two activities that appear in the text-selection toolbar.
 *
 * Both launch cold from another app's process, so this keeps the startup path short: no dependency
 * graph, no eager settings read, and nothing blocking before the sheet can draw.
 */
abstract class TextActionActivity : ComponentActivity() {

    protected lateinit var request: ProcessTextRequest
        private set

    /** Label shown above the result text, e.g. "Revised" or "French". */
    protected abstract fun resultTitle(): String

    @Composable
    protected abstract fun Content(viewModel: TextActionViewModel, state: ActionState)

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val parsed = ProcessTextRequest.from(intent)
        if (parsed == null) {
            Toast.makeText(this, "Nothing selected.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        request = parsed

        // A selection action runs while the user's own keyboard is selected, so this is a reliable
        // place to learn which one it is.
        TypingKeyboard.noteCurrent(this)

        setContent {
            val viewModel: TextActionViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val theme by rememberThemeMode()

            PlumeTheme(mode = theme) {
                val dark = when (theme) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightStatusBars = !dark
                }
                OverlaySheet(onDismiss = ::cancel) {
                    Content(viewModel, state)
                }
            }
        }
    }

    /**
     * Seeded from the synchronous cache so the very first frame is already the user's theme —
     * defaulting to System here would render the wrong scheme and then snap, a visible flash on a
     * surface that is only ever seen cold. The real setting still wins, and refreshes the cache.
     */
    @Composable
    private fun rememberThemeMode(): State<ThemeMode> {
        val repository = remember { PlumeStores.settings(this) }
        val cached = remember { ThemeCache.read(this) }
        return produceState(initialValue = cached, repository) {
            val actual = runCatching { repository.settings.first().theme }.getOrNull() ?: return@produceState
            if (actual != cached) ThemeCache.write(this@TextActionActivity, actual)
            value = actual
        }
    }

    /** Hands text back to the host app, which swaps it for the user's selection. */
    protected fun replaceSelection(text: String) {
        setResult(RESULT_OK, ProcessTextRequest.replacementIntent(text))
        finish()
    }

    protected fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Plume", text))
        // Android 13+ shows its own copy confirmation; a second toast would be redundant.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    protected fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    protected fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    /** Result rendering is identical across actions; only the title differs. */
    @Composable
    protected fun DoneContent(output: String) {
        ResultPanel(
            title = resultTitle(),
            output = output,
            onCopy = { copyToClipboard(output) },
            onReplace = if (request.editable) ({ replaceSelection(output) }) else null,
            onDismiss = ::cancel,
        )
    }
}
