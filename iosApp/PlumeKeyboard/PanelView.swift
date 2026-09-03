import SwiftUI
import PlumeShared

/// The keyboard panel, mirroring the Android one screen for screen.
///
/// It switches on `snapshot.kind` rather than on a Kotlin sealed type: Kotlin/Native exports those
/// as a class hierarchy that Swift can only handle with `as?` chains, and the flattened snapshot
/// keeps this readable.
struct PanelView: View {

    @ObservedObject var model: PanelModel
    let actions: PanelActions

    private var snapshot: IosPanelSnapshot? { model.snapshot }

    var body: some View {
        VStack(spacing: 10) {
            header
            Group {
                switch snapshot?.kind {
                case IosPanelSnapshot.companion.PICK: picker
                case IosPanelSnapshot.companion.WORKING: working
                case IosPanelSnapshot.companion.READING: reading
                case IosPanelSnapshot.companion.FAILED: failed
                default: ready
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Color(UIColor.systemBackground))
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text("PLUME")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.tint)
            Spacer()
            if snapshot?.hasText == true {
                Button(action: actions.clearField) {
                    Image(systemName: "delete.left")
                }
            }
            // iOS requires a way back to the previous keyboard; without it the user can be stuck.
            Button(action: actions.nextKeyboard) {
                Image(systemName: "globe")
            }
        }
        .font(.footnote)
    }

    private var ready: some View {
        VStack(spacing: 10) {
            if let confirmation = snapshot?.confirmation {
                Label(confirmation, systemImage: "checkmark.circle")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if snapshot?.hasText != true {
                Text("Type with your usual keyboard, then switch back here to fix or translate it.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(alignment: .leading, spacing: 4) {
                    if snapshot?.hasSelection == true {
                        Text("YOUR SELECTION").font(.caption2).foregroundStyle(.tint)
                    }
                    Text(snapshot?.preview ?? "")
                        .font(.footnote)
                        .lineLimit(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: .infinity, alignment: .top)
            }

            HStack(spacing: 10) {
                Button(action: actions.revise) {
                    Label("Revise", systemImage: "wand.and.stars")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(snapshot?.hasText != true)

                Button(action: actions.translate) {
                    Label("Translate", systemImage: "character.book.closed")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(snapshot?.hasText != true)
            }

            // Kept visible but disabled: hiding it would make the panel jump and would never teach
            // anyone the feature exists.
            Button(action: actions.readClipboard) {
                Label(
                    snapshot?.hasClipboard == true ? "Translate copied text" : "No copied text",
                    systemImage: "doc.on.clipboard"
                )
                .frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered)
            .disabled(snapshot?.hasClipboard != true)
        }
    }

    private var picker: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(snapshot?.note == "clipboard" ? "Translate copied text into" : "Translate into")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("Cancel", action: actions.cancelPicker).font(.caption)
            }
            ScrollView {
                let codes = snapshot?.languageCodes ?? []
                let names = snapshot?.languageNames ?? []
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 110))], spacing: 8) {
                    ForEach(Array(codes.enumerated()), id: \.offset) { index, code in
                        Button(index < names.count ? names[index] : code) {
                            if snapshot?.note == "clipboard" {
                                actions.pickForClipboard(code)
                            } else {
                                actions.pick(code)
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private var working: some View {
        VStack(spacing: 10) {
            ProgressView()
            Text(snapshot?.note ?? "Working").font(.footnote).foregroundStyle(.secondary)
        }
    }

    /// Reading never writes to the field: the user is looking at someone else's message while a
    /// half-typed reply may be sitting there.
    private var reading: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(snapshot?.language ?? "").font(.caption2).foregroundStyle(.tint)
                Spacer()
                Button("Back", action: actions.closeReading).font(.caption)
            }
            ScrollView {
                Text(snapshot?.translated ?? "")
                    .font(.footnote)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
        }
    }

    private var failed: some View {
        VStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle")
            Text(snapshot?.message ?? "Something went wrong.")
                .font(.footnote)
                .multilineTextAlignment(.center)
            if snapshot?.settingsFix == true {
                Text("Open Plume to finish setting up a provider.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
