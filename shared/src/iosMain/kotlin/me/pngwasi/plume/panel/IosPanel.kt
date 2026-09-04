package me.pngwasi.plume.panel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.pngwasi.plume.data.Languages
import me.pngwasi.plume.data.PlumeStores
import platform.UIKit.UIPasteboard
import platform.UIKit.UITextDocumentProxyProtocol

/**
 * What SwiftUI renders, flattened.
 *
 * Kotlin/Native exposes sealed hierarchies to Swift as a class tree that is awkward to switch over,
 * and suspending functions as completion handlers that are worse. A plain snapshot plus a callback
 * keeps the Swift side to a `switch` on an enum-like string and a few optionals — which is what a
 * keyboard extension, with a hard memory ceiling and no Compose, needs.
 */
data class IosPanelSnapshot(
    val kind: String,
    val preview: String = "",
    val note: String = "",
    val message: String = "",
    val original: String = "",
    val translated: String = "",
    val language: String = "",
    val confirmation: String? = null,
    val hasSelection: Boolean = false,
    val hasText: Boolean = false,
    val hasClipboard: Boolean = false,
    val settingsFix: Boolean = false,
    val languageCodes: List<String> = emptyList(),
    val languageNames: List<String> = emptyList(),
) {
    companion object {
        const val READY = "ready"
        const val PICK = "pick"
        const val WORKING = "working"
        const val READING = "reading"
        const val FAILED = "failed"
    }
}

/**
 * The bridge the keyboard extension talks to.
 *
 * The state machine and every network call are the shared Kotlin ones; only the drawing is native.
 * That split is deliberate — Compose Multiplatform's Kotlin/Native runtime costs 15–20 MB before
 * Skia allocates anything, against a keyboard extension ceiling of around 60 MB, and the system
 * kills an extension that crosses it with no message at all.
 */
class IosPanel(
    proxy: () -> UITextDocumentProxyProtocol?,
    private val onSnapshot: (IosPanelSnapshot) -> Unit,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val controller = PanelController(
        scope = scope,
        bridge = TextDocumentProxyBridge(proxy),
        loadSettings = { PlumeStores.settings.current() },
        apiKeyFor = { id -> PlumeStores.secrets.getKey(id) },
        onTargetUsed = { code -> PlumeStores.settings.recordTranslationTarget(code) },
        clipboard = IosClipboardSource(),
    )

    init {
        scope.launch {
            controller.state.collect { onSnapshot(it.toSnapshot()) }
        }
    }

    fun refresh() = controller.refresh()
    fun revise() = controller.revise()
    fun startTranslate() = controller.startTranslate()
    fun translate(code: String) = controller.translate(code)
    fun startReadClipboard() = controller.startReadClipboard()
    fun readClipboard(code: String) = controller.readClipboard(code)
    fun cancelPicker() = controller.cancelPicker()
    fun closeReading() = controller.closeReading()
    fun clearField() = controller.clearField()
    fun onFieldChanged() = controller.onFieldChanged()

    fun dispose() = scope.cancel()
}

/**
 * A keyboard extension may only reach the pasteboard with Full Access granted; without it there is
 * nothing here and the clipboard action is simply not offered.
 *
 * `hasStrings` reports whether text is present without reading it, which is what keeps iOS 16's
 * "Allow Paste?" prompt tied to the moment the user asks for the clipboard.
 */
private class IosClipboardSource : ClipboardSource {
    override fun hasText(): Boolean = UIPasteboard.generalPasteboard.hasStrings

    override fun read(): String? = UIPasteboard.generalPasteboard.string?.takeIf { it.isNotBlank() }
}

private fun PanelState.toSnapshot(): IosPanelSnapshot = when (this) {
    is PanelState.Ready -> IosPanelSnapshot(
        kind = IosPanelSnapshot.READY,
        preview = preview,
        confirmation = confirmation,
        hasSelection = scope == ActionScope.Selection,
        hasText = scope != null,
        hasClipboard = hasClipboard,
    )

    is PanelState.PickLanguage -> {
        val codes = pickerOptions(recents, favorites)
        IosPanelSnapshot(
            kind = IosPanelSnapshot.PICK,
            languageCodes = codes,
            languageNames = codes.map { Languages.resolve(it).displayName() },
            // Which text the choice applies to, so Swift can title the picker correctly.
            note = if (subject == TranslationSubject.Clipboard) "clipboard" else "field",
        )
    }

    is PanelState.Working -> IosPanelSnapshot(kind = IosPanelSnapshot.WORKING, note = note)

    is PanelState.Reading -> IosPanelSnapshot(
        kind = IosPanelSnapshot.READING,
        original = original,
        translated = translated,
        language = language,
    )

    is PanelState.Failed -> IosPanelSnapshot(
        kind = IosPanelSnapshot.FAILED,
        message = message,
        settingsFix = settingsFix,
    )
}
