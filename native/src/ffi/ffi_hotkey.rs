use std::os::raw::{c_char, c_int};
use std::sync::Arc;
use std::thread;

use parking_lot::Mutex;
use rdev::{Event, EventType, Key};

// On Linux: use listen() for X11 (no special permissions needed)
//           use start_grab_listen() for Wayland (needs input group)
// On macOS/Windows: use listen() which works natively
#[cfg(not(target_os = "linux"))]
use rdev::listen;
#[cfg(target_os = "linux")]
use rdev::{listen, start_grab_listen};

use super::ffi_types::*;

/// Detect if running on Wayland
#[cfg(target_os = "linux")]
fn is_wayland() -> bool {
    // Check XDG_SESSION_TYPE first (most reliable)
    if let Ok(session_type) = std::env::var("XDG_SESSION_TYPE") {
        if session_type.to_lowercase() == "wayland" {
            return true;
        }
        if session_type.to_lowercase() == "x11" {
            return false;
        }
    }

    // Fallback: check if WAYLAND_DISPLAY is set
    std::env::var("WAYLAND_DISPLAY").is_ok()
}

/// Hotkey callback function type
/// The callback receives the action string that was registered
pub type HotkeyCallback = extern "C" fn(*const c_char);

/// Simple hotkey manager that doesn't require async dependencies
pub struct SimpleHotkeyManager {
    bindings: Arc<Mutex<Vec<HotkeyBinding>>>,
    listener_handle: Option<thread::JoinHandle<()>>,
    active: Arc<Mutex<bool>>,
}

struct HotkeyBinding {
    binding: String,
    action: String,
    callback: HotkeyCallback,
    modifiers: Vec<String>,
    key: String,
}

