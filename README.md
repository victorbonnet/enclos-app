# enclos-app

The mobile app for [enclos](https://github.com/victorbonnet/enclos): talk to the agents in your enclos sandboxes from your phone, over Tailscale. Android first, then iOS.

Status: a skeleton. The app starts and shows the version of its Rust core; nothing talks to a host yet. The plan is in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145).

## Build

Needs Rust (the version in `rust-toolchain.toml` is installed by `rustup`), the Android SDK and NDK, JDK 17 and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```sh
just android
```

It builds the Rust core for `arm64-v8a` and `x86_64`, generates its Kotlin bindings, and builds the debug app in `android/app/build/outputs/apk/debug/`. Each pull request's CI also uploads that debug build as an artifact (`enclos-debug-apk`).

## License

MIT
