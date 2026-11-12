---
status: proposed
---

# One Rust core, and a native app per platform

The app ships on Android first, then iOS, with the same features on both. Its cryptography must match enclos's bridge byte for byte: pairing links, the Noise handshake, sealing messages, push previews ([enclos ADR 0042](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0042-a-bridge-on-each-host-serves-the-mobile-app.md), proposed).

Decision (proposed): **one Rust core** (`core-ffi/`), a thin layer over enclos's `enclos-protocol` crate, exported to Kotlin and Swift through UniFFI, does that cryptography and nothing else. **Each platform's app is native** (Kotlin and Compose on Android, Swift and SwiftUI on iOS) and does everything else: screens, the connection, storage, keys in the platform's key store. `docs/spec.md` says how the app behaves on both; a behaviour is built on each platform the way that platform does it.

Reason: the protocol is written and tested once, in the language enclos's bridge uses, so the two ends cannot drift; the screens, gestures, keys and notifications are the platform's own, which a shared UI layer would only imitate.

## Considered options

- **A shared UI** (Compose Multiplatform, Flutter, React Native): one set of screens, but iOS controls drawn by another toolkit, and the platform's key store, camera and push still reached through native code.
- **The protocol written twice**, in Kotlin and Swift: no Rust in the apps, but three implementations of the same cryptography to keep in step.

## Consequences

- Feature parity is kept by the spec, not by shared code: a pull request that changes a behaviour changes `docs/spec.md`, and the other platform follows it.
- The core holds no logic of its own beyond the glue (CLAUDE.md); anything that is not the protocol stays in each app.
