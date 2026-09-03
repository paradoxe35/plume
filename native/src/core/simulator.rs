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

    extern "C" {
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

    pub fn select_all(&mut self) -> Result<()> {
        debug!("Simulating select all");

        #[cfg(target_os = "macos")]
        {
            macos_native::simulate_select_all()
        }

        #[cfg(not(target_os = "macos"))]
        {
            self.enigo.key(Key::Control, enigo::Direction::Press)?;
            self.enigo.key(Key::Unicode('a'), enigo::Direction::Click)?;
            self.enigo.key(Key::Control, enigo::Direction::Release)?;
            Ok(())
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
            self.enigo.key(Key::Control, enigo::Direction::Press)?;
            self.enigo.key(Key::Unicode('c'), enigo::Direction::Click)?;
            self.enigo.key(Key::Control, enigo::Direction::Release)?;
            Ok(())
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
            self.enigo.key(Key::Control, enigo::Direction::Press)?;
            self.enigo.key(Key::Unicode('v'), enigo::Direction::Click)?;
            self.enigo.key(Key::Control, enigo::Direction::Release)?;
            Ok(())
        }
    }
}
