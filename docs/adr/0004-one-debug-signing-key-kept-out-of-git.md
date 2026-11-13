---
status: proposed
---

# One debug signing key, kept out of git

Android installs an update only when it is signed with the same certificate as the installed app. With no key configured, each machine signs debug builds with a debug key of its own, and each CI run with a new one: every APK from CI, and every build from another laptop, installed only after uninstalling the app, which deletes its device keys, its pairings and the conversations it kept.

Decision (proposed): **debug builds are signed with one project debug key, kept out of git**: on a laptop at `~/.android/enclos-debug.keystore` (or `ENCLOS_DEBUG_KEYSTORE`), in CI from the `ENCLOS_DEBUG_KEYSTORE_B64` repository secret. A build without it falls back to the machine's own key, and CI says so.

Reason: any debug build then installs over any other. The key stays out of the repository because an update signed with it runs as the same app: whoever holds it could ship an APK that uses the installed app's device keys and reads its conversations, if the person installs it.

Who can read it in CI: GitHub gives the secret to every `pull_request` run from a branch of this repository (not from a fork), and the keystore is a file on the runner while Gradle runs, so any build script, Gradle plugin or dependency the `android` job executes can read it. That is accepted for a debug key, which only installs over debug builds; it is the reason the release key must not follow this job's pattern.

## Considered options

- **The debug keystore in git**: simplest, common for debug keys, but it gives that power to anyone who can read the repository.
- **Install only builds from one machine**: no setup, but no CI build installs over a laptop's, and a lost keystore means uninstalling everywhere.

## Consequences

- Changing the key, or losing it, makes every installed debug build need an uninstall once.
- Release builds will need a key of their own (Play App Signing); this one signs debug builds only.
