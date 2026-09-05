use std::ffi::CString;
use std::os::raw::{c_char, c_int};
use std::sync::Arc;
use std::thread;

use parking_lot::Mutex;
use rdev::{Event, EventType, Key};

// Wayland needs rdev's evdev grab, which requires the user to be in the `input` group. X11,
// macOS and Windows all work through listen() with no special permissions.
#[cfg(not(target_os = "linux"))]
use rdev::listen;
#[cfg(target_os = "linux")]
use rdev::{listen, start_grab_listen};

use super::ffi_types::*;

#[cfg(target_os = "linux")]
fn is_wayland() -> bool {
    // XDG_SESSION_TYPE is authoritative; WAYLAND_DISPLAY is only a fallback.
    if let Ok(session_type) = std::env::var("XDG_SESSION_TYPE") {
        if session_type.to_lowercase() == "wayland" {
            return true;
        }
        if session_type.to_lowercase() == "x11" {
            return false;
        }
    }

    std::env::var("WAYLAND_DISPLAY").is_ok()
}

/// Receives the action string the binding was registered with.
pub type HotkeyCallback = extern "C" fn(*const c_char);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Modifier {
    Ctrl,
    Alt,
    Shift,
    Meta,
}

impl Modifier {
    fn from_name(name: &str) -> Option<Self> {
        match name {
            "ctrl" | "control" => Some(Self::Ctrl),
            "alt" | "option" => Some(Self::Alt),
            "shift" => Some(Self::Shift),
            "meta" | "cmd" | "super" | "win" => Some(Self::Meta),
            _ => None,
        }
    }

    fn from_key(key: &Key) -> Option<Self> {
        match key {
            Key::ControlLeft | Key::ControlRight => Some(Self::Ctrl),
            Key::Alt | Key::AltGr => Some(Self::Alt),
            Key::ShiftLeft | Key::ShiftRight => Some(Self::Shift),
            Key::MetaLeft | Key::MetaRight => Some(Self::Meta),
            _ => None,
        }
    }
}

/// Which modifiers are down. Resolved once when a binding is registered rather than re-derived
/// from strings on every key press, because this runs inside the system's own event callback.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
struct Modifiers {
    ctrl: bool,
    alt: bool,
    shift: bool,
    meta: bool,
}

impl Modifiers {
    fn is_empty(self) -> bool {
        !self.ctrl && !self.alt && !self.shift && !self.meta
    }

    fn get(self, modifier: Modifier) -> bool {
        match modifier {
            Modifier::Ctrl => self.ctrl,
            Modifier::Alt => self.alt,
            Modifier::Shift => self.shift,
            Modifier::Meta => self.meta,
        }
    }

    fn set(&mut self, modifier: Modifier, down: bool) {
        match modifier {
            Modifier::Ctrl => self.ctrl = down,
            Modifier::Alt => self.alt = down,
            Modifier::Shift => self.shift = down,
            Modifier::Meta => self.meta = down,
        }
    }

    fn merged(self, other: Self) -> Self {
        Self {
            ctrl: self.ctrl || other.ctrl,
            alt: self.alt || other.alt,
            shift: self.shift || other.shift,
            meta: self.meta || other.meta,
        }
    }
}

