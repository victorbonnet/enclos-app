fmt:
    cargo fmt --all -- --check

clippy:
    cargo clippy --locked --all-targets --all-features -- -D warnings

test:
    cargo test --locked --all-features

deny:
    cargo deny check

# The Rust core for the app's ABIs, and its Kotlin bindings
core-android:
    sh scripts/build-core-android.sh

# Needs the Android SDK and NDK, and cargo-ndk
android: core-android
    cd android && ./gradlew --no-daemon :app:assembleDebug :app:lintDebug

ci: fmt clippy test deny
