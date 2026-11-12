//! The enclos app's Rust core, exported to Kotlin (and later Swift) through
//! UniFFI.
//!
//! For now it only reports its version, so the app, the bindings and the
//! build can be checked end to end. It will wrap `enclos-protocol` (message
//! encoding, the Noise handshake, pairing, decrypting push previews) once that
//! crate exists in enclos.

uniffi::setup_scaffolding!();

/// The core's version, as Cargo knows it.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_the_crate_version() {
        assert_eq!(core_version(), env!("CARGO_PKG_VERSION"));
    }
}
