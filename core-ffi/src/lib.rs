//! The enclos app's Rust core, exported to Kotlin (and later Swift) through
//! UniFFI: the glue over `enclos-protocol`'s device leg (pairing, Noise,
//! previews). The app's own code builds and reads the JSON messages; what
//! must be right once for every platform lives here.

use std::sync::Mutex;

use enclos_protocol::noise::{Initiator, Session};
use enclos_protocol::{noise, pairing, preview};

uniffi::setup_scaffolding!();

/// Why the core refused.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("{reason}")]
    Refused { reason: String },
}

fn refused(e: impl std::fmt::Display) -> CoreError {
    CoreError::Refused {
        reason: e.to_string(),
    }
}

/// The core's version, as Cargo knows it.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_owned()
}

/// What a pairing link holds.
#[derive(uniffi::Record)]
pub struct PairLink {
    pub host: String,
    pub url: String,
    pub key: Vec<u8>,
    pub code: Vec<u8>,
    pub expires: u64,
}

/// Reads an `enclos://pair?…` link, from a QR code or typed.
#[uniffi::export]
pub fn parse_pair_link(link: String) -> Result<PairLink, CoreError> {
    let link = pairing::PairLink::parse(link.trim()).map_err(refused)?;
    Ok(PairLink {
        host: link.host,
        url: link.url,
        key: link.key,
        code: link.code,
        expires: link.expires,
    })
}

/// A static X25519 key pair for the device's Noise sessions.
#[derive(uniffi::Record)]
pub struct NoiseKeyPair {
    pub private_key: Vec<u8>,
    pub public_key: Vec<u8>,
}

#[uniffi::export]
pub fn generate_noise_keypair() -> Result<NoiseKeyPair, CoreError> {
    let keys = noise::generate_keypair().map_err(refused)?;
    Ok(NoiseKeyPair {
        private_key: keys.private.to_vec(),
        public_key: keys.public,
    })
}

/// The pairing code's proof for this session.
#[uniffi::export]
pub fn pairing_proof(code: Vec<u8>, handshake_hash: Vec<u8>) -> Vec<u8> {
    pairing::proof(&code, &handshake_hash)
}

/// Bytes the device's hardware key signs when asking to pair.
#[uniffi::export]
pub fn pairing_signature_message(handshake_hash: Vec<u8>) -> Vec<u8> {
    pairing::Signed::Pair.message(&handshake_hash)
}

/// Bytes the device's hardware key signs when authenticating a paired connection.
#[uniffi::export]
pub fn auth_signature_message(handshake_hash: Vec<u8>) -> Vec<u8> {
    pairing::Signed::Auth.message(&handshake_hash)
}

/// Bytes as the protocol writes them in JSON (base64url).
#[uniffi::export]
pub fn encode_bytes(bytes: Vec<u8>) -> String {
    pairing::encode(&bytes)
}

#[uniffi::export]
pub fn decode_bytes(text: String) -> Result<Vec<u8>, CoreError> {
    pairing::decode("bytes", &text).map_err(refused)
}

/// Opens a notification preview sealed for this device.
#[uniffi::export]
pub fn open_preview(
    push_key: Vec<u8>,
    host: String,
    seq: i64,
    sealed: Vec<u8>,
) -> Result<Vec<u8>, CoreError> {
    preview::open(
        &push_key,
        &preview::PushFields { host: &host, seq },
        &sealed,
    )
    .map_err(refused)
}

/// The device's side of a Noise handshake with one bridge.
#[derive(uniffi::Object)]
pub struct DeviceHandshake {
    initiator: Mutex<Option<Initiator>>,
}

#[uniffi::export]
impl DeviceHandshake {
    #[uniffi::constructor]
    pub fn new(device_private: Vec<u8>, bridge_public: Vec<u8>) -> Result<Self, CoreError> {
        Ok(DeviceHandshake {
            initiator: Mutex::new(Some(
                Initiator::new(&device_private, &bridge_public).map_err(refused)?,
            )),
        })
    }

    /// The first handshake message.
    pub fn hello(&self) -> Result<Vec<u8>, CoreError> {
        let mut initiator = self.initiator.lock().map_err(refused)?;
        initiator
            .as_mut()
            .ok_or_else(|| refused("the handshake is over"))?
            .hello()
            .map_err(refused)
    }

