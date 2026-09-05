# Going multiplatform

Target: Android, iOS, Windows, macOS, Linux (deb/rpm), from one Kotlin codebase.

This is the plan of record. It exists because the interesting parts of this migration are the
places where sharing _stops_, and those are easy to discover too late.

## What is actually shared

Everything that decides _what to send and what to do with the answer_ is already pure Kotlin and
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

| Platform                | How the user reaches Plume                                                                                |
| ----------------------- | --------------------------------------------------------------------------------------------------------- |
| Android                 | `ACTION_PROCESS_TEXT` in the selection menu, plus the IME panel                                           |
| iOS                     | Keyboard extension (`UIInputViewController` + `UITextDocumentProxy`); no selection-menu equivalent exists |
| Windows / macOS / Linux | Tray icon and a global hotkey, as MyReviser does today                                                    |

`EditorBridge` is the seam. `InputConnectionBridge` (Android) is joined by a
`TextDocumentProxyBridge` (iOS) and a clipboard-and-synthetic-keys bridge (desktop).

### The iOS keyboard extension must not use Compose

An iOS keyboard extension runs in a process with a memory ceiling around **60 MB**, enforced by
silent termination — the keyboard simply disappears. Compose Multiplatform's Kotlin/Native runtime
costs **15–20 MB** before Skia allocates anything.

So the iOS keyboard's UI is **native SwiftUI**: a title, two buttons, a language row, a spinner. It
calls the shared `ImePanelController` through KMP. Compose Multiplatform is still used for the iOS
_container app_ (settings), where there is no such ceiling.

This is the one place the "share the UI" story is knowingly abandoned, and it is abandoned on
purpose.

### What the first iOS compile found

Nothing in `shared/src/iosMain` had ever been through the Kotlin/Native compiler, because the
targets are gated behind a macOS host. The first CI run on a Mac reported 26 errors from four
causes, and they are worth naming because none of them is a typo.

**A protocol is not a class.** `UITextDocumentProxy` is an Objective-C protocol, and cinterop names
those `UITextDocumentProxyProtocol`. Fifteen of the errors were that one wrong type dragging every
member down with it: `insertText`, `deleteBackward`, `documentContextBeforeInput` and the rest all
read as unresolved.

**Dictionary keys are `NSCopying`, not `Any`.** The Security constants are `CFStringRef`, toll-free
bridged to `NSString`, so the pointer only needs re-typing. The catch is ownership:
`CFBridgingRelease` takes it, and these are immortal globals, so it has to be paired with a
`CFRetain` to leave the retain count where it started.

**`OSStatus` is `Int` and `noErr` is `UInt`.** Comparing them does not compile.

**`NSFileManager` has no `temporaryDirectory`** in the bindings. It is the free function
`NSTemporaryDirectory()`.

The compiler also missed one, which is the more interesting half. The dictionary *values* were
`CFStringRef` cast to `Any`, which compiles and is wrong: Objective-C would receive a Kotlin
wrapper rather than a string, and the Keychain query would have failed at runtime with nothing to
read in the log. Those go through the same bridging now, and the `kSecReturnData` flag is an
explicit `NSNumber` rather than a Kotlin `Boolean` left to bridge itself.

The lesson is the one the gating hides: code that compiles for three platforms is not evidence it
compiles for a fourth. A `java.util.Locale` sat in `commonMain` for weeks for the same reason.

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

`panic` also moves from `abort` to `unwind`. The hotkey callbacks already wrap themselves in
`catch_unwind`, which `panic = "abort"` makes dead code. Inside the JVM that would take the whole
app down with no stack trace.

### What MyReviser got wrong, and what Plume does instead

Carrying the crate over meant reading it, and the clipboard dance had real defects. They are worth
naming because every one of them is invisible in the happy path and ruins the user's text when it
misfires.

**A copy that never landed is indistinguishable from a successful one.** The Go processor saved the
clipboard, simulated copy, slept 150 ms, then read the clipboard. If the copy did not land — the app
was busy, focus moved, the Wayland grab was slow — the clipboard still held _its previous contents_,
so the processor cheerfully revised whatever was there before and pasted it over the user's
selection. No sleep length fixes this, because nothing is being checked.

Plume clears the clipboard before simulating the copy and then polls until it changes, with a
deadline. "The copy landed" becomes observable instead of assumed, and the empty-selection case
reports honestly rather than mangling unrelated text.

