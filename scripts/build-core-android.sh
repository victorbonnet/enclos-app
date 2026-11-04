#!/bin/sh
# Builds the Rust core for the Android ABIs the app ships and generates its
# Kotlin bindings, into the app's (git-ignored) source folders:
#   android/app/src/main/jniLibs/<abi>/libenclos_core.so
#   android/app/src/main/kotlin/generated/
# Needs the Android NDK (ANDROID_NDK_HOME, or found under ANDROID_HOME) and
# cargo-ndk.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
main="$root/android/app/src/main"
cd "$root"

# The app's minSdk (android/app/build.gradle.kts)
cargo ndk --platform 28 -t arm64-v8a -t x86_64 -o "$main/jniLibs" \
    build --locked --release -p core-ffi

# The bindings come from an unstripped host build of the same crate: the
# release profile strips the shipped libraries, and with them the metadata
# UniFFI reads.
cargo build --locked -p core-ffi
case "$(uname -s)" in
    Darwin) host_lib="$root/target/debug/libenclos_core.dylib" ;;
    *) host_lib="$root/target/debug/libenclos_core.so" ;;
esac
rm -rf "$main/kotlin/generated"
cargo run --locked -p uniffi-bindgen -- generate \
    --library "$host_lib" \
    --language kotlin --no-format \
    --out-dir "$main/kotlin/generated"
