use std::os::raw::{c_char, c_int};

use super::ffi_types::*;
use crate::core::ClipboardManager;

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_new() -> ClipboardHandle {
    init_logging();

    match ClipboardManager::new() {
        Ok(clipboard) => Box::into_raw(Box::new(clipboard)) as ClipboardHandle,
        Err(e) => {
            set_last_error(format!("Failed to create clipboard manager: {:#}", e));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_get_text(handle: ClipboardHandle) -> *mut c_char {
    if handle.is_null() {
        set_last_error("Null clipboard handle provided".to_string());
        return std::ptr::null_mut();
    }

    let clipboard = &*(handle as *mut ClipboardManager);

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            set_last_error(format!("Failed to create runtime: {:#}", e));
            return std::ptr::null_mut();
        }
    };

    match rt.block_on(clipboard.get_text()) {
        Ok(text) => string_to_c_str(text),
        Err(e) => {
            set_last_error(format!("Failed to get clipboard text: {:#}", e));
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_set_text(
    handle: ClipboardHandle,
    text: *const c_char,
) -> c_int {
    if handle.is_null() {
        set_last_error("Null clipboard handle provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    if text.is_null() {
        set_last_error("Null text provided".to_string());
        return FFIErrorCode::NullPointer as c_int;
    }

    let clipboard = &*(handle as *mut ClipboardManager);

    let text_str = match c_str_to_string(text) {
        Ok(s) => s,
        Err(e) => {
            set_last_error(format!("Invalid text string: {}", e));
            return FFIErrorCode::InvalidUtf8 as c_int;
        }
    };

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            set_last_error(format!("Failed to create runtime: {:#}", e));
            return FFIErrorCode::InitFailed as c_int;
        }
    };

    match rt.block_on(clipboard.set_text(text_str)) {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Failed to set clipboard text: {:#}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_save(handle: ClipboardHandle) -> c_int {
    if handle.is_null() {
        return FFIErrorCode::NullPointer as c_int;
    }

    let clipboard = &*(handle as *mut ClipboardManager);

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            set_last_error(format!("Failed to create runtime: {:#}", e));
            return FFIErrorCode::InitFailed as c_int;
        }
    };

    match rt.block_on(clipboard.save_clipboard()) {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Failed to save clipboard: {:#}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_restore(handle: ClipboardHandle) -> c_int {
    if handle.is_null() {
        return FFIErrorCode::NullPointer as c_int;
    }

    let clipboard = &*(handle as *mut ClipboardManager);

    let rt = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
        Ok(rt) => rt,
        Err(e) => {
            set_last_error(format!("Failed to create runtime: {:#}", e));
            return FFIErrorCode::InitFailed as c_int;
        }
    };

    match rt.block_on(clipboard.restore_clipboard()) {
        Ok(_) => FFIErrorCode::Success as c_int,
        Err(e) => {
            set_last_error(format!("Failed to restore clipboard: {:#}", e));
            FFIErrorCode::OperationFailed as c_int
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn plume_clipboard_free(handle: ClipboardHandle) {
    if !handle.is_null() {
        let _ = Box::from_raw(handle as *mut ClipboardManager);
    }
}