/// The only keys a binding can name, and the names it uses for them.
///
/// One table for both directions: a name the recorder can produce but the listener cannot match is
/// a binding that saves cleanly and then never fires, which is indistinguishable from a refused
/// listener. Registering an unknown name fails instead.
const KEYS: &[(Key, &str)] = &[
    (Key::KeyA, "a"),
    (Key::KeyB, "b"),
    (Key::KeyC, "c"),
    (Key::KeyD, "d"),
    (Key::KeyE, "e"),
    (Key::KeyF, "f"),
    (Key::KeyG, "g"),
    (Key::KeyH, "h"),
    (Key::KeyI, "i"),
    (Key::KeyJ, "j"),
    (Key::KeyK, "k"),
    (Key::KeyL, "l"),
    (Key::KeyM, "m"),
    (Key::KeyN, "n"),
    (Key::KeyO, "o"),
    (Key::KeyP, "p"),
    (Key::KeyQ, "q"),
    (Key::KeyR, "r"),
    (Key::KeyS, "s"),
    (Key::KeyT, "t"),
    (Key::KeyU, "u"),
    (Key::KeyV, "v"),
    (Key::KeyW, "w"),
    (Key::KeyX, "x"),
    (Key::KeyY, "y"),
    (Key::KeyZ, "z"),
    (Key::Num0, "0"),
    (Key::Num1, "1"),
    (Key::Num2, "2"),
    (Key::Num3, "3"),
    (Key::Num4, "4"),
    (Key::Num5, "5"),
    (Key::Num6, "6"),
    (Key::Num7, "7"),
    (Key::Num8, "8"),
    (Key::Num9, "9"),
    (Key::Space, "space"),
    (Key::Return, "return"),
    (Key::KpReturn, "return"),
    (Key::Escape, "escape"),
    (Key::Tab, "tab"),
    (Key::Backspace, "backspace"),
    (Key::Delete, "delete"),
    (Key::Insert, "insert"),
    (Key::Home, "home"),
    (Key::End, "end"),
    (Key::PageUp, "pageup"),
    (Key::PageDown, "pagedown"),
    (Key::UpArrow, "up"),
    (Key::DownArrow, "down"),
    (Key::LeftArrow, "left"),
    (Key::RightArrow, "right"),
    (Key::F1, "f1"),
    (Key::F2, "f2"),
    (Key::F3, "f3"),
    (Key::F4, "f4"),
    (Key::F5, "f5"),
    (Key::F6, "f6"),
    (Key::F7, "f7"),
    (Key::F8, "f8"),
    (Key::F9, "f9"),
    (Key::F10, "f10"),
    (Key::F11, "f11"),
    (Key::F12, "f12"),
];

fn key_name(key: &Key) -> Option<&'static str> {
    KEYS.iter().find(|(known, _)| known == key).map(|(_, name)| *name)
}

fn canonical_key_name(name: &str) -> Option<&'static str> {
    KEYS.iter().find(|(_, known)| *known == name).map(|(_, name)| *name)
}

/// A binding is modifiers, then optionally one key: `ctrl+alt+space`, or `ctrl+cmd` on its own.
///
/// A modifier is required. Without one the binding would fire on ordinary typing.
fn parse_binding(binding: &str) -> Result<(Modifiers, Option<&'static str>), String> {
    let parts: Vec<&str> = binding
        .split('+')
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .collect();
    let Some((last, leading)) = parts.split_last() else {
        return Err("binding is empty".to_string());
    };

    let mut modifiers = Modifiers::default();
    for part in leading {
        let name = part.to_lowercase();
        let modifier =
            Modifier::from_name(&name).ok_or_else(|| format!("'{}' is not a modifier", part))?;
        modifiers.set(modifier, true);
    }

    let name = last.to_lowercase();
    let key = match Modifier::from_name(&name) {
        Some(modifier) => {
            modifiers.set(modifier, true);
            None
        }
        None => Some(canonical_key_name(&name).ok_or_else(|| format!("unknown key '{}'", last))?),
    };

    if modifiers.is_empty() {
        return Err("binding needs a modifier".to_string());
    }
    Ok((modifiers, key))
}

struct HotkeyBinding {
    binding: String,
    action: String,
    callback: HotkeyCallback,
    modifiers: Modifiers,
    /// `None` for a modifier-only binding such as `ctrl+cmd`.
    key: Option<&'static str>,
}

fn fire(binding: &HotkeyBinding) {
    tracing::info!(
        "Hotkey triggered: {} (action: {})",
        binding.binding,
        binding.action
    );
    // Lent, not handed over: `into_raw` leaked one allocation per key press. The host copies
    // during the call.
    if let Ok(action) = CString::new(binding.action.as_str()) {
        (binding.callback)(action.as_ptr());
    }
}