**Restore silently discarded non-text clipboard contents.** `save` stored `Option<String>`, so a
clipboard holding an image saved as `None`, and `restore` treated `None` as "nothing to do" — which
left _Plume's_ text on the clipboard after having destroyed the image. Plume distinguishes empty
from foreign content and clears rather than leaving its own text behind, so a borrow can never
become a silent overwrite.

**The hotkey's own modifiers leaked into the synthetic keystrokes.** Triggering on Ctrl+Alt+R and
then simulating Ctrl+A while Alt is still physically held sends Ctrl+Alt+A. MyReviser slept 250 ms
at the top of each operation and hoped the user had let go.

On Linux and Windows the simulator releases the held modifiers instead, and releases Control even if
the letter keystroke fails, so a failure cannot leave the desktop with a stuck modifier.

macOS cannot do that: a physically held key is not released by posting an event, and the system
merges the live hardware flags into whatever is posted — Cmd+A sent while Ctrl+Option are still down
arrives as Ctrl+Option+Cmd+A and selects nothing. This was written as a no-op returning `Ok`, which
read as "handled" and was not: "revise everything" quietly selected nothing and reported that the
copy never landed, while "translate" — which sends no Cmd+A — worked. It now reads the live modifier
flags and waits for them to clear, returning the moment the user lets go rather than sleeping a
fixed guess, and giving up after 400 ms so a stuck modifier cannot hang the action.

**A Tokio runtime was built and destroyed on every clipboard call** — to await a lock that never
yields. Beyond the waste on a latency-sensitive path, `block_on` panics if the calling thread
already drives a runtime; the JVM calls in from whatever thread it likes. The clipboard is plain
synchronous code now and the Tokio dependency is gone.

**Re-entrancy was a single bool.** Plume serialises desktop actions and refuses a second one while
the first holds the clipboard, since two overlapping runs fight over one global resource.

**The flow is the same as Android's, with a different bridge.** There is no `InputConnection` on the
desktop, so the `EditorBridge` implementation is: save clipboard → clear → simulate select-all and
copy → wait for the clipboard to actually change → send to the model → set clipboard → simulate
paste → restore clipboard.

### Desktop needs platform permissions, and they need UI

None of this works silently, and each platform fails differently. The desktop settings must state
plainly where it stands and how to fix it — the same treatment the Android keyboard checklist gets.

| Platform        | Requirement                                        | Failure mode without it                                |
| --------------- | -------------------------------------------------- | ------------------------------------------------------ |
| macOS           | Accessibility permission (TCC)                     | Hotkeys never fire; must be granted in System Settings |
| Linux / Wayland | User in the `input` group (`rdev` grabs via evdev) | Hotkeys never fire until re-login after `usermod`      |
| Linux / X11     | None                                               | Works out of the box                                   |
| Windows         | None                                               | Works out of the box                                   |

MyReviser detects the session with `XDG_SESSION_TYPE`, falling back to `WAYLAND_DISPLAY`, and picks
`start_grab_listen` on Wayland versus `listen` on X11. It also has a macOS permission prompt and a
"open Accessibility preferences" deep link. All of that is carried over.

### One process, and the restart that keeps it that way

Granting a macOS permission is only half the fix: the shortcut listener is wired once at launch, so
the privilege has to be picked up by a new process. That restart turned out to touch four things
that each fail on their own.

**Two copies must not run at once.** Both would register the same global shortcuts, so which one
answers a keypress is a race, and both write the same settings file. With no window on screen there
is nothing to notice. Plume takes an exclusive lock on `instance.lock` in its config directory — a
lock rather than a pid file, because the operating system releases it however the process ends,
including a crash — and listens on a loopback port whose number it writes beside the lock. A second
launch fails the lock, connects to that port, says `show`, and stops. Clicking the launcher while
Plume is already running now raises its window instead of doing nothing.

**The replacement has to wait for the old process to be gone.** Starting it immediately leaves two
Plumes alive, and the new one would meet the lock above and stop again — so the restart button would
appear to do nothing. The launch is handed to a small detached shell that polls for the old pid to
disappear, then runs the launcher. Windows has no `sh`, so it is `Wait-Process` in PowerShell.

**And the old process has to actually exit.** `exitApplication` closes the window and returns; the
JVM stays up. Restart calls `exitProcess`, which is the only thing the waiting shell can observe.

**Closing the native handles twice aborts the process.** Quitting, closing the window and restarting
all shut the controller down, so being closed twice is ordinary rather than exotic — and the Rust
side rebuilds a `Box` from the pointer, so the second free hands back malloc memory that is no
longer ours. That is a `SIGABRT` with no Kotlin stack anywhere in it, and it is what a restart used
to produce. `close` clears the handles as it frees them, which also means a late hotkey action
cannot reach one.

