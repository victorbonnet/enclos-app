# enclos-app

The enclos mobile app: Android first, then iOS. It talks to the agents of every enclos host over Tailscale, through each host's bridge. The plan, the architecture and the security model are in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145) until enclos's design takes them in.

## Layout

- `core-ffi/`: the Rust core, exported to Kotlin (and later Swift) through UniFFI. It will wrap `enclos-protocol` from the enclos repository; it holds no logic of its own beyond that glue.
- `uniffi-bindgen/`: UniFFI's binding generator, from the same `uniffi` version as `core-ffi`.
- `android/`: the Android app (Kotlin, Compose).
- `scripts/build-core-android.sh`: builds the core for the app's ABIs and generates its Kotlin bindings into git-ignored folders of the app. Run it before Gradle (`just android` does both).

## Versions and dependencies

Your training data is older than today. Never write a version number from memory.

- Crates: add them with `cargo add <name>` (it picks the latest) or check `cargo search <name> --limit 1`. Don't hand-edit versions in Cargo.toml.
- Android: versions live in `android/gradle/libs.versions.toml` only. Check the latest on Google's Maven (`https://maven.google.com`) or Maven Central; when they can't be reached, a recently updated Google sample (`android/snippets`) is the reference, and say so in the PR.
- GitHub Actions: check `git ls-remote --tags https://github.com/<owner>/<repo>`, use the latest major, and pin it to the commit SHA with the tag in a comment (`uses: actions/checkout@<sha> # v7`).
- Rust: check the latest stable with `git ls-remote --tags https://github.com/rust-lang/rust` and update `rust-toolchain.toml`, the only place the version is set. CI installs from it with `rustup toolchain install`.
- After any version change, run `just ci`, and `just android` when the Android SDK and NDK are installed. `cargo deny check` rejects crates with known advisories, licenses not listed in `deny.toml`, or sources other than crates.io. Ask before adding a license or an advisory exception to `deny.toml`.
- `unsafe_code` is denied across the workspace, except in `core-ffi`, whose UniFFI scaffolding exports `#[unsafe(no_mangle)]` functions. Hand-written code there stays safe Rust: ask before writing any `unsafe`.
- In the PR description, list each version you chose and how you checked it.

Dependabot also opens weekly update PRs for crates, Gradle, actions and the Rust toolchain (see `.github/dependabot.yml`).

## Vocabulary

Use the terms of enclos's [`GLOSSARY.md`](https://github.com/victorbonnet/enclos/blob/main/GLOSSARY.md) in code, docs and messages (host, sandbox, agent, runtime, channel, …), and avoid the words listed there under _Avoid_.

## Security

The app holds device keys and, later, conversations. Never describe security behavior as tested or enforced until code and tests show it. Keys stay in the Android Keystore; nothing secret goes into logs, backups or notifications in clear text.

## Pull requests

`main` is protected: every change goes through a pull request, merged by squash, with `ci-ok` passing.
