tool := "tools/uniffi-bindgen/Cargo.toml"

fmt:
    cargo fmt --all -- --check
    cargo fmt --manifest-path {{tool}} -- --check

clippy:
    cargo clippy --locked --all-targets --all-features -- -D warnings
    cargo clippy --locked --manifest-path {{tool}} --all-targets -- -D warnings

test:
    cargo test --locked --all-features

# The app's crates with no exception, then the binding generator with its own
# (cargo-deny reads the deny.toml next to each manifest)
deny:
    cargo deny check
    cargo deny --manifest-path {{tool}} check

# Both workspaces must use the same uniffi: the generated bindings must match
# the library's scaffolding
uniffi-versions:
    sh scripts/check-uniffi-versions.sh

# The Rust core for the app's ABIs, and its Kotlin bindings
core-android:
    sh scripts/build-core-android.sh

# Needs the Android SDK and NDK, and cargo-ndk
android: core-android
    cd android && ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

ci: fmt clippy test deny uniffi-versions
