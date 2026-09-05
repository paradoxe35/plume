use anyhow::Result;
use tracing::debug;

#[cfg(not(target_os = "macos"))]
use enigo::{Enigo, Key, Keyboard, Settings};

/// Shift, Control, Option, Command. CapsLock (1 << 16) and Fn (1 << 23) are deliberately absent:
/// neither spoils a Cmd shortcut, and including CapsLock would make every caps-locked user wait
/// out the whole budget on every action.
const MODIFIER_FLAGS: u64 = (1 << 17) | (1 << 18) | (1 << 19) | (1 << 20);

const MODIFIER_POLL_MS: u64 = 10;

/// Generous, because it is only spent while the user is actually still holding the keys, and a
/// modifier-only shortcut such as macOS's ctrl+cmd is naturally held for a beat.
const MODIFIER_POLLS: u32 = 100;

/// Polls until no modifier is held. False when the budget ran out with one still down.
///
/// Separated from the platform call so it can be tested anywhere: the bug this replaced was a
/// macOS-only body that returned success without reading anything.
fn wait_for_modifiers(
    mut read_flags: impl FnMut() -> u64,
    mut pause: impl FnMut(),
    polls: u32,
) -> bool {
    for _ in 0..polls {
        if read_flags() & MODIFIER_FLAGS == 0 {
            return true;
        }
        pause();
    }
    read_flags() & MODIFIER_FLAGS == 0
}

pub struct KeySimulator {
    #[cfg(not(target_os = "macos"))]
    enigo: Enigo,
}

#[cfg(target_os = "macos")]
mod macos_native {
    use super::*;
    use std::ffi::c_void;
    use std::ptr;

    const KCG_KEY_A: i64 = 0;
    const KCG_KEY_C: i64 = 8;
    const KCG_KEY_V: i64 = 9;
    const KCG_FLAGMASK_COMMAND: u64 = 1 << 20;

    /// What `NSEvent.modifierFlags` reflects, and read before Plume posts anything of its own.
    const KCG_EVENT_SOURCE_STATE_COMBINED_SESSION: i32 = 0;

    extern "C" {
        fn CGEventSourceFlagsState(state_id: i32) -> u64;

        fn CGEventCreateKeyboardEvent(
            source: *mut c_void,
            virtual_key: u16,
            key_down: bool,
        ) -> *mut c_void;

        fn CGEventSetFlags(event: *mut c_void, flags: u64);
        fn CGEventPost(tap: u32, event: *mut c_void);
        fn CFRelease(cf: *mut c_void);
    }

    const KCG_HID_EVENT_TAP: u32 = 0;

    pub unsafe fn send_key_combo(key_code: u16, with_command: bool) -> Result<()> {
        let event_down = CGEventCreateKeyboardEvent(ptr::null_mut(), key_code, true);
        if event_down.is_null() {
            return Err(anyhow::anyhow!("Failed to create key down event"));
        }

        if with_command {
            CGEventSetFlags(event_down, KCG_FLAGMASK_COMMAND);
        }

        CGEventPost(KCG_HID_EVENT_TAP, event_down);
        CFRelease(event_down);

        std::thread::sleep(std::time::Duration::from_millis(10));

        let event_up = CGEventCreateKeyboardEvent(ptr::null_mut(), key_code, false);
        if event_up.is_null() {
            return Err(anyhow::anyhow!("Failed to create key up event"));
        }

        if with_command {
            CGEventSetFlags(event_up, KCG_FLAGMASK_COMMAND);
        }

        CGEventPost(KCG_HID_EVENT_TAP, event_up);
        CFRelease(event_up);

        Ok(())
    }

    /// False when the user was still holding them when the budget ran out.
    pub fn await_modifiers_released() -> bool {
        super::wait_for_modifiers(
            || unsafe { CGEventSourceFlagsState(KCG_EVENT_SOURCE_STATE_COMBINED_SESSION) },
            || std::thread::sleep(std::time::Duration::from_millis(MODIFIER_POLL_MS)),
            MODIFIER_POLLS,
        )
    }

    pub fn simulate_select_all() -> Result<()> {
        unsafe { send_key_combo(KCG_KEY_A as u16, true) }
    }

    pub fn simulate_copy() -> Result<()> {
        unsafe { send_key_combo(KCG_KEY_C as u16, true) }
    }

    pub fn simulate_paste() -> Result<()> {
        unsafe { send_key_combo(KCG_KEY_V as u16, true) }
    }
}