    /// Reads the bridge's answer, which ends the handshake.
    pub fn finish(&self, answer: Vec<u8>) -> Result<std::sync::Arc<DeviceSession>, CoreError> {
        let initiator = self
            .initiator
            .lock()
            .map_err(refused)?
            .take()
            .ok_or_else(|| refused("the handshake is over"))?;
        let session = initiator.finish(&answer).map_err(refused)?;
        Ok(std::sync::Arc::new(DeviceSession {
            session: Mutex::new(session),
        }))
    }
}

/// An encrypted session with a bridge.
#[derive(uniffi::Object)]
pub struct DeviceSession {
    session: Mutex<Session>,
}

#[uniffi::export]
impl DeviceSession {
    /// What the device signs (`auth`) or proves (`pair.request`).
    pub fn handshake_hash(&self) -> Result<Vec<u8>, CoreError> {
        Ok(self
            .session
            .lock()
            .map_err(refused)?
            .handshake_hash()
            .to_vec())
    }

    pub fn encrypt(&self, plaintext: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.session
            .lock()
            .map_err(refused)?
            .encrypt(&plaintext)
            .map_err(refused)
    }

    pub fn decrypt(&self, ciphertext: Vec<u8>) -> Result<Vec<u8>, CoreError> {
        self.session
            .lock()
            .map_err(refused)?
            .decrypt(&ciphertext)
            .map_err(refused)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reports_the_crate_version() {
        assert_eq!(core_version(), env!("CARGO_PKG_VERSION"));
    }

    #[test]
    fn exported_signature_messages_bind_the_session_and_purpose() {
        let hash = vec![7; 32];
        let pair = pairing_signature_message(hash.clone());
        let auth = auth_signature_message(hash.clone());
        assert_eq!(pair, [b"enclos pair v1".as_slice(), &hash].concat());
        assert_eq!(auth, [b"enclos auth v1".as_slice(), &hash].concat());
        assert_ne!(pair, auth);
        assert_ne!(pair, pairing_signature_message(vec![8; 32]));
    }

    #[test]
    fn a_preview_requires_the_host_and_sequence_it_was_sealed_for() {
        let key = vec![4; 32];
        let sealed = preview::seal(
            &key,
            &preview::PushFields {
                host: "ubuntu",
                seq: 7,
            },
            b"preview",
        )
        .unwrap();
        assert_eq!(
            open_preview(key.clone(), "ubuntu".into(), 7, sealed.clone()).unwrap(),
            b"preview"
        );
        assert!(open_preview(key.clone(), "pi".into(), 7, sealed.clone()).is_err());
        assert!(open_preview(key, "ubuntu".into(), 8, sealed).is_err());
    }

    #[test]
    fn a_device_completes_a_handshake_with_a_bridge() {
        let bridge = noise::generate_keypair().unwrap();
        let device = generate_noise_keypair().unwrap();
        let handshake = DeviceHandshake::new(device.private_key, bridge.public.clone()).unwrap();
        let mut responder = noise::Responder::new(&bridge.private).unwrap();
        responder.read_hello(&handshake.hello().unwrap()).unwrap();
        let (answer, mut bridge_session) = responder.answer().unwrap();
        let session = handshake.finish(answer).unwrap();
        assert!(handshake.hello().is_err(), "a handshake is used once");
        let sealed = session.encrypt(b"hi".to_vec()).unwrap();
        assert_eq!(bridge_session.decrypt(&sealed).unwrap(), b"hi");
        assert_eq!(
            session.handshake_hash().unwrap(),
            bridge_session.handshake_hash()
        );
    }

    #[test]
    fn a_pairing_link_reads_back() {
        let link = pairing::PairLink {
            host: "mac".into(),
            url: "https://mac.tail1234.ts.net".into(),
            key: vec![7; 32],
            code: vec![9; 16],
            expires: 1_800_000_000,
        }
        .to_link()
        .unwrap();
        let read = parse_pair_link(link).unwrap();
        assert_eq!((read.host.as_str(), read.code.len()), ("mac", 16));
        assert!(parse_pair_link("https://example.com".into()).is_err());
    }
}
