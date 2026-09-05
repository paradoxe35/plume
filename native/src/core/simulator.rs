use anyhow::Result;
use tracing::debug;

#[cfg(not(target_os = "macos"))]
use enigo::{Enigo, Key, Keyboard, Settings};

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

    /// Live hardware modifier state, which is not the same as the flags on a posted event.
    const KCG_EVENT_SOURCE_STATE_COMBINED_SESSION: u32 = 0;
    const MODIFIER_FLAGS: u64 = (1 << 17) | (1 << 18) | (1 << 19) | (1 << 20);
    const HELD_MODIFIER_TIMEOUT_MS: u64 = 400;

    extern "C" {
        fn CGEventSourceFlagsState(state_id: u32) -> u64;

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

    /// Waits for the shortcut's own modifiers to come up before anything is simulated.
    ///
    /// A physically held key cannot be released by posting an event, and macOS merges the live
    /// hardware flags into whatever is posted — so Cmd+A sent while Ctrl+Option are still down
    /// arrives as Ctrl+Option+Cmd+A and selects nothing. Waiting is the only option; it returns as
    /// soon as the user lets go, rather than sleeping a fixed guess.
    pub fn await_modifiers_released() {
        let deadline =
            std::time::Instant::now() + std::time::Duration::from_millis(HELD_MODIFIER_TIMEOUT_MS);
        while std::time::Instant::now() < deadline {
            if unsafe { CGEventSourceFlagsState(KCG_EVENT_SOURCE_STATE_COMBINED_SESSION) }
                & MODIFIER_FLAGS
                == 0
            {
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        tracing::info!("Modifiers still held after {HELD_MODIFIER_TIMEOUT_MS}ms; going ahead");
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
    /// Ctrl+Alt+A. macOS needs nothing: its events carry an explicit flag mask.
    pub fn release_modifiers(&mut self) -> Result<()> {
        #[cfg(target_os = "macos")]
        {
            macos_native::await_modifiers_released();
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
