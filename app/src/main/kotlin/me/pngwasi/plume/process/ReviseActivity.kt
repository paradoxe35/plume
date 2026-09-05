package me.pngwasi.plume.process

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * "Revise" in the text-selection toolbar. Fixes spelling, grammar and accents in place.
 *
 * When the selection came from an editable field the corrected text is written straight back and
 * the overlay closes — no confirmation step. That is the fast path this feature exists for; the
 * user is mid-sentence and any extra tap defeats the point. Read-only selections fall back to the
 * result panel, where copying is the only way out.
 */
class ReviseActivity : TextActionActivity() {

    override fun resultTitle() = "Revised"

    @Composable
    override fun Content(viewModel: TextActionViewModel, state: ActionState) {
        LaunchedEffect(Unit) { viewModel.startOnce { revise(request.text) } }

        when (state) {
            is ActionState.Preparing -> WorkingPanel("Revising", ::cancel)
            is ActionState.Working -> WorkingPanel(state.note, ::cancel)

            is ActionState.Done -> {
                if (request.editable) {
                    LaunchedEffect(state.output) { replaceSelection(state.output) }
                    WorkingPanel("Replacing", ::cancel)
                } else {
                    DoneContent(state.output)
                }
            }

            is ActionState.Failed -> ErrorPanel(
                message = state.message,
                showSettings = state.settingsFix,
                onRetry = { viewModel.revise(request.text) },
                onOpenSettings = ::openSettings,
                onDismiss = ::cancel,
            )

            // Revise never asks for a language; nothing to render.
            is ActionState.PickLanguage -> WorkingPanel("Revising", ::cancel)
        }
    }
}
