# enclos-app

The enclos mobile app: Android first, then iOS. It talks to the agents of every enclos host over Tailscale, through each host's bridge. The plan, the architecture and the security model are in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145) until enclos's design takes them in.

## Behaviour, decisions and platforms

- `docs/spec.md` says what the app does, for every platform, and how each platform renders it (its Platforms table). Read it before working on a feature. A pull request that adds or changes a behaviour updates it in the same pull request.
- Android and iOS reach feature parity and each behaves natively: a behaviour is specified once, platform-neutrally, and built the platform's own way on each. When a behaviour is built on one platform only, the spec says so.
- Propose an ADR (`docs/adr/NNNN-slug.md`, the next number) only for a decision that is hard to reverse, surprising without context and the result of a real trade-off. Keep it short: a title, the context, decision and reason in a few sentences; "Considered options" or "Consequences" only when they help. Every ADR starts with `status:` front matter: `proposed`, `accepted`, or `superseded by NNNN`. Don't contradict an ADR silently: propose one that supersedes it.
- Keep three kinds of statement apart: decided (the spec's text, an accepted ADR), proposed (an ADR with `status: proposed`), and to verify (the spec's "To verify" list). When a test or a run on a phone settles an item, move it out of that list.
- enclos's side (the bridge, the protocol, the runtimes' plugins) is in enclos's `docs/design.md` and ADRs; link to them rather than restating them.

## Layout

- `core-ffi/`: the Rust core, exported to Kotlin (and later Swift) through UniFFI. It wraps `enclos-protocol` from the enclos repository, pinned to a commit (a git dependency, allowed for that repository alone in `deny.toml`) until the crate is on crates.io; it holds no logic of its own beyond that glue.
- `tools/uniffi-bindgen/`: UniFFI's binding generator, a build tool that never ships in the app. It is a workspace of its own, with its own `Cargo.lock` and `deny.toml`, because it depends on an unmaintained crate the app must not (RUSTSEC-2026-0374). It must use the same `uniffi` release as `core-ffi`: `scripts/check-uniffi-versions.sh` checks it.
- `docs/`: the spec (`spec.md`) and the ADRs (`adr/`).
- `android/`: the Android app (Kotlin, Compose). JVM unit tests in `android/app/src/test/`.
- `scripts/build-core-android.sh`: builds the core for the app's ABIs and generates its Kotlin bindings into git-ignored folders of the app. Run it before Gradle (`just android` does both).

## Versions and dependencies

Your training data is older than today. Never write a version number from memory.

- Crates: add them with `cargo add <name>` (it picks the latest) or check `cargo search <name> --limit 1`. Don't hand-edit versions in Cargo.toml.
- Android: versions live in `android/gradle/libs.versions.toml` only. Check the latest on Google's Maven (`https://maven.google.com`) or Maven Central; when they can't be reached, a recently updated Google sample (`android/snippets`) is the reference, and say so in the PR.
- GitHub Actions: check `git ls-remote --tags https://github.com/<owner>/<repo>`, use the latest major, and pin it to the commit SHA with the tag in a comment (`uses: actions/checkout@<sha> # v7`).
- Rust: check the latest stable with `git ls-remote --tags https://github.com/rust-lang/rust` and update `rust-toolchain.toml`, the only place the version is set. CI installs from it with `rustup toolchain install`.
- After any version change, run `just ci`, and `just android` when the Android SDK and NDK are installed. `cargo deny` runs on each workspace and rejects crates with known advisories, licenses not listed in its `deny.toml`, or sources other than crates.io. The app's `deny.toml` has no advisory exception, and must keep none: what ships in the app uses no unmaintained or vulnerable crate. Ask before adding a license or an advisory exception to either `deny.toml`.
- `uniffi` changes in both workspaces at once (Dependabot groups them).
- `unsafe_code` is denied in every crate except `core-ffi`, whose UniFFI scaffolding exports `#[unsafe(no_mangle)]` functions. Hand-written code there stays safe Rust: ask before writing any `unsafe`.
- In the PR description, list each version you chose and how you checked it.

Dependabot also opens weekly update PRs for crates, Gradle, actions and the Rust toolchain (see `.github/dependabot.yml`).

## Vocabulary

Use the terms of enclos's [`GLOSSARY.md`](https://github.com/victorbonnet/enclos/blob/main/GLOSSARY.md) in code, docs and messages (host, sandbox, agent, runtime, channel, …), and avoid the words listed there under _Avoid_.

## Security

The app holds device keys and, later, conversations. Never describe security behavior as tested or enforced until code and tests show it. Keys stay in the Android Keystore; nothing secret goes into logs, backups or notifications in clear text.

## Pull requests

`main` is protected: every change goes through a pull request, merged by squash, with `ci-ok` passing.
