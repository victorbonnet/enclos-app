#!/bin/sh
# The binding generator (tools/uniffi-bindgen) and the app's core must use the
# same uniffi release: the Kotlin bindings it writes must match the library's
# scaffolding. Each has its own lockfile, so check they agree.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)

uniffi_version() {
    awk '/^name = "uniffi"$/ { getline; gsub(/version = |"/, ""); print }' "$1"
}

app=$(uniffi_version "$root/Cargo.lock")
tool=$(uniffi_version "$root/tools/uniffi-bindgen/Cargo.lock")

if [ -z "$app" ] || [ "$app" != "$tool" ]; then
    echo "uniffi differs: app ${app:-none} (Cargo.lock), generator ${tool:-none} (tools/uniffi-bindgen/Cargo.lock)" >&2
    exit 1
fi
echo "uniffi $app in both workspaces"
