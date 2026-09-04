use std::ffi::CString;
use std::io;
use std::os::raw::c_char;

use parking_lot::Mutex;

/// `void (*)(const char *message)`. Rust frees the string when the call returns, so the host must
/// copy rather than keep it.
pub type PlumeLogCallback = extern "C" fn(*const c_char);

static SINK: Mutex<Option<PlumeLogCallback>> = Mutex::new(None);

/// Two functions rather than one nullable pointer: cbindgen renders `Option<fn>` as an opaque
/// struct passed by value, which is not the ABI.
#[no_mangle]
pub unsafe extern "C" fn plume_set_log_callback(callback: PlumeLogCallback) {
    *SINK.lock() = Some(callback);
}

#[no_mangle]
pub unsafe extern "C" fn plume_clear_log_callback() {
    *SINK.lock() = None;
}

/// False when no host is attached, so the caller can fall back to the console.
pub(crate) fn forward(message: &str) -> bool {
    // Copied out before the call. Holding the lock across a callback into the host would deadlock
    // the moment that host set the sink from inside it.
    let sink = *SINK.lock();
    let Some(sink) = sink else { return false };

    // Interior nul cannot be represented; dropping the line beats passing a truncated one.
    let Ok(text) = CString::new(message) else { return false };
    sink(text.as_ptr());
    true
}

/// Sends `tracing` output to the host, falling back to stdout. A macOS `.app` discards stdout.
pub(crate) struct HostWriter;

impl io::Write for HostWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let text = String::from_utf8_lossy(buf);
        let line = text.trim_end();
        if !line.is_empty() && !forward(line) {
            print!("{}", text);
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CStr;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static SEEN: Mutex<Option<String>> = Mutex::new(None);
    static CALLS: AtomicUsize = AtomicUsize::new(0);

    extern "C" fn record(message: *const c_char) {
        CALLS.fetch_add(1, Ordering::SeqCst);
        let text = unsafe { CStr::from_ptr(message) };
        *SEEN.lock() = Some(text.to_string_lossy().into_owned());
    }

    #[test]
    fn a_line_reaches_the_host_and_the_string_is_not_handed_over() {
        unsafe { plume_set_log_callback(record) };

        assert!(forward("listener refused"));
        assert_eq!(SEEN.lock().as_deref(), Some("listener refused"));

        // Detaching has to stop delivery, or a host that has gone away is still called.
        unsafe { plume_clear_log_callback() };
        let before = CALLS.load(Ordering::SeqCst);
        assert!(!forward("ignored"));
        assert_eq!(CALLS.load(Ordering::SeqCst), before);
    }
}
