pub mod core;
pub mod ffi;

pub use ffi::*;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_library_smoke() {
        assert!(true);
    }
}
