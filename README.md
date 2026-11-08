# enclos-app

The mobile app for [enclos](https://github.com/victorbonnet/enclos): talk to the agents in your enclos sandboxes from your phone, over Tailscale. Android first, then iOS.

Status: a proposal being built, Android only. The app pairs with a host's bridge (`enclos app pair`), lists its agents and talks to them; push notifications and iOS are not built. What it does is in [`docs/spec.md`](docs/spec.md), its decisions in [`docs/adr/`](docs/adr/), the plan in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145). Nothing in it is a security guarantee yet.

Agents can send [static HTML artefacts](docs/spec.md#5-messages): a self-contained page opens directly in the app, with Back and Share. Pages use inline CSS and embedded images or fonts; scripts, external resources and navigation are unavailable. The channel tells the agent to write a UTF-8 `.html` file and send `MEDIA:/absolute/path/page.html`. HTML in a conversation's text still shows literally.

## Build

Needs Rust (the version in `rust-toolchain.toml` is installed by `rustup`), the Android SDK and NDK, JDK 17 and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```sh
just android
```

It builds the Rust core for `arm64-v8a` and `x86_64`, generates its Kotlin bindings, and builds the debug app in `android/app/build/outputs/apk/debug/`. Each pull request's CI also uploads that debug build as an artifact (`enclos-debug-apk`).

Debug builds are signed with the project's one debug key, so any build installs over any other ([ADR 0004](docs/adr/0004-one-debug-signing-key-kept-out-of-git.md)). The key is not in git: put the keystore at `~/.android/enclos-debug.keystore` (or point `ENCLOS_DEBUG_KEYSTORE` at it); CI reads it from the `ENCLOS_DEBUG_KEYSTORE_B64` secret. Without it, a build is signed with the machine's own debug key and installs only after uninstalling the app, which forgets its pairings.

## License

MIT
