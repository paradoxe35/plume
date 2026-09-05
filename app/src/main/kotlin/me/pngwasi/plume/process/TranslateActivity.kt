package me.pngwasi.plume.process

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import me.pngwasi.plume.MainActivity
import me.pngwasi.plume.data.Languages

/**
 * "Translate" in the text-selection toolbar.
 *
 * Unlike Revise, this opens the picker first — the target language is a per-invocation decision.
 * The result is usually read-only (translation happens on text you are reading, not writing), so
 * the result panel is the main path here rather than a fallback; replacing in place is offered
 * only when the selection came from an editable field.
 */
class TranslateActivity : TextActionActivity() {

    /** Mirrors the view model so the base class can title the result panel. */
    private var lastTarget: String? = null

    override fun resultTitle(): String =
        lastTarget?.let { Languages.resolve(it).displayName() } ?: "Translated"

    @Composable
    override fun Content(viewModel: TextActionViewModel, state: ActionState) {
        LaunchedEffect(Unit) { viewModel.startOnce { prepareTranslation(request.text) } }

        // Set synchronously by translate() before the state moves on, so this is never stale.
        val target = viewModel.chosenTarget
        lastTarget = target

        when (state) {
            is ActionState.Preparing -> WorkingPanel("Loading", ::cancel)

            is ActionState.PickLanguage -> LanguagePickerPanel(
                favorites = state.favorites,
                recents = state.recents,
                onPick = { code -> viewModel.translate(request.text, code) },
                onManage = ::openLanguageSettings,
            )

            is ActionState.Working -> WorkingPanel(state.note, ::cancel)

            is ActionState.Done -> ResultPanel(
                title = target?.let { Languages.resolve(it).displayName() } ?: "Translated",
                output = state.output,
                onCopy = { copyToClipboard(state.output) },
                onReplace = if (request.editable) ({ replaceSelection(state.output) }) else null,
                onDismiss = ::cancel,
                secondaryLabel = "Other language",
                onSecondary = { viewModel.reopenPicker() },
            )

            is ActionState.Failed -> ErrorPanel(
                message = state.message,
                showSettings = state.settingsFix,
                onRetry = target?.let { code -> { viewModel.translate(request.text, code) } },
                onOpenSettings = ::openSettings,
                onDismiss = ::cancel,
            )
        }
    }

    private fun openLanguageSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TRANSLATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