/// What the listener remembers between events.
///
/// A binding with a key fires the moment that key goes down. A modifier-only binding cannot: at
/// the moment `ctrl+cmd` is complete the user may still be reaching for the space bar, and firing
/// there is what made `ctrl+cmd+space` revise the selection as well as open the emoji picker. So a
/// modifier-only binding fires when the combination is released, and only if nothing else was
/// pressed while it was held.
#[derive(Default)]
struct ListenerState {
    held: Modifiers,
    /// The largest modifier set held since the last time every modifier was up.
    chord: Modifiers,
    /// Something beyond the chord's own modifiers happened while it was held.
    interrupted: bool,
    /// The non-modifier key currently down, so auto-repeat is not read as a second press.
    held_key: Option<&'static str>,
    delivery_announced: bool,
    unmatched_announced: Vec<&'static str>,
}

impl ListenerState {
    fn process(&mut self, event: EventType, bindings: &Mutex<Vec<HotkeyBinding>>) {
        match event {
            EventType::KeyPress(key) => self.on_press(key, bindings),
            EventType::KeyRelease(key) => self.on_release(key, bindings),
            EventType::ButtonPress(_) => self.interrupted = true,
            _ => {}
        }
    }

    fn on_press(&mut self, key: Key, bindings: &Mutex<Vec<HotkeyBinding>>) {
        if let Some(modifier) = Modifier::from_key(&key) {
            if self.held.get(modifier) {
                return;
            }
            // The first modifier down begins a chord, whatever was typed before it.
            if self.held.is_empty() {
                self.chord = Modifiers::default();
                self.interrupted = false;
            }
            self.held.set(modifier, true);
            self.chord = self.chord.merged(self.held);
            return;
        }

        let Some(name) = key_name(&key) else {
            self.interrupted = true;
            return;
        };
        if self.held_key == Some(name) {
            return;
        }
        self.held_key = Some(name);
        self.interrupted = true;

        // Once, and without naming the key: proof that key events reach us at all.
        if !self.delivery_announced {
            self.delivery_announced = true;
            tracing::info!("The system is delivering key events to Plume");
        }

        let bindings = bindings.lock();
        let mut fired = false;
        for binding in bindings.iter() {
            if binding.key == Some(name) && binding.modifiers == self.held {
                fire(binding);
                fired = true;
            }
        }
        if !fired {
            self.note_unmatched(name, &bindings);
        }
    }

    fn on_release(&mut self, key: Key, bindings: &Mutex<Vec<HotkeyBinding>>) {
        let Some(modifier) = Modifier::from_key(&key) else {
            if self.held_key == key_name(&key) {
                self.held_key = None;
            }
            return;
        };
        if !self.held.get(modifier) {
            return;
        }

        let before = self.held;
        self.held.set(modifier, false);

        // The first modifier to come up ends the chord; releasing the rest must not fire again.
        if !self.interrupted && before == self.chord {
            for binding in bindings.lock().iter() {
                if binding.key.is_none() && binding.modifiers == before {
                    fire(binding);
                }
            }
        }
    }

    /// Once per key. A bound key seen with modifiers that matched nothing is what tells a wrong
    /// binding apart from a listener the system never delivers to.
    fn note_unmatched(&mut self, name: &'static str, bindings: &[HotkeyBinding]) {
        if self.held.is_empty() || self.unmatched_announced.contains(&name) {
            return;
        }
        if !bindings.iter().any(|binding| binding.key == Some(name)) {
            return;
        }
        self.unmatched_announced.push(name);
        tracing::info!(
            "Saw {} with ctrl={} alt={} shift={} meta={}, which matched no binding",
            name,
            self.held.ctrl,
            self.held.alt,
            self.held.shift,
            self.held.meta
        );
    }
}

