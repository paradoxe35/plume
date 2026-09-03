# Going multiplatform

Target: Android, iOS, Windows, macOS, Linux (deb/rpm), from one Kotlin codebase.

This is the plan of record. It exists because the interesting parts of this migration are the
places where sharing *stops*, and those are easy to discover too late.

## What is actually shared

Everything that decides *what to send and what to do with the answer* is already pure Kotlin and
moves to `commonMain` unchanged:

- `ai/` — providers, reasoning dialects and the fallback ladder, response cleaning, `TextEngine`
- `data/` — `AppSettings`, `Prompts`, `Languages`, validation
- `ime/ImePanelController` — the panel state machine; it has no Android types today
- `ime/EditorBridge` — already an interface, with the platform implementation behind it

The Compose UI (settings screens, the action panel) is shared everywhere **except** the iOS
keyboard extension. See below.

## Where sharing stops, and why

### The invocation mechanism is different on every platform

This is the product, and none of it is portable.

| Platform | How the user reaches Plume |
|---|---|
| Android | `ACTION_PROCESS_TEXT` in the selection menu, plus the IME panel |
| iOS | Keyboard extension (`UIInputViewController` + `UITextDocumentProxy`); no selection-menu equivalent exists |
| Windows / macOS / Linux | Tray icon and a global hotkey, as MyReviser does today |

`EditorBridge` is the seam. `InputConnectionBridge` (Android) is joined by a
`TextDocumentProxyBridge` (iOS) and a clipboard-and-synthetic-keys bridge (desktop).

### The iOS keyboard extension must not use Compose

An iOS keyboard extension runs in a process with a memory ceiling around **60 MB**, enforced by
silent termination — the keyboard simply disappears. Compose Multiplatform's Kotlin/Native runtime
costs **15–20 MB** before Skia allocates anything.

So the iOS keyboard's UI is **native SwiftUI**: a title, two buttons, a language row, a spinner. It
calls the shared `ImePanelController` through KMP. Compose Multiplatform is still used for the iOS
*container app* (settings), where there is no such ceiling.

This is the one place the "share the UI" story is knowingly abandoned, and it is abandoned on
purpose.

### Desktop: reuse MyReviser's Rust layer rather than re-solving it

System-wide hotkeys are not a JVM capability and JVM library support is thin, so the desktop build
does not try. MyReviser already has a working native layer and it is the right thing to carry over
rather than reinvent — it is small, it is proven on three platforms, and the hard parts are the
platform quirks it already handles.

Its C contract is twenty functions across three concerns:

```
clipboard_new / get_text / set_text / save / restore / free
hotkey_manager_new / clear / register(binding, action, callback) / start / stop / free
simulator_new / simulate_select_all / simulate_copy / simulate_paste / free
get_last_error / free_string
```

Built on `rdev` (a RustDesk fork, for Wayland support), `arboard` for clipboard and `enigo` for
keystroke simulation.

**What changes for Kotlin.** The crate is currently `crate-type = ["staticlib"]` for Go's cgo. The
JVM needs a dynamic library, so it becomes `cdylib`, loaded through **JNA** — which maps the C
functions to a Kotlin interface directly and supports the `void(*)(const char*)` hotkey callback
without any hand-written glue. No C shim, no JNI boilerplate.

**The flow is the same as Android's, with a different bridge.** There is no `InputConnection` on the
desktop, so the `EditorBridge` implementation is: save clipboard → simulate select-all and copy →
read clipboard → send to the model → set clipboard → simulate paste → restore clipboard. MyReviser's
processor already sequences this, including the sleeps that make it reliable.

### Desktop needs platform permissions, and they need UI

None of this works silently, and each platform fails differently. The desktop settings must state
plainly where it stands and how to fix it — the same treatment the Android keyboard checklist gets.

| Platform | Requirement | Failure mode without it |
|---|---|---|
| macOS | Accessibility permission (TCC) | Hotkeys never fire; must be granted in System Settings |
| Linux / Wayland | User in the `input` group (`rdev` grabs via evdev) | Hotkeys never fire until re-login after `usermod` |
| Linux / X11 | None | Works out of the box |
| Windows | None | Works out of the box |