### Desktop-only settings and features

Things the Android build has no concept of, which the desktop needs:

- **Hotkey bindings** — revise selection, revise the whole field, and translate; MyReviser's
  defaults are a sensible start, and the translate binding is new. Each is rebindable, and a
  binding already taken by another action is rejected rather than silently shadowing it.
- **Permission status** — granted / not granted, with the button that fixes it.
- **Start on login**, **start minimised**, **close to tray**.
- **Character limit and timeout** already exist and stay shared.

Beyond parity, a few things only make sense once there is a always-running tray process:

- **A result notification.** On Android the replacement happens under the user's eyes. On the
  desktop the hotkey fires into whatever app has focus, so success, failure and "nothing was
  selected" have to be visible without stealing focus — a tray notification, not a window.
- **A translate target chosen without a window.** The tray menu carries the pinned languages, so
  translating does not mean opening settings.
- **History of the last few runs**, with the original text. This is the desktop's version of undo:
  the paste went into someone else's app and Plume cannot reach into it, but it can always tell the
  user what the original text was so they can put it back.
- **A visible busy state.** A reasoning model can take a minute, and a hotkey that appears to do
  nothing for a minute reads as broken. The tray icon reflects it.

### Secrets differ everywhere

`SecretStore` becomes `expect`/`actual`:

| Platform | Backing                                                       |
| -------- | ------------------------------------------------------------- |
| Android  | Tink + Android Keystore (already built)                       |
| iOS      | Keychain                                                      |
| macOS    | Keychain                                                      |
| Windows  | DPAPI / Credential Manager                                    |
| Linux    | Secret Service (libsecret), falling back to an encrypted file |

### Smaller seams

- **HTTP** — OkHttp gives way to Ktor (3.5.2), one engine per platform: OkHttp on Android and the
  JVM, Darwin on iOS. Tests move to Ktor's `MockEngine`, which runs in `commonTest` rather than
  only on the JVM as MockWebServer did.
- **Settings storage** — DataStore supports KMP, but officially only _Preferences_. Plume uses a
  typed store with a custom serializer, so it goes through `datastore-core-okio`, whose
  `OkioSerializer` works on every target. Only the file location is `expect`/`actual`.
- **`Languages`** — `java.util.Locale` is JVM-only; display names need an `expect`/`actual`.
- **Clipboard** — trivial but platform-specific.

### Compose Multiplatform is pinned to 1.11.x, and compileSdk stays at 36

CMP 1.12 bundles Jetpack Compose 1.12, which refuses to be consumed below **compileSdk 37**. That
in turn needs **AGP 9**, and AGP 9 is not a version bump — it removes the separate Kotlin plugin in
favour of built-in Kotlin, and it rejects `com.android.library` on a Kotlin Multiplatform module
outright, requiring `com.android.kotlin.multiplatform.library` and its different DSL.

That is a migration in its own right, and stacking it on top of this one would mean debugging two
unrelated things at once. CMP **1.11.1** resolves to Jetpack Compose 1.11.4, which is current — this
is a sequencing decision, not a stale dependency. The AGP 9 move is worth doing on its own branch,
where a failure is legible.

### Material icons are a dead end, so Plume ships its own

JetBrains stopped publishing `org.jetbrains.compose.material:material-icons-extended` after
**1.7.3**, and androidx deprecated its equivalent: shipping a few thousand pre-bundled icons fights
resource shrinking, and Material 3 is meant to be icon-agnostic. Pinning the last release against a
1.12 runtime is exactly the kind of stale dependency worth avoiding.

Plume uses 29 icons. They become `ImageVector` definitions in the shared module — no dependency, no
deprecation, identical on all five platforms, and a fraction of the size.

## Platform-specific UI, not just platform-specific API

Sharing the settings screens does not mean pretending the platforms are the same:

- **Desktop** gets a tray icon, a menu, a hotkey preferences pane, and launch-at-login. Compose
  Multiplatform has a built-in `Tray`; `ComposeNativeTray` is the better option for HDPI on Windows
  and for Linux appearance.
- **Android** keeps the keyboard checklist and the selection-menu explanation, both meaningless
  elsewhere.
- **iOS** gets its own enable-the-keyboard walkthrough, including the "Allow Full Access" step that
  network access depends on.

## Modules