impl SimpleHotkeyManager {
    pub fn new() -> Self {
        Self {
            bindings: Arc::new(Mutex::new(Vec::new())),
            listener_handle: None,
            active: Arc::new(Mutex::new(false)),
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
        // Parse binding (e.g., "ctrl+alt+space" or "ctrl+win")
        let parts: Vec<&str> = binding.split('+').map(|s| s.trim()).collect();
        if parts.is_empty() {
            return Err("Empty binding".to_string());
        }

        let mut modifiers = Vec::new();
        let key;

        if parts.len() == 1 {
            key = parts[0].to_lowercase();
        } else {
            // Check if last part is a modifier (for modifier-only hotkeys like ctrl+win)
            let last_part = parts[parts.len() - 1].to_lowercase();
            let is_modifier = last_part == "ctrl"
                || last_part == "control"
                || last_part == "alt"
                || last_part == "option"
                || last_part == "shift"
                || last_part == "meta"
                || last_part == "cmd"
                || last_part == "super"
                || last_part == "win";

            if is_modifier && parts.len() >= 2 {
                // All parts are modifiers (e.g., "ctrl+win")
                modifiers = parts.iter().map(|s| s.to_lowercase()).collect();
                key = String::new(); // Empty key means modifier-only binding
            } else {
                // Normal binding with modifiers + key
                modifiers = parts[..parts.len() - 1]
                    .iter()
                    .map(|s| s.to_lowercase())
                    .collect();
                key = last_part;
            }
        }

        let binding_obj = HotkeyBinding {
            binding: binding.clone(),
            action: action.clone(),
            callback,
            modifiers: modifiers.clone(),
            key: key.clone(),
        };

        // Log registration for debugging
        if key.is_empty() {
            tracing::info!(
                "Registered modifier-only hotkey: {} (modifiers: {:?}, action: {})",
                binding,
                modifiers,
                action
            );
        } else {
            tracing::info!(
                "Registered hotkey: {} (modifiers: {:?}, key: {}, action: {})",
                binding,
                modifiers,
                key,
                action
            );
        }

        self.bindings.lock().push(binding_obj);
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

        let handle = thread::spawn(move || {
            let mut ctrl_pressed = false;
            let mut alt_pressed = false;
            let mut shift_pressed = false;
            let mut meta_pressed = false;

            // Process an event and return whether it was handled
            // This logic is shared between grab and listen callbacks
            let process_event = |event: &Event,
                                 ctrl: &mut bool,
                                 alt: &mut bool,
                                 shift: &mut bool,
                                 meta: &mut bool,
                                 bindings: &Arc<Mutex<Vec<HotkeyBinding>>>,
                                 active_flag: &Arc<Mutex<bool>>| {
                if !*active_flag.lock() {
                    return;
                }

                match event.event_type {
                    EventType::KeyPress(key) => {
                        // Track previous modifier states
                        let prev_ctrl = *ctrl;
                        let prev_alt = *alt;
                        let prev_shift = *shift;
                        let prev_meta = *meta;

                        // Update modifier states
                        match key {
                            Key::ControlLeft | Key::ControlRight => *ctrl = true,
                            Key::Alt | Key::AltGr => *alt = true,
                            Key::ShiftLeft | Key::ShiftRight => *shift = true,
                            Key::MetaLeft | Key::MetaRight => *meta = true,
                            _ => {}
                        }

                        // Check bindings after modifier state update
                        let bindings_lock = bindings.lock();

                        for binding in bindings_lock.iter() {
                            // For modifier-only bindings (empty key)
                            if binding.key.is_empty() {
                                // Trigger when all required modifiers are pressed and state changed
                                if matches_modifier_only_binding(
                                    &binding.modifiers,
                                    *ctrl,
                                    *alt,
                                    *shift,
                                    *meta,
                                    prev_ctrl,
                                    prev_alt,
                                    prev_shift,
                                    prev_meta,
                                ) {
                                    tracing::info!(
                                        "Modifier-only hotkey triggered: {} (action: {})",
                                        binding.binding,
                                        binding.action
                                    );
                                    // Trigger callback
                                    if let Ok(action_cstr) =
                                        std::ffi::CString::new(binding.action.clone())
                                    {
                                        let action_ptr = action_cstr.into_raw();
                                        (binding.callback)(action_ptr);
                                    }
                                }
                            } else {
                                // For regular key bindings
                                let key_str = key_to_string(&key);
                                if matches_binding(
                                    &binding.modifiers,
                                    &binding.key,
                                    *ctrl,
                                    *alt,
                                    *shift,
                                    *meta,
                                    &key_str,
                                ) {
                                    tracing::info!(
                                        "Hotkey triggered: {} (action: {})",
                                        binding.binding,
                                        binding.action
                                    );
                                    // Trigger callback
                                    if let Ok(action_cstr) =
                                        std::ffi::CString::new(binding.action.clone())
                                    {
                                        let action_ptr = action_cstr.into_raw();
                                        (binding.callback)(action_ptr);
                                    }
                                }
                            }
                        }
                    }
                    EventType::KeyRelease(key) => {
                        // Update modifier states
                        match key {
                            Key::ControlLeft | Key::ControlRight => *ctrl = false,
                            Key::Alt | Key::AltGr => *alt = false,
                            Key::ShiftLeft | Key::ShiftRight => *shift = false,
                            Key::MetaLeft | Key::MetaRight => *meta = false,
                            _ => {}
                        }
                    }
                    _ => {}
                }
            };

            // On Linux: use listen() for X11, start_grab_listen() for Wayland
            // On macOS/Windows: use listen() which works natively
            #[cfg(target_os = "linux")]
            {
                if is_wayland() {
                    // Wayland: use start_grab_listen (evdev) - requires input group
                    tracing::info!("Wayland session detected, using evdev grab for hotkeys");
                    let callback = move |event: Event| -> Option<Event> {
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            process_event(
                                &event,
                                &mut ctrl_pressed,
                                &mut alt_pressed,
                                &mut shift_pressed,
                                &mut meta_pressed,
                                &bindings,
                                &active_flag,
                            );
                        }));
                        // Always pass the event through (don't consume it)
                        Some(event)
                    };

                    if let Err(e) = start_grab_listen(callback) {
                        eprintln!("Hotkey grab error (Wayland): {:?}", e);
                        eprintln!("Hint: Make sure your user is in the 'input' group: sudo usermod -aG input $USER");
                    }
                } else {
                    // X11: use listen (X11 APIs) - no special permissions needed
                    tracing::info!("X11 session detected, using X11 listener for hotkeys");
                    let callback = move |event: Event| {
                        let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            process_event(
                                &event,
                                &mut ctrl_pressed,
                                &mut alt_pressed,
                                &mut shift_pressed,
                                &mut meta_pressed,
                                &bindings,
                                &active_flag,
                            );
                        }));
                    };

                    if let Err(e) = listen(callback) {
                        eprintln!("Hotkey listener error (X11): {:?}", e);
                    }
                }
            }

            #[cfg(not(target_os = "linux"))]
            {
                let callback = move |event: Event| {
                    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                        process_event(
                            &event,
                            &mut ctrl_pressed,
                            &mut alt_pressed,
                            &mut shift_pressed,
                            &mut meta_pressed,
                            &bindings,
                            &active_flag,
                        );
                    }));
                };

                if let Err(e) = listen(callback) {
                    eprintln!("Hotkey listener error: {:?}", e);
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

        // Note: rdev doesn't provide a way to cleanly stop listening
        // The thread will exit when the program exits
        Ok(())
    }
}