MyReviser detects the session with `XDG_SESSION_TYPE`, falling back to `WAYLAND_DISPLAY`, and picks
`start_grab_listen` on Wayland versus `listen` on X11. It also has a macOS permission prompt and a
"open Accessibility preferences" deep link. All of that is carried over.

### Desktop-only settings

Things the Android build has no concept of, which the desktop needs:

- **Hotkey bindings** — one for "revise selection", one for "select all and revise"; MyReviser's
  defaults are a sensible start, and a translate binding is new.
- **Permission status** — granted / not granted, with the button that fixes it.
- **Start on login**, **start minimised**, **close to tray**.
- **Character limit and timeout** already exist and stay shared.

### Secrets differ everywhere

`SecretStore` becomes `expect`/`actual`:

| Platform | Backing |
|---|---|
| Android | Tink + Android Keystore (already built) |
| iOS | Keychain |
| macOS | Keychain |
| Windows | DPAPI / Credential Manager |
| Linux | Secret Service (libsecret), falling back to an encrypted file |

### Smaller seams

- **HTTP** — OkHttp gives way to Ktor, one engine per platform.
- **Settings storage** — DataStore supports KMP, but officially only *Preferences*. Plume uses a
  typed store with a custom serializer, so this needs the okio-based `DataStoreFactory` or a small
  hand-rolled JSON store.
- **`Languages`** — `java.util.Locale` is JVM-only; display names need an `expect`/`actual` or a
  multiplatform locale library.
- **Clipboard** — trivial but platform-specific.

## Platform-specific UI, not just platform-specific API

Sharing the settings screens does not mean pretending the platforms are the same:

- **Desktop** gets a tray icon, a menu, a hotkey preferences pane, and launch-at-login. Compose
  Multiplatform has a built-in `Tray`; `ComposeNativeTray` is the better option for HDPI on Windows
  and for Linux appearance.
- **Android** keeps the keyboard checklist and the selection-menu explanation, both meaningless
  elsewhere.
- **iOS** gets its own enable-the-keyboard walkthrough, including the "Allow Full Access" step that
  network access depends on.

## Stages

Each stage leaves the Android app shipping and its tests green. That constraint is the point: the
Android app is device-verified today and must not spend weeks broken to reach a second platform.

1. **Logic to `commonMain`.** Create `:shared`, move `ai/` and `data/` and the panel controller,
   swap OkHttp for Ktor, move the tests to `commonTest`. Android UI untouched.
2. **Secrets and settings behind `expect`/`actual`.** Still Android-only, but the seams exist.
3. **Compose Multiplatform for the shared UI.** Move the settings screens to
   `org.jetbrains.compose`. Android continues to consume them.
4. **Desktop app.** Tray, hotkey (prototype first), packaging to dmg/msi/deb/rpm via
   `nativeDistributions`. This is where MyReviser is retired.
5. **iOS.** Container app in Compose Multiplatform; keyboard extension in SwiftUI over the shared
   controller.

## What this machine can and cannot build

Worth stating plainly, because it shapes how the work can be verified:

- **iOS and macOS targets cannot be compiled here.** Kotlin/Native for Apple platforms requires
  macOS and Xcode; this is a Linux VM. Apple-target code can be written, but not built or tested
  without a Mac.
- **Installers are host-specific.** `nativeDistributions` uses jpackage, which only produces
  packages for the OS it runs on: `.deb`/`.rpm` here, `.msi` on Windows, `.dmg` on macOS. Shipping
  all five means CI with a runner per OS.

Android and a Linux desktop build are fully verifiable on this machine, including on the connected
device.

## Packaging

`nativeDistributions` covers `.dmg`/`.pkg`, `.exe`/`.msi` and `.deb`/`.rpm` through jpackage, so
Linux packaging is a build-config exercise rather than new code.