```
shared/     commonMain: ai, data, panel, ui (settings screens, theme, icons)
            androidMain / desktopMain / iosMain: secrets, locale, HTTP engine, platform stores
app/        Android: activities, the IME service, the selection-menu entries
desktop/    Compose Desktop: tray, shortcuts, window, packaging
native/     the Rust hotkey/clipboard/keystroke library
iosApp/     Xcode targets: container app + SwiftUI keyboard extension
```

The settings screens are one copy, driven by `SettingsNavHost` in `commonMain`. Each platform
passes in the rows and screens only it has — the companion keyboard on Android, shortcuts and
history on the desktop, the Full Access walkthrough on iOS — and shares everything else.

## Stages

Each stage left the Android app shipping and its tests green. That constraint was the point: the
Android app is device-verified and must not spend weeks broken to reach a second platform.

1. **Logic to `commonMain`.** `:shared` with `ai/`, `data/` and the panel controller, OkHttp
   swapped for Ktor, tests moved to `commonTest`. Done.
2. **Secrets and settings per platform.** `SecretStore` is an interface with a Keystore, Keychain,
   DPAPI and Secret Service implementation behind it; settings moved to okio-backed DataStore.
   Done.
3. **Compose Multiplatform for the shared UI.** Settings screens, theme and icons in `commonMain`.
   Done.
4. **Desktop app.** Tray, shortcuts, history, `.deb` built and verified. Done.
5. **iOS.** Container app over the shared screens; keyboard extension in SwiftUI over the shared
   controller. Written, not compiled — see below.

## What this machine can and cannot build

Worth stating plainly, because it shapes what can be claimed:

- **Android**: fully built and tested here, including on the connected device.
- **Linux desktop**: built, tested, and packaged to a real `.deb`. The Rust library builds and its
  symbols are exercised through JNA by a test.
- **`.rpm`**: needs `rpmbuild`, which is not installed here. The Gradle configuration is in place.
- **Windows and macOS**: `nativeDistributions` uses jpackage, which only produces packages for the
  host it runs on, so `.msi` and `.dmg` need a runner per OS. The Rust crate also has to be built
  per platform.
- **iOS**: Kotlin/Native for Apple targets requires macOS and Xcode. The Apple targets are declared
  only on a macOS host, so nothing under `iosMain/` or `iosApp/` has been compiled. It is written
  against the documented APIs and should be treated as unverified until it builds on a Mac.

## Packaging

`nativeDistributions` covers `.dmg`/`.pkg`, `.exe`/`.msi` and `.deb`/`.rpm` through jpackage.

The Rust library travels inside the jar at JNA's own resource prefix
(`linux-x86-64/libplume_native.so` and so on) rather than beside the binary. That way one mechanism
covers `gradlew run`, the app image and every installer, with no library path to set and nothing
for the jpackage step to drop.

The Xcode project is generated by XcodeGen from `iosApp/project.yml` rather than committed: a
`.pbxproj` is thousands of lines of generated state that merges badly and hides mistakes.

### The icon has three separate jobs, and they fail independently

`iconFile` in `nativeDistributions` only reaches the launcher and the desktop entry. It says
nothing about the running window or the tray, and each of those was wrong in its own way.

**The window and the taskbar** read `java.awt.Window.iconImages`, a *set*. Handing over a single
bitmap through Compose's `icon` parameter leaves the dock and the switcher to resample it, which is
the "large image squeezed into a small view" look. `WindowIcon.kt` draws 16 through 256 px, each
from the path data, and the set is applied again on `windowOpened`: the effect runs before the
window is realised, and X11 reads the icon when the peer is created. Until that lands the window
advertises Duke, the JDK's own default frame icon.

**The launcher and the dash** read the freedesktop icon theme. jpackage installs one 256 px icon
and leaves every desktop to shrink it. `generate.py` renders a `hicolor` set instead, and the
`.deb` rewrite packages it — dpkg then owns those files and removes them, rather than a maintainer
script copying them in and having to remember to take them away.

**The tray** takes the mark as an `ImageVector`, which the library fits to a 192 px scene and then
resamples to what the platform's panel wants: 24 px on Linux, 32 on Windows, 44 on macOS.
`IconRenderProperties.withoutScalingAndAliasing()` reads like the right call and is the wrong one —
it sets the target equal to the scene, handing the panel the whole 192 px image.

The artwork is drawn from path data rather than loaded from a resource because ProGuard dropped the
PNG from the minified jar: the icon worked from Gradle and vanished in the installed package, which
is the worst way for it to fail.

