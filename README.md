# Plume

Select text anywhere, fix it or translate it, and carry on. Plume runs on Android, Windows, macOS
and Linux from one Kotlin codebase.

Two actions, everywhere:

- **Revise** corrects spelling, grammar, punctuation, accents and agreement, in whatever language
  the text is already in.
- **Translate** asks which language you want and handles the rest.

You bring your own API key. There is no Plume account and no server of ours in the middle.

## How you reach it

The mechanism differs on every platform, because each one allows only one thing.

|                       | How Plume is invoked                                                               |
| --------------------- | ---------------------------------------------------------------------------------- |
| Android               | The text-selection toolbar, next to Cut and Copy. Plus an optional keyboard panel. |
| Windows, macOS, Linux | A global shortcut. Plume waits in the tray.                                        |
| iOS                   | A keyboard extension. iOS has no selection menu for other apps to join.            |

### On the desktop

Select text in any application, press a shortcut, and Plume replaces the selection where it stands.
Your clipboard is put back the way you left it.

| Action              | Default              |
| ------------------- | -------------------- |
| Revise selection    | `Ctrl` + `Super`     |
| Revise everything   | `Ctrl` `Alt` `Space` |
| Translate selection | `Ctrl` `Alt` `G`     |

All three are editable, and Plume tells you when the system refuses one because something else got
there first. On macOS you will need to allow Plume under Privacy & Security → Accessibility. On
Wayland your user needs to be in the `input` group.

### On Android

Plume adds two entries to the selection toolbar, so they show up in WhatsApp, Gmail, your browser
and most other apps. Look under the ⋮ overflow if you don't see them.

When the text is editable, Plume writes the result straight back. When you have selected text you
are only reading, Android gives apps no way to write to it, so Plume shows the result with a Copy
button instead. That is the platform, not a missing feature.

There is also an optional keyboard panel, off by default. It is not a typing keyboard, just the two
actions as buttons. It can rewrite a whole field without you selecting anything first, which the
selection menu cannot do.

## Install

Android: `Plume.apk` at the root of this repository. It is signed with a local key, so Android will
ask you to allow an unknown source.

Desktop: take the installer for your system from the latest release. Linux gets `.deb`, `.rpm` and
an AppImage, Windows gets `.msi` and `.exe`, macOS gets a `.dmg` for both Intel and Apple silicon.
The macOS builds are unsigned, so Gatekeeper will need convincing.

## Setup

Open Plume, go to **AI providers**, pick one and paste a key. OpenAI, OpenRouter and Gemini are
built in. Anything speaking the OpenAI chat-completions format works too, with presets for Groq,
Mistral, Together, Ollama and LM Studio.

Running a model locally? Turn off "Requires an API key" and Plume sends no `Authorization`
header at all, which is what Ollama and LM Studio expect.

By default both actions use the same provider. You can split them: something cheap and quick for
corrections, something stronger for translation.

Both prompts are editable, and there is a Reset if you regret it.

## Privacy

Your text goes to the provider you configured and nowhere else. No backend, no analytics.

Keys are kept in whatever your system uses for secrets: the Android Keystore, the iOS Keychain, the
login keychain on macOS, DPAPI on Windows, and your keyring on Linux, falling back to an encrypted
file when no keyring answers.

On the desktop, Recent changes keeps the last 20 originals so you can put one back. It lives in
memory and is gone when Plume quits. The log records what happened, never what you wrote.

## Building

You need JDK 17. The desktop build also needs Rust, since global shortcuts and clipboard access are
not things a JVM can do.

```bash
./gradlew :app:assembleDebug                 # Android
cargo build --release --manifest-path native/Cargo.toml
./gradlew :desktop:run                       # desktop, from source
./gradlew :desktop:packageReleaseDeb         # or packageReleaseMsi, packageReleaseDmg
```

Tests:

```bash
./gradlew :shared:desktopTest :desktop:test :shared:testDebugUnitTest   # 629
cargo test --manifest-path native/Cargo.toml                            # 6
```

CI runs the suite on Linux, Windows and macOS for every pull request. Tagging `v*` builds every
installer and publishes them to the release.

## How it is put together

`:shared` holds everything that decides what to send and what to do with the answer, plus the
Compose UI. `:app` is Android, `:desktop` is the JVM build, `iosApp` is the Xcode project, and
`native/` is a small Rust library the desktop loads through JNA for hotkeys, clipboard and
keystroke simulation.

The iOS keyboard is SwiftUI rather than Compose on purpose. A keyboard extension gets around 60 MB
before iOS kills it, and Compose's Kotlin/Native runtime spends much of that before drawing
anything.

There is no dependency-injection framework and no navigation library. On Android both entry
activities start cold inside another app's process while you are mid-sentence, and that startup time
is the product.

More on the multiplatform decisions, including the ones that went wrong first, in
[docs/multiplatform.md](docs/multiplatform.md).

## Credits

Plume's desktop half grew out of [MyReviser](https://github.com/paradoxe35/MyReviser), which did
this job with a hotkey before Plume existed. Its Rust layer is the ancestor of `native/`, and its
shortcut defaults are still Plume's, because they had already been proven in daily use. Reading it
closely also turned up the clipboard bugs worth not repeating: a copy that never landed being taken
for a real one, a saved image quietly replaced by text, and the trigger's own modifiers leaking into
the keystrokes that followed.

The desktop leans on work by other people too:

- [rdev](https://github.com/rustdesk-org/rdev), RustDesk's fork, for global shortcuts that also
  work on Wayland
- [arboard](https://github.com/1Password/arboard) for the clipboard
- [enigo](https://github.com/enigo-rs/enigo) for keystroke simulation
- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform), which is why Android,
  the desktop and iOS share one UI
- [ComposeNativeTray](https://github.com/kdroidFilter/ComposeNativeTray) for a tray that follows the
  desktop's own look instead of Java's
- [Ktor](https://ktor.io), [okio](https://github.com/square/okio), DataStore and
  [JNA](https://github.com/java-native-access/jna)

## Status

Android and Linux are the two I use daily. Windows and macOS build and test in CI on every change,
but I have no machines to try them on. iOS is written and not yet compiled.