pub struct SimpleHotkeyManager {
    bindings: Arc<Mutex<Vec<HotkeyBinding>>>,
    listener_handle: Option<thread::JoinHandle<()>>,
    active: Arc<Mutex<bool>>,
    /// Why the listener never started. It fails on its own thread, and the message would otherwise
    /// go to stdout — which a macOS .app bundle discards, leaving a dead shortcut and no trace.
    listen_error: Arc<Mutex<Option<String>>>,
}

impl SimpleHotkeyManager {
    pub fn new() -> Self {
        Self {
            bindings: Arc::new(Mutex::new(Vec::new())),
            listener_handle: None,
            active: Arc::new(Mutex::new(false)),
            listen_error: Arc::new(Mutex::new(None)),
        }
    }

    pub fn clear_bindings(&mut self) {
        self.bindings.lock().clear();
        tracing::info!("Cleared all hotkey bindings");
    }

    pub fn register(
        &mut self,
        binding: String,
        action: String,
        callback: HotkeyCallback,
    ) -> Result<(), String> {
        let (modifiers, key) = parse_binding(&binding)?;
        tracing::info!("Registered hotkey: {} (action: {})", binding, action);
        self.bindings.lock().push(HotkeyBinding {
            binding,
            action,
            callback,
            modifiers,
            key,
        });
        Ok(())
    }