Rebuilding the `.deb` needs `dpkg-deb --root-owner-group`. Unpacking as an ordinary user rewrites
every file to that user and rebuilding records it, so the installed application would be owned by
whoever holds uid 1000 on the target machine rather than by root.

### Opening the Shortcuts screen switched the shortcuts off

Recording a shortcut has to suspend the global listener, or pressing the combination you are
rebinding fires the action bound to it and the capture field never sees the keys. The screen
reports what its capture fields are doing — `onRecordingChange(recording != null)` — and the
controller took that as what the *listener* should do. The two readings are opposites.

So the effect fired as the screen appeared, with nothing recording, and the listener was told to
stop. Nothing turned it back on: leaving the screen only disposed the effect. One visit to
Shortcuts and every binding was dead until Plume restarted, which is indistinguishable from a
binding that was never registered. The argument is named for the capture field now, the direction
goes through a function whose name says which way round it is, and leaving the screen resumes.

Underneath it, a second one. rdev offers no way to stop a listener, so `stop` only sets a flag and
the thread stays; `start` then spawned another beside it. Two listeners deliver the same key press,
so every action would have run twice — after a rebind, which is the least likely moment to connect
the two. `start` reuses the thread it already has.

The Rust hotkey layer is otherwise byte-identical to MyReviser's, renamed symbols aside. When the
same native code behaves differently in two applications, it is worth reading the caller first.

### The macOS launch crash was two bugs holding hands

A crash about a second after launch, `EXC_BREAKPOINT` from `+[NSApplication _crashOnException:]`,
with no Java stack anywhere in the report. Two independent facts, both confirmed in source:

**AWT's event thread was blocked in `nativeGetScreenInsets`.** `WindowPosition.Aligned` calls
`Toolkit.getScreenInsets` while placing the window, and `CGraphicsDevice.getScreenInsets` carries
the comment *"the insets are queried synchronously and are not cached"* — it hops to the AppKit
thread and waits. If AppKit is busy, that is a stall at the exact moment the window is created, and
JBR has an open issue about the freezes it causes (JBR-2602). The window is placed from screen
*bounds* now, which need no such round trip; the cost is ignoring the menu bar and the Dock, worth
a few pixels.

**AppKit was inside a Core Animation commit, drawing an OpenGL layer.** Java2D's OpenGL pipeline is
the default on macOS up to JDK 18 — Metal became the default in JDK 19 — and its
`-[CGLLayer drawInCGLContext:]` is not wrapped in `JNI_COCOA_ENTER`/`EXIT`. Its `CHECK_EXCEPTION()`
therefore *raises an `NSException`* for any pending Java exception with nothing to catch it, having
cleared the Java exception first. The JDK header says as much: *"control will propagate back to the
run loop which might terminate the application… the location of termination does not show where the
NSException originated."* That is the whole crash report, explained. Plume asks for Metal at
startup, and the code path stops existing.

What set them off is inference rather than fact: switching the macOS activation policy makes the
menu bar and Dock appear, which invalidates exactly the screen metrics AWT was blocked asking for.
Activation now waits for `windowOpened` instead of firing when the window is merely wanted. That
ordering is defensible on its own, and it is not a proven cause.

### The settings window is what puts Plume in the dock, everywhere

One rule on all three platforms, and only one of them needs code for it.

**Windows and Linux need none.** The window is the taskbar entry: it arrives when the window opens
and goes when it closes, because closing to the tray destroys the window rather than hiding it.

**Linux needs an identity, though.** A dock decides which launcher a window belongs to by matching
`WM_CLASS` against the desktop entry's `StartupWMClass`, and AWT names the window after the class
holding the bottom stack frame with its dots turned into dashes — `me-pngwasi-plume-desktop-MainKt`,
measured with `xprop`, with no supported way to change it. Without that line the dash shows the
pinned launcher and an unmatched window side by side. The `.deb` rewrite substitutes it from the
`mainClass` property rather than spelling it out in the maintainer script, so renaming the entry
point cannot quietly break the match. **The `.rpm` does not get this**: jpackage takes desktop-entry
overrides through `--resource-dir`, and the Compose plugin owns that directory as a derived
`Provider<Directory>` with no setter, so there is nowhere to put one — and unlike the `.deb`, an
`.rpm` cannot be unpacked and rebuilt without `rpmbuild`.

**macOS is the one that needs code.** A menu-bar app is an accessory process with no Dock entry at
all, and showing a window does not create one; the activation policy has to follow the window.

### CI is where Windows and macOS actually get built

