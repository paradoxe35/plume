import UIKit
import SwiftUI
import PlumeShared

/// The keyboard extension's entry point.
///
/// The panel's behaviour — reading the field, calling a provider, writing the result back — is the
/// shared Kotlin `PanelController`, reached through `IosPanel`. Only the drawing is native.
///
/// This is the one place Plume knowingly gives up on sharing the UI. A keyboard extension is
/// terminated without warning if it crosses roughly 60MB, and Compose Multiplatform's Kotlin/Native
/// runtime costs 15-20MB before Skia allocates a single pixel. SwiftUI costs nothing extra here.
final class KeyboardViewController: UIInputViewController {

    private var panel: IosPanel?
    private let model = PanelModel()
    private var host: UIHostingController<PanelView>?

    override func viewDidLoad() {
        super.viewDidLoad()

        let panel = IosPanel(
            proxy: { [weak self] in self?.textDocumentProxy },
            onSnapshot: { [weak self] snapshot in
                // Kotlin delivers this on the main dispatcher already; the hop is belt and braces
                // for the case where it is emitted during construction.
                DispatchQueue.main.async { self?.model.snapshot = snapshot }
            }
        )
        self.panel = panel

        let view = PanelView(
            model: model,
            actions: PanelActions(
                revise: { panel.revise() },
                translate: { panel.startTranslate() },
                readClipboard: { panel.startReadClipboard() },
                pick: { code in panel.translate(code: code) },
                pickForClipboard: { code in panel.readClipboard(code: code) },
                cancelPicker: { panel.cancelPicker() },
                closeReading: { panel.closeReading() },
                clearField: { panel.clearField() },
                nextKeyboard: { [weak self] in self?.advanceToNextInputMode() }
            )
        )

        let host = UIHostingController(rootView: view)
        self.host = host
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        self.view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: self.view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: self.view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: self.view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: self.view.bottomAnchor),
            // A panel that resizes as its state changes makes the host app's layout jump.
            host.view.heightAnchor.constraint(equalToConstant: 272)
        ])
        host.didMove(toParent: self)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        panel?.refresh()
    }

    /// The field can change under us without the keyboard being re-created, and the panel's
    /// enabled states describe whatever the user is actually looking at.
    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        panel?.onFieldChanged()
    }

    deinit {
        panel?.dispose()
    }
}

/// Observable wrapper, since the Kotlin snapshot is a plain value type.
final class PanelModel: ObservableObject {
    @Published var snapshot: IosPanelSnapshot?
}

struct PanelActions {
    let revise: () -> Void
    let translate: () -> Void
    let readClipboard: () -> Void
    let pick: (String) -> Void
    let pickForClipboard: (String) -> Void
    let cancelPicker: () -> Void
    let closeReading: () -> Void
    let clearField: () -> Void
    let nextKeyboard: () -> Void
}
