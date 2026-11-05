# enclos-app

The mobile app for [enclos](https://github.com/victorbonnet/enclos): talk to the agents in your enclos sandboxes from your phone, over Tailscale. Android first, then iOS.

Status: a proposal being built, Android only. The app pairs with a host's bridge (`enclos app pair`), lists its agents and talks to them; push notifications and iOS are not built. What it does is in [`docs/spec.md`](docs/spec.md), its decisions in [`docs/adr/`](docs/adr/), the plan in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145). Nothing in it is a security guarantee yet.

## Build

Needs Rust (the version in `rust-toolchain.toml` is installed by `rustup`), the Android SDK and NDK, JDK 17 and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```sh
just android
```

It builds the Rust core for `arm64-v8a` and `x86_64`, generates its Kotlin bindings, and builds the debug app in `android/app/build/outputs/apk/debug/`. Each pull request's CI also uploads that debug build as an artifact (`enclos-debug-apk`).

## License

MIT
