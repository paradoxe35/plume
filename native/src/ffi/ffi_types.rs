use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_void};
use std::sync::Once;

use once_cell::sync::OnceCell;
use parking_lot::Mutex;

/// Opaque pointer types for safe cross-FFI boundary object passing
pub type HotkeyManagerHandle = *mut c_void;
pub type ClipboardHandle = *mut c_void;
pub type SimulatorHandle = *mut c_void;

/// FFI Error codes returned by all functions
#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum FFIErrorCode {
    Success = 0,
    NullPointer = -1,
    InitFailed = -2,
    InvalidArgument = -3,
    OperationFailed = -4,
    InvalidUtf8 = -5,
    NotInitialized = -6,
}

impl From<i32> for FFIErrorCode {
    fn from(code: i32) -> Self {
        match code {
            0 => FFIErrorCode::Success,
            -1 => FFIErrorCode::NullPointer,
            -2 => FFIErrorCode::InitFailed,
            -3 => FFIErrorCode::InvalidArgument,
            -4 => FFIErrorCode::OperationFailed,
            -5 => FFIErrorCode::InvalidUtf8,
            -6 => FFIErrorCode::NotInitialized,
            _ => FFIErrorCode::OperationFailed,
        }
    }
}

/// Thread-safe last error storage
static LAST_ERROR: OnceCell<Mutex<Option<String>>> = OnceCell::new();

/// Set the last error message
pub fn set_last_error(err: String) {
    let error_store = LAST_ERROR.get_or_init(|| Mutex::new(None));
    *error_store.lock() = Some(err);
}

/// Take (and clear) the last error message
pub fn take_last_error() -> Option<String> {
    let error_store = LAST_ERROR.get_or_init(|| Mutex::new(None));
    error_store.lock().take()
}

/// Helper: Convert C string to Rust String
/// # Safety
/// - `c_str` must be a valid null-terminated C string
/// - `c_str` must not be null
pub unsafe fn c_str_to_string(c_str: *const c_char) -> Result<String, &'static str> {
    if c_str.is_null() {
        return Err("Null pointer provided");
    }
    CStr::from_ptr(c_str)
        .to_str()
        .map(|s| s.to_string())
        .map_err(|_| "Invalid UTF-8 in C string")
}

/// Helper: Convert Rust String to C string (caller must free with plume_free_string)
pub fn string_to_c_str(s: String) -> *mut c_char {
    match CString::new(s) {
        Ok(c_string) => c_string.into_raw(),
        Err(_) => {
            set_last_error("String contains null byte".to_string());
            std::ptr::null_mut()
        }
    }
}

/// Helper: Convert Result to error code
pub fn result_to_error_code<T>(result: Result<T, anyhow::Error>) -> c_int {
    match result {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("{:#}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

/// Initialize logging (call once)
static INIT_LOGGING: Once = Once::new();

pub fn init_logging() {
    INIT_LOGGING.call_once(|| {
        tracing_subscriber::fmt()
            .with_max_level(tracing::Level::INFO)
            .with_target(false)
            .init();
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_error_storage() {
        set_last_error("test error".to_string());
        let err = take_last_error();
        assert_eq!(err, Some("test error".to_string()));

        // Should be cleared
        let err2 = take_last_error();
        assert_eq!(err2, None);
    }

    #[test]
    fn test_string_conversion() {
        let rust_str = "Hello, FFI!".to_string();
        let c_str = string_to_c_str(rust_str.clone());
        assert!(!c_str.is_null());

        unsafe {
            let back_to_rust = c_str_to_string(c_str).unwrap();
            assert_eq!(back_to_rust, rust_str);

            // Free the C string
            let _ = CString::from_raw(c_str);
        }
    }
}