impl KeySimulator {
    pub fn new() -> Result<Self> {
        Ok(Self {
            #[cfg(not(target_os = "macos"))]
            enigo: Enigo::new(&Settings::default())
                .map_err(|e| anyhow::anyhow!("Failed to initialize key simulator: {}", e))?,
        })
    }

    /// Drops modifiers still held from the triggering hotkey, so Ctrl+A does not arrive as
    /// Ctrl+Alt+A. macOS cannot release a physically held key, so it waits for one instead.
    pub fn release_modifiers(&mut self) -> Result<()> {
        #[cfg(target_os = "macos")]
        {
            if !macos_native::await_modifiers_released() {
                // Reported rather than pressed on with: the caller can say "let go of the keys",
                // where going ahead fails later as "could not copy" and blames the application.
                tracing::warn!("Modifiers still held; not simulating a keystroke over them");
                return Err(anyhow::anyhow!("The shortcut keys are still held down"));
            }
            Ok(())
        }

        #[cfg(not(target_os = "macos"))]
        {
            debug!("Releasing held modifiers");
            // Best effort; a modifier that was never down releases harmlessly.
            for key in [Key::Control, Key::Alt, Key::Shift, Key::Meta] {
                let _ = self.enigo.key(key, enigo::Direction::Release);
            }
            Ok(())
        }
    }

    #[cfg(not(target_os = "macos"))]
    fn control_combo(&mut self, letter: char) -> Result<()> {
        self.release_modifiers()?;
        self.enigo.key(Key::Control, enigo::Direction::Press)?;
        let clicked = self.enigo.key(Key::Unicode(letter), enigo::Direction::Click);
        // Control must come up even if the letter failed, or every later keystroke is a shortcut.
        self.enigo.key(Key::Control, enigo::Direction::Release)?;
        clicked?;
        Ok(())
    }

    pub fn select_all(&mut self) -> Result<()> {
        debug!("Simulating select all");

        #[cfg(target_os = "macos")]
        {
            macos_native::simulate_select_all()
        }

        #[cfg(not(target_os = "macos"))]
        {
            self.control_combo('a')
        }
    }

    pub fn copy(&mut self) -> Result<()> {
        debug!("Simulating copy");

        #[cfg(target_os = "macos")]
        {
            macos_native::simulate_copy()
        }

        #[cfg(not(target_os = "macos"))]
        {
            self.control_combo('c')
        }
    }

    pub fn paste(&mut self) -> Result<()> {
        debug!("Simulating paste");

        #[cfg(target_os = "macos")]
        {
            macos_native::simulate_paste()
        }

        #[cfg(not(target_os = "macos"))]
        {
            self.control_combo('v')
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;

    /// The body this replaced returned success without reading anything, so a shortcut was
    /// simulated while the user still held the keys that triggered it.
    #[test]
    fn it_waits_until_the_keys_come_up() {
        let reads = Cell::new(0);
        let pauses = Cell::new(0);
        let held = [MODIFIER_FLAGS, MODIFIER_FLAGS, 0];

        let cleared = wait_for_modifiers(
            || {
                let n = reads.get();
                reads.set(n + 1);
                held[n.min(held.len() - 1)]
            },
            || pauses.set(pauses.get() + 1),
            MODIFIER_POLLS,
        );

        assert!(cleared);
        assert_eq!(reads.get(), 3, "it stopped reading before the keys came up");
        assert_eq!(pauses.get(), 2);
    }

    #[test]
    fn a_key_held_throughout_gives_up_rather_than_hanging() {
        let pauses = Cell::new(0);

        let cleared = wait_for_modifiers(
            || MODIFIER_FLAGS,
            || pauses.set(pauses.get() + 1),
            MODIFIER_POLLS,
        );

        assert!(!cleared, "a held key must be reported, not waited on forever");
        assert_eq!(pauses.get(), MODIFIER_POLLS);
    }

    #[test]
    fn nothing_held_costs_one_read_and_no_waiting() {
        let pauses = Cell::new(0);

        assert!(wait_for_modifiers(|| 0, || pauses.set(pauses.get() + 1), MODIFIER_POLLS));
        assert_eq!(pauses.get(), 0);
    }

    /// Caps Lock is a modifier that spoils nothing, and waiting it out would stall every action.
    #[test]
    fn caps_lock_is_not_something_to_wait_for() {
        assert!(wait_for_modifiers(|| 1 << 16, || {}, MODIFIER_POLLS));
    }
}
