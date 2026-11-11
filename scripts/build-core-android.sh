#!/bin/sh
# Builds the Rust core for the Android ABIs the app ships and generates its
# Kotlin bindings, into the app's (git-ignored) source folders:
#   android/app/src/main/jniLibs/<abi>/libenclos_core.so
#   android/app/src/main/kotlin/generated/
# Needs cargo-ndk and the Android NDK named in android/gradle/libs.versions.toml
# (ANDROID_NDK_HOME, else $ANDROID_HOME/ndk/<that version>); any other NDK is
# refused, so the toolchain behind the shipped core changes only in a commit.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
main="$root/android/app/src/main"
cd "$root"

ndk=$(sed -n 's/^ndk = "\(.*\)"$/\1/p' android/gradle/libs.versions.toml)
if [ -z "$ndk" ]; then
    echo "build-core-android: no ndk version in android/gradle/libs.versions.toml" >&2
    exit 1
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    ANDROID_NDK_HOME="${ANDROID_HOME:?set ANDROID_NDK_HOME or ANDROID_HOME}/ndk/$ndk"
fi
found=$(sed -n 's/^Pkg\.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null || true)
if [ "$found" != "$ndk" ]; then
    echo "build-core-android: needs NDK $ndk, found '${found:-none}' in $ANDROID_NDK_HOME" >&2
    echo "  install it: sdkmanager \"ndk;$ndk\"" >&2
    exit 1
fi
export ANDROID_NDK_HOME

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
# Run from the repository root, where the generator's library mode finds
# core-ffi and its uniffi.toml through cargo metadata.
cargo run --locked --manifest-path tools/uniffi-bindgen/Cargo.toml -- generate \
    --library "$host_lib" \
    --language kotlin --no-format \
    --out-dir "$main/kotlin/generated"