    pub fn start(&mut self) -> Result<(), String> {
        let mut active = self.active.lock();
        if *active {
            return Ok(());
        }
        *active = true;
        drop(active);

        // rdev offers no way to stop a listener, so `stop` only closes the gate and the thread
        // stays. Spawning another on resume would leave two of them delivering the same key press,
        // and every action would run twice. Suspending and resuming is ordinary — it happens every
        // time a shortcut is recorded.
        if self.listener_handle.is_some() {
            return Ok(());
        }

        let bindings = self.bindings.clone();
        let active_flag = self.active.clone();
        let listen_error = self.listen_error.clone();

        let handle = thread::spawn(move || {
            let mut state = ListenerState::default();
            let mut dispatch = move |event: &Event| {
                if !*active_flag.lock() {
                    return;
                }
                state.process(event.event_type, &bindings);
            };

            #[cfg(target_os = "linux")]
            {
                if is_wayland() {
                    tracing::info!("Wayland session detected, using evdev grab for hotkeys");
                    let callback = move |event: Event| -> Option<Event> {
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            dispatch(&event);
                        }));
                        // Always pass the event through (don't consume it)
                        Some(event)
                    };

                    if let Err(e) = start_grab_listen(callback) {
                        *listen_error.lock() = Some(format!(
                            "Wayland grab failed ({:?}). Add your user to the 'input' group: \
                             sudo usermod -aG input $USER",
                            e
                        ));
                    }
                } else {
                    tracing::info!("X11 session detected, using X11 listener for hotkeys");
                    let callback = move |event: Event| {
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            dispatch(&event);
                        }));
                    };

                    if let Err(e) = listen(callback) {
                        *listen_error.lock() = Some(format!("X11 listener failed ({:?})", e));
                    }
                }
            }

            #[cfg(not(target_os = "linux"))]
            {
                let callback = move |event: Event| {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        dispatch(&event);
                    }));
                };

                if let Err(e) = listen(callback) {
                    *listen_error.lock() = Some(format!(
                        "The system refused the key listener ({:?}). On macOS this is Input \
                         Monitoring; grant it to Plume and restart.",
                        e
                    ));
                }
            }
        });

        self.listener_handle = Some(handle);
        Ok(())
    }

    pub fn stop(&mut self) -> Result<(), String> {
        let mut active = self.active.lock();
        *active = false;
        drop(active);

        // rdev cannot stop a listener, so this only closes the gate; the thread lives until the
        // process exits and is reused on resume.
        Ok(())
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_manager_new() -> HotkeyManagerHandle {
    init_logging();

    let manager = Box::new(SimpleHotkeyManager::new());
    Box::into_raw(manager) as HotkeyManagerHandle
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_clear(handle: HotkeyManagerHandle) -> c_int {
    if handle.is_null() {
        set_last_error("Null hotkey manager handle provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    let manager = &mut *(handle as *mut SimpleHotkeyManager);
    manager.clear_bindings();
    FFIErrorCode::Success as c_int
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_register(
    handle: HotkeyManagerHandle,
    binding: *const c_char,
    action: *const c_char,
    callback: HotkeyCallback,
) -> c_int {
    if handle.is_null() {
        set_last_error("Null hotkey manager handle provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    if binding.is_null() || action.is_null() {
        set_last_error("Null binding or action provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    let manager = &mut *(handle as *mut SimpleHotkeyManager);

    let binding_str = match c_str_to_string(binding) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(format!("Invalid binding string: {}", e));
            return FFIErrorCode::InvalidUtf8 as c_int;
        }
    };

    let action_str = match c_str_to_string(action) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(format!("Invalid action string: {}", e));
            return FFIErrorCode::InvalidUtf8 as c_int;
        }
    };

    match manager.register(binding_str, action_str, callback) {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Hotkey registration failed: {}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_start(handle: HotkeyManagerHandle) -> c_int {
    if handle.is_null() {
        set_last_error("Null hotkey manager handle provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    let manager = &mut *(handle as *mut SimpleHotkeyManager);

    match manager.start() {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Failed to start hotkey listener: {}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_stop(handle: HotkeyManagerHandle) -> c_int {
    if handle.is_null() {
        set_last_error("Null hotkey manager handle provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    let manager = &mut *(handle as *mut SimpleHotkeyManager);

    match manager.stop() {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Failed to stop hotkey listener: {}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

/// Null when the listener is running. The caller frees the string with `plume_free_string`.
#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_listen_error(handle: HotkeyManagerHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }

    let manager = &*(handle as *mut SimpleHotkeyManager);
    match manager.listen_error.lock().clone() {
        Some(message) => string_to_c_str(message),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_manager_free(handle: HotkeyManagerHandle) {
    if !handle.is_null() {
        let _ = Box::from_raw(handle as *mut SimpleHotkeyManager);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rdev::Button;
    use std::ffi::CStr;

    static FIRED: Mutex<Vec<String>> = Mutex::new(Vec::new());
    static SERIAL: Mutex<()> = Mutex::new(());

    extern "C" fn record(action: *const c_char) {
        let text = unsafe { CStr::from_ptr(action) }.to_string_lossy().into_owned();
        FIRED.lock().push(text);
    }

    enum Tap {
        Down(Key),
        Up(Key),
        Click,
    }

    use Tap::{Click, Down, Up};

    /// Feeds `taps` to a listener holding `bindings`, and returns the actions it fired.
    fn fired(bindings: &[(&str, &str)], taps: &[Tap]) -> Vec<String> {
        let _serial = SERIAL.lock();
        FIRED.lock().clear();

        let mut manager = SimpleHotkeyManager::new();
        for (binding, action) in bindings {
            manager
                .register(binding.to_string(), action.to_string(), record)
                .unwrap_or_else(|e| panic!("could not register {}: {}", binding, e));
        }

        let mut state = ListenerState::default();
        for tap in taps {
            state.process(
                match tap {
                    Down(key) => EventType::KeyPress(*key),
                    Up(key) => EventType::KeyRelease(*key),
                    Click => EventType::ButtonPress(Button::Left),
                },
                &manager.bindings,
            );
        }
        FIRED.lock().clone()
    }

    const MAC: &[(&str, &str)] = &[
        ("ctrl+cmd", "revise_selection"),
        ("ctrl+option+space", "revise_all"),
        ("ctrl+option+g", "translate_selection"),
    ];

    /// The macOS default that never fired. Option arrives as `Key::Alt`, the space bar as "space".
    #[test]
    fn ctrl_option_space_revises_everything() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::Alt),
                Down(Key::Space),
                Up(Key::Space),
                Up(Key::Alt),
                Up(Key::ControlLeft),
            ],
        );

        assert_eq!(actions, vec!["revise_all"]);
    }

    #[test]
    fn ctrl_option_g_translates() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::Alt),
                Down(Key::KeyG),
                Up(Key::KeyG),
                Up(Key::Alt),
                Up(Key::ControlLeft),
            ],
        );

        assert_eq!(actions, vec!["translate_selection"]);
    }

    /// A modifier-only binding cannot fire while the combination is still held: at that moment the
    /// user may be on their way to a longer shortcut.
    #[test]
    fn ctrl_cmd_fires_when_the_combination_is_released() {
        let held = fired(MAC, &[Down(Key::ControlLeft), Down(Key::MetaLeft)]);
        assert!(held.is_empty(), "fired while the keys were still down");

        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );
        assert_eq!(actions, vec!["revise_selection"]);
    }

    /// The bug this rule exists for: ctrl+cmd+space opens the macOS emoji picker, and used to
    /// revise the selection on the way there.
    #[test]
    fn ctrl_cmd_space_leaves_the_selection_alone() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Down(Key::Space),
                Up(Key::Space),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert!(actions.is_empty(), "fired on the way to the emoji picker");
    }

    #[test]
    fn a_modifier_only_binding_fires_once_whatever_the_release_order() {
        for (first, second) in [
            (Key::MetaLeft, Key::ControlLeft),
            (Key::ControlLeft, Key::MetaLeft),
        ] {
            let actions = fired(
                MAC,
                &[
                    Down(Key::ControlLeft),
                    Down(Key::MetaLeft),
                    Up(first),
                    Up(second),
                ],
            );
            assert_eq!(actions, vec!["revise_selection"], "releasing {:?} first", first);
        }
    }

    /// The chord begins at the first modifier. Whatever the user typed before it is not part of it.
    #[test]
    fn typing_before_the_chord_does_not_cancel_it() {
        let actions = fired(
            MAC,
            &[
                Down(Key::KeyH),
                Up(Key::KeyH),
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert_eq!(actions, vec!["revise_selection"]);
    }

    /// And a chord that was cancelled must not poison the next one.
    #[test]
    fn a_cancelled_chord_does_not_cancel_the_next() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Down(Key::Space),
                Up(Key::Space),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert_eq!(actions, vec!["revise_selection"]);
    }

    #[test]
    fn an_extra_modifier_cancels_the_modifier_only_binding() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Down(Key::ShiftLeft),
                Up(Key::ShiftLeft),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert!(actions.is_empty(), "an extra modifier still triggered it");
    }

    #[test]
    fn a_click_cancels_the_modifier_only_binding() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Click,
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert!(actions.is_empty(), "ctrl+cmd+click triggered it");
    }

    /// Tapping the command key twice while control is held is two deliberate gestures.
    #[test]
    fn the_chord_can_be_repeated_without_releasing_every_modifier() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
                Up(Key::ControlLeft),
            ],
        );

        assert_eq!(actions, vec!["revise_selection", "revise_selection"]);
    }

    /// Holding the shortcut repeats the key press. One press was one request.
    #[test]
    fn holding_a_shortcut_runs_the_action_once() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::Alt),
                Down(Key::Space),
                Down(Key::Space),
                Down(Key::Space),
                Up(Key::Space),
            ],
        );

        assert_eq!(actions, vec!["revise_all"]);
    }

    #[test]
    fn holding_a_modifier_repeats_neither_state_nor_action() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::MetaLeft),
                Down(Key::MetaLeft),
                Up(Key::MetaLeft),
            ],
        );

        assert_eq!(actions, vec!["revise_selection"]);
    }

    #[test]
    fn a_stray_modifier_refuses_a_key_binding() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlLeft),
                Down(Key::Alt),
                Down(Key::ShiftLeft),
                Down(Key::Space),
            ],
        );

        assert!(actions.is_empty(), "ctrl+option+shift+space matched ctrl+option+space");
    }

    #[test]
    fn the_right_hand_modifiers_are_the_same_modifiers() {
        let actions = fired(
            MAC,
            &[
                Down(Key::ControlRight),
                Down(Key::AltGr),
                Down(Key::Space),
            ],
        );

        assert_eq!(actions, vec!["revise_all"]);
    }

    #[test]
    fn the_linux_and_windows_defaults_still_fire() {
        for binding in ["ctrl+super", "ctrl+win"] {
            let actions = fired(
                &[(binding, "revise_selection")],
                &[
                    Down(Key::ControlLeft),
                    Down(Key::MetaLeft),
                    Up(Key::MetaLeft),
                    Up(Key::ControlLeft),
                ],
            );
            assert_eq!(actions, vec!["revise_selection"], "binding {}", binding);
        }

        let actions = fired(
            &[("ctrl+alt+space", "revise_all")],
            &[Down(Key::ControlLeft), Down(Key::Alt), Down(Key::Space)],
        );
        assert_eq!(actions, vec!["revise_all"]);
    }

    #[test]
    fn a_binding_naming_a_key_the_listener_cannot_see_is_refused() {
        let mut manager = SimpleHotkeyManager::new();
        let error = manager
            .register("ctrl+alt+f13".to_string(), "revise_all".to_string(), record)
            .expect_err("f13 is not a key the listener knows");

        assert!(error.contains("f13"), "unhelpful message: {}", error);
    }

    /// Without a modifier the binding would fire on ordinary typing.
    #[test]
    fn a_binding_without_a_modifier_is_refused() {
        let mut manager = SimpleHotkeyManager::new();
        assert!(manager
            .register("space".to_string(), "revise_all".to_string(), record)
            .is_err());
    }

    #[test]
    fn a_binding_that_names_two_keys_is_refused() {
        let mut manager = SimpleHotkeyManager::new();
        assert!(manager
            .register("ctrl+a+b".to_string(), "revise_all".to_string(), record)
            .is_err());
    }

    #[test]
    fn each_platform_spells_the_same_modifier_its_own_way() {
        let ctrl_meta = Modifiers {
            ctrl: true,
            meta: true,
            ..Modifiers::default()
        };
        for binding in ["ctrl+cmd", "ctrl+super", "ctrl+win", "control+meta"] {
            assert_eq!(parse_binding(binding), Ok((ctrl_meta, None)), "{}", binding);
        }

        let ctrl_alt = Modifiers {
            ctrl: true,
            alt: true,
            ..Modifiers::default()
        };
        for binding in ["ctrl+alt+space", "ctrl+option+space"] {
            assert_eq!(
                parse_binding(binding),
                Ok((ctrl_alt, Some("space"))),
                "{}",
                binding
            );
        }
    }

    /// The names `HotkeyRecorder.keyName` can produce. A name the recorder emits and the listener
    /// cannot match saves cleanly and then never fires, which looks exactly like a refused listener.
    #[test]
    fn every_name_the_recorder_can_produce_is_a_key_the_listener_knows() {
        let recorded = [
            "space", "return", "escape", "tab", "backspace", "delete", "insert", "home", "end",
            "pageup", "pagedown", "left", "right", "up", "down", "a", "z", "0", "9", "f1", "f12",
        ];
        for name in recorded {
            assert!(
                canonical_key_name(name).is_some(),
                "the recorder can produce '{}' and the listener cannot match it",
                name
            );
        }
    }

    /// Suspending and resuming happens every time a shortcut is recorded. rdev's listener cannot be
    /// stopped, so resuming used to spawn a second one beside the first — both delivering the same
    /// key press, and every action running twice.
    #[test]
    fn resuming_reuses_the_listener_thread() {
        let mut manager = SimpleHotkeyManager::new();

        manager.start().expect("start");
        let first = manager.listener_handle.as_ref().expect("a listener").thread().id();

        manager.stop().expect("stop");
        manager.start().expect("resume");
        let second = manager.listener_handle.as_ref().expect("a listener").thread().id();

        assert_eq!(first, second, "resuming spawned a second listener");
    }
}