fn key_to_string(key: &Key) -> String {
    match key {
        Key::KeyA => "a".to_string(),
        Key::KeyB => "b".to_string(),
        Key::KeyC => "c".to_string(),
        Key::KeyD => "d".to_string(),
        Key::KeyE => "e".to_string(),
        Key::KeyF => "f".to_string(),
        Key::KeyG => "g".to_string(),
        Key::KeyH => "h".to_string(),
        Key::KeyI => "i".to_string(),
        Key::KeyJ => "j".to_string(),
        Key::KeyK => "k".to_string(),
        Key::KeyL => "l".to_string(),
        Key::KeyM => "m".to_string(),
        Key::KeyN => "n".to_string(),
        Key::KeyO => "o".to_string(),
        Key::KeyP => "p".to_string(),
        Key::KeyQ => "q".to_string(),
        Key::KeyR => "r".to_string(),
        Key::KeyS => "s".to_string(),
        Key::KeyT => "t".to_string(),
        Key::KeyU => "u".to_string(),
        Key::KeyV => "v".to_string(),
        Key::KeyW => "w".to_string(),
        Key::KeyX => "x".to_string(),
        Key::KeyY => "y".to_string(),
        Key::KeyZ => "z".to_string(),
        Key::Num0 => "0".to_string(),
        Key::Num1 => "1".to_string(),
        Key::Num2 => "2".to_string(),
        Key::Num3 => "3".to_string(),
        Key::Num4 => "4".to_string(),
        Key::Num5 => "5".to_string(),
        Key::Num6 => "6".to_string(),
        Key::Num7 => "7".to_string(),
        Key::Num8 => "8".to_string(),
        Key::Num9 => "9".to_string(),
        Key::Space => "space".to_string(),
        Key::Return => "return".to_string(),
        Key::Escape => "escape".to_string(),
        Key::F1 => "f1".to_string(),
        Key::F2 => "f2".to_string(),
        Key::F3 => "f3".to_string(),
        Key::F4 => "f4".to_string(),
        Key::F5 => "f5".to_string(),
        Key::F6 => "f6".to_string(),
        Key::F7 => "f7".to_string(),
        Key::F8 => "f8".to_string(),
        Key::F9 => "f9".to_string(),
        Key::F10 => "f10".to_string(),
        Key::F11 => "f11".to_string(),
        Key::F12 => "f12".to_string(),
        Key::Tab => "tab".to_string(),
        Key::Backspace => "backspace".to_string(),
        Key::Delete => "delete".to_string(),
        Key::UpArrow => "up".to_string(),
        Key::DownArrow => "down".to_string(),
        Key::LeftArrow => "left".to_string(),
        Key::RightArrow => "right".to_string(),
        _ => "unknown".to_string(),
    }
}

fn matches_binding(
    binding_modifiers: &[String],
    binding_key: &str,
    ctrl: bool,
    alt: bool,
    shift: bool,
    meta: bool,
    pressed_key: &str,
) -> bool {
    // Check if key matches
    if pressed_key != binding_key {
        return false;
    }

    // Check modifiers
    let has_ctrl = binding_modifiers.contains(&"ctrl".to_string())
        || binding_modifiers.contains(&"control".to_string());
    let has_alt = binding_modifiers.contains(&"alt".to_string())
        || binding_modifiers.contains(&"option".to_string()); // macOS uses "option"
    let has_shift = binding_modifiers.contains(&"shift".to_string());
    let has_meta = binding_modifiers.contains(&"meta".to_string())
        || binding_modifiers.contains(&"cmd".to_string())
        || binding_modifiers.contains(&"super".to_string())
        || binding_modifiers.contains(&"win".to_string());

    ctrl == has_ctrl && alt == has_alt && shift == has_shift && meta == has_meta
}

fn matches_modifier_only_binding(
    binding_modifiers: &[String],
    ctrl: bool,
    alt: bool,
    shift: bool,
    meta: bool,
    prev_ctrl: bool,
    prev_alt: bool,
    prev_shift: bool,
    prev_meta: bool,
) -> bool {
    // Check what modifiers the binding requires
    let has_ctrl = binding_modifiers.contains(&"ctrl".to_string())
        || binding_modifiers.contains(&"control".to_string());
    let has_alt = binding_modifiers.contains(&"alt".to_string())
        || binding_modifiers.contains(&"option".to_string());
    let has_shift = binding_modifiers.contains(&"shift".to_string());
    let has_meta = binding_modifiers.contains(&"meta".to_string())
        || binding_modifiers.contains(&"cmd".to_string())
        || binding_modifiers.contains(&"super".to_string())
        || binding_modifiers.contains(&"win".to_string());

    // All required modifiers must be pressed
    let modifiers_match =
        ctrl == has_ctrl && alt == has_alt && shift == has_shift && meta == has_meta;

    // Trigger only when:
    // 1. All required modifiers are now pressed
    // 2. At least one required modifier just changed to pressed (not all were already pressed)
    if !modifiers_match {
        return false;
    }

    // Check if any required modifier just became pressed
    let ctrl_just_pressed = has_ctrl && ctrl && !prev_ctrl;
    let alt_just_pressed = has_alt && alt && !prev_alt;
    let shift_just_pressed = has_shift && shift && !prev_shift;
    let meta_just_pressed = has_meta && meta && !prev_meta;

    ctrl_just_pressed || alt_just_pressed || shift_just_pressed || meta_just_pressed
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

#[no_mangle]
pub unsafe extern "C" fn plume_hotkey_manager_free(handle: HotkeyManagerHandle) {
    if !handle.is_null() {
        let _ = Box::from_raw(handle as *mut SimpleHotkeyManager);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
