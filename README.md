# enclos-app

The mobile app for [enclos](https://github.com/victorbonnet/enclos): talk to the agents in your enclos sandboxes from your phone, over Tailscale. Android first, then iOS.

Status: a proposal being built, Android only. The app pairs with a host's bridge (`enclos app pair`), lists its agents and talks to them; push notifications and iOS are not built. What it does is in [`docs/spec.md`](docs/spec.md), its decisions in [`docs/adr/`](docs/adr/), the plan in [victorbonnet/enclos#145](https://github.com/victorbonnet/enclos/issues/145). Nothing in it is a security guarantee yet.

Agents can send [static HTML artefacts](docs/spec.md#5-messages): a self-contained page opens directly in the app, with Back and Share. Pages use inline CSS and embedded images or fonts; scripts, external resources and automatic navigation are unavailable. Tapping a web link shows its host and address, then opens it in the browser's Custom Tab where supported; the private page stays in its viewer. The channel tells the agent to write a UTF-8 `.html` file and send `MEDIA:/absolute/path/page.html`. HTML in a conversation's text still shows literally.

[Attachments open in the app](docs/spec.md#5-messages) when tapped, with Share available from the viewer. Images the phone decodes keep their full-screen viewer, and any other image opens as a document; PDFs have native scrolling and zoom, and UTF-8 text files up to 1 MiB show as selectable text. Unsupported documents, password-protected PDFs and failed PDF or text previews show a notice with **Share**, and **Open in another app** for a listed document type whose name has a matching extension: HTML, XHTML, SVG, XML, APKs and the like are offered to Share only. Handing a file to another app is always an explicit action, through the system's chooser.

Conversations can be [archived and restored](docs/spec.md#archived-conversations) across paired devices. Archived conversations stay searchable with their history intact; new activity leaves them archived. The bridge owns the flag, so Hermes and a future OpenClaw channel use the same behaviour without a runtime-specific archive command.

Supporting agents expose [Routines](docs/spec.md#routines): scheduled instructions, delivery choices and advanced runtime settings, with edit, pause/resume, run and delete controls. The runtime keeps and executes them while the phone is closed. Routines use an optional generic channel contract; Hermes is the first implementation.

Agents can have [shared display names](docs/spec.md#agent-display-names), saved by the host and synchronized across phones. `comptoir-dev` can appear as **Gégé Commit** while keeping its address and `@handle`. Renaming needs a supporting host; updating only the phone does not enable it.

Supporting agents ask [questions with native choices](docs/spec.md#questions): single or multiple selection, Other, free text and Skip inside the conversation. Submitting answers the exact waiting question. Hermes uses its existing `clarify` tool, guided by a bundled channel skill. This needs an updated bridge and channel; ordinary numbered messages remain text.

[Runtime approvals](docs/spec.md#runtime-approvals) have native scope buttons too: review the complete command and reason, then Allow once, Allow for session, Always allow or Deny when offered. The exact native request stays with the runtime; approving it changes no enclos sandbox policy. Updated Hermes APIs, bridge and channel are required. Without them, an approval arrives as text that shows the whole command and says the app cannot decide it, and typed approval commands are refused from everyone. Android is implemented; the spec and ADR define matching iOS behaviour.

With an updated bridge, the host's person can create a sandbox and install a runtime from Agents on a paired phone. Runtime login and app channel setup continue on the host; restart the bridge after setup to list the agent.

## Build

Needs Rust (the version in `rust-toolchain.toml` is installed by `rustup`), the Android SDK, the NDK version named in `android/gradle/libs.versions.toml` (`sdkmanager "ndk;<version>"`), JDK 17 and [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk).

```sh
just android
```

It builds the Rust core for `arm64-v8a` and `x86_64`, generates its Kotlin bindings, and builds the debug app in `android/app/build/outputs/apk/debug/`. Each pull request's CI also uploads that debug build as an artifact (`enclos-debug-apk`). Only debug builds exist so far, and they are debuggable: with USB debugging on, `adb shell run-as` reads the app's private storage, so what [the spec](docs/spec.md#8-on-the-phone) says about data at rest holds for a release build, which is not configured yet.

Debug builds are signed with the project's one debug key, so any build installs over any other ([ADR 0004](docs/adr/0004-one-debug-signing-key-kept-out-of-git.md)). The key is not in git: put the keystore at `~/.android/enclos-debug.keystore` (or point `ENCLOS_DEBUG_KEYSTORE` at it); CI reads it from the `ENCLOS_DEBUG_KEYSTORE_B64` secret. A new machine gets it copied from one that has it, over a private channel (never the repository, an issue or a chat); `*.keystore` and `*.jks` are ignored by git. Without it, a build is signed with the machine's own debug key and installs only after uninstalling the app, which forgets its pairings.

## License

MIT