`.github/workflows/build-desktop.yml` runs on every push to `feature/multiplatform`, across
`ubuntu-latest`, `windows-latest`, `macos-latest` and `macos-15-intel`. Each job builds the Rust
crate natively, runs the tests, and packages for its own host. `fail-fast` is off — the point is
finding out which platforms work, not stopping at the first that does not.

**Two Macs, not one.** jpackage builds for the host and bundles a JVM for that architecture, and
the Rust library is compiled for it as well, so nothing it produces is universal. `macos-latest` is
Apple silicon, so for a while the only DMG on offer would not open on an Intel Mac at all. Intel
needs its own runner: `macos-13` was retired in December 2025, and `macos-15-intel` is the
replacement. Artifacts are named by architecture rather than by "macOS" so the two cannot be
confused for one another.

Three things it has to get right, none of them obvious:

- **`shell: bash` for every step.** Windows runners default to PowerShell, where `./gradlew` picks
  the extensionless shell script and fails.
- **WiX 3, pinned.** jpackage shells out to `candle.exe` and `light.exe`, which exist only in WiX 3
  — it does not support 4 or 5 (JDK-8319457). The binaries are downloaded rather than taken from
  the runner image, so a change to that image cannot break the build quietly.
- **AppImage is not a jpackage target.** `.github/scripts/build-appimage.sh` wraps the jpackage app
  image instead, since `.deb` and `.rpm` both need a package manager and root, and an AppImage is
  the one Linux format a user can simply download and run.

CI compiles the iOS targets on every pull request, because nothing else does: they are gated
behind a macOS host, so on a Linux development machine `shared/src/iosMain` is never type-checked
at all. That gap is how a `java.util.Locale` sat in shared code for weeks. The job compiles for
device and simulator, links the framework, then runs `xcodegen` and an unsigned `xcodebuild` so the
Swift side and the extension are checked too. It is `continue-on-error` until iOS builds green,
since a platform that has never compiled should not block work on the ones that ship.

The Rust crate's Windows and macOS builds were verified from Linux with `cargo check --target`
before any of this ran: `enigo`'s `wayland`/`x11rb` features and `arboard`'s `wayland-data-control`
look Linux-only, but both crates gate those dependencies by target, so enabling them elsewhere is
harmless. That was the assumption most likely to break the other two platforms.

### Where the download size goes

A self-contained desktop app carries its own runtime, and that is most of it. Uncompressed:

| | Size |
|---|---|
| Bundled JVM (`lib/modules` + `libjvm.so`) | 68 MB |
| Skia, via `libskiko-linux-x64.so` | 28 MB |
| Compose and Kotlin jars | ~15 MB |
| Plume, including the Rust library | ~4 MB |

So roughly three quarters is the JVM and Skia, neither of which packaging choices can touch. The
jlink module set is already minimal — `java.base`, `java.datatransfer`, `java.desktop`,
`java.logging`, `java.prefs`, `java.xml`, `jdk.crypto.ec` — and `java.desktop` is the large one
Compose cannot do without.

`packageRelease*` runs ProGuard over the app's own jars and takes the `.deb` from 60 MB to 45 MB.
**Optimisation is disabled** in `desktop/proguard-rules.pro`, and not out of caution: with it on,
ProGuard rewrote `okio`'s `Okio__JvmOkioKt.source` to return `okio.Source` where the signature
says `InputStreamSource`, and the JVM threw `VerifyError: Bad return type` the first time DataStore
read the settings file — on a background thread, so the app started and simply had no settings.
Shrinking alone is where the size comes from.

### The desktop app has been run, not just built

Under `Xvfb` with `SKIKO_RENDER_API=SOFTWARE`, both the plain and minified Linux builds start,
load settings, load the Rust library through JNA, register all three hotkeys, detect the X11
session, and render the settings window. That covers the whole chain end to end on Linux. Windows
and macOS are still only compiled, not run.

The window icon is checked the same way, by reading `_NET_WM_ICON` off the running window with
`xprop` rather than by looking at the code. It is worth doing literally: the property is the only
place that shows which of the competing icons actually won, and every reading taken before the
window had finished composing was of the JDK default instead — under software rendering that takes
around twenty-five seconds, long enough to make a passing check look like a failing one.

The desktop's key storage falls back to the encrypted file whenever the platform store fails a
write-read-delete probe at startup. "The tool is installed" is not "the tool works" — a locked
keychain or a refusing keyring daemon fails on write, and saving a key is fire-and-forget from the
UI, so the user would only find out when their provider stopped being configured.
