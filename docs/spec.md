# enclos app: what it does

The enclos app is a channel to the agents of a person's enclos hosts: from a phone, over their tailnet, they list each host's sandboxes and agents, talk to any of them, and read what an agent sends on its own, such as a cron job's output. enclos's side is in its design: [ADR 0042](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0042-a-bridge-on-each-host-serves-the-mobile-app.md) (a bridge on each host), [ADR 0043](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0043-sharing-an-agent-is-not-hosting-it.md) (sharing) and `docs/protocol.md` (the device leg), all proposed. The plan is in [enclos#145](https://github.com/victorbonnet/enclos/issues/145).

This file says how the app behaves, on every platform. Android is built; iOS is not started. **Both platforms behave the same and each looks native**: a behaviour here holds on both, and the [Platforms](#platforms) table says how each renders it. A pull request that changes a behaviour changes this file; a decision that is hard to reverse gets an ADR in `docs/adr/`.

Three kinds of statement are kept apart, as in enclos: what is decided (this text, an accepted ADR), what is proposed (an ADR with `status: proposed`), and what is still [to verify](#to-verify). Nothing here describes security behaviour as tested unless a test or a probe shows it.

Words are enclos's ([GLOSSARY.md](https://github.com/victorbonnet/enclos/blob/main/GLOSSARY.md)): host, sandbox, agent, runtime, channel, and for the app, bridge, person, paired device, conversation.

## 1. Shape

- **One Rust core, a native app per platform** ([ADR 0001](adr/0001-one-rust-core-and-a-native-app-per-platform.md)). The core (`core-ffi/`, over enclos's `enclos-protocol`) does the protocol's cryptography: pairing links, the Noise handshake, sealing and opening messages, and push previews. Each app does everything else natively: screens, the connection, storage, keys.
- **Hosts are independent.** The app pairs with each host's bridge on its own and gathers their agents into one list. No host knows of another.

## 2. Pairing

1. On the host, `enclos app pair` shows a QR code and a link (`enclos://pair?…`) that works once, for 5 minutes, while `enclos app serve` runs.
2. The person scans the code (or types the link). The app reads the host's name, its bridge's address and the link's expiry, and shows them with a countdown.
3. The app makes the device's signing key in the platform's key store (StrongBox where the phone has it) and a Noise key pair, wrapped by the key store.
4. It connects, sends a pairing request (its model name, its public key, a proof from the link's code), and shows the terminal's question: `<model> (tailscale login: <your login>) wants to pair. Accept? [y/N]`. The person answers in the terminal; the app shows Paired or why not (refused, or the connection ended).

The pairing screen has three steps, always in this order: code read, device key made, accept on the host. Its copy states what happens, never how safe it is.

## 3. Connection

- The app opens a connection to each paired host when it comes to the foreground, and keeps it while the phone lets it.
- A connection that ends while the app is in the foreground is opened again after 2 s, then 4, 8, 16, 32, then every 60 s, until it is authenticated. Reconnect in Status tries at once.
- On each connection the app asks for the host's agents and syncs every message since the start; the bridge sends each message with the time it was stored.
- In the background the app does not connect. Until push notifications are built, what arrives meanwhile is shown at the next sync.
- When the bridge reports an error (an agent it could not reach), the conversation and Status show it, in the bridge's words.

## 4. Screens

Three destinations, Inbox, Agents and Status, and two full screens over them, a conversation and pairing. A destination starts with its title, with no bar above it, so its content starts high; its one action, if any, sits on the title's line. The app is in portrait only, for now, the QR scanner included.

### Inbox

Every conversation with every agent of every host, the most recent first; a conversation with no message yet comes first. A row shows the agent's avatar (its initial inside the logo's corners, in `accent` while its host is connected), the agent's name, the conversation's [name](#names) (its address `host/sandbox/agent` while it has none), the time of the last message (today: `07:12`, this week: the weekday, else `3 Nov`), an "on its own" header when the agent sent it unprompted, the last message's words without markdown on two lines at most (the person's own prefixed "You:"), and the unread count. The extended button "New conversation" opens Agents. With no host paired, the inbox says how to pair one.

**Search and filters.** A search field under the title finds conversations by their name, their agent's address, and what was said in them (the agent's messages as their words, without markdown), whatever the case and the accents; with several words, each must be found, in the name or in any of its messages, not necessarily the same one. A conversation found by a message shows that message, the newest that holds the most words, in place of the last, with the words in `accent`. Search reads every message, so it runs off the main thread, each message's words worked out once while the app runs. Under the field, one filter at a time: All, Unread, and each agent when there are several (by its name, or `sandbox/agent` when two share one). Nothing found says so.

### Names

A conversation has a name: its agent's, from what was said in it (Hermes names each session; the plugin passes the model's summary on, not the first message's cut), or the person's. Tapping the name in a conversation's bar renames it; the person's name replaces the agent's, which no longer replaces it, and clearing it lets the agent name it again. The bridge keeps the name and sends it to every device of the person (enclos `docs/protocol.md`, A conversation's name), so a name is saved only while its host is connected: until then the rename dialog says so and cannot save. A name is shown without the characters that change how text reads unseen (bidirectional overrides, zero-width characters: the app's `Visible` rule), whoever gave it.

### Agents

Each paired host, with a filled square while connected (`connected · tailnet`) and an outline otherwise (why it is not); under it each sandbox, with its runtime and, once the bridge has reached the agent's plugin, what its channel does: `full channel` (messages both ways and messages on its own, the plugin announced `notify`) or `request/reply`. Under each sandbox its agents; tapping one starts a new conversation. An action on the title's line pairs another host. A person sees only what is shared with them; the list never hints that more exists.

### Status

Read-only: each paired host, its connection (who it is connected as, or why not), the bridge's last error, its address, and Reconnect when it is not connected; the [theme](#7-theme); Pair a host; the core's version. Nothing here acts on a sandbox.

### Conversation

The conversation's name in the bar (the agent's name while it has none), the agent and its address under it; tapping them renames it. Messages bottom-up, anchored at the newest: the screen follows what arrives unless the person scrolled up to read, and stays anchored when the keyboard opens. A day separator in lowercase (`today`, `yesterday`, `mon 3 nov`) starts each day. Each message has its time (`HH:mm`).

- The person's messages: right, on `button-bg`, shown exactly as typed.
- The agent's: left, on `surface` with a hairline, [markdown](#5-messages) drawn, selectable to copy. One the agent sent on its own (`kind: notify`, such as a cron job's) has an "on its own" header; the protocol does not say which job.
- **The agent at work**: three squares rising in turn, a 900 ms wave, from the moment the person's message is sent until the agent answers, and whenever the runtime says it is typing. It stops when the connection ends.
- Under the messages, why the host is not connected, or what the bridge said, when there is something to say.

Opening a conversation marks it read: unread counts are the agent's messages after the last one read, kept on this device only.

### Pairing

Section 2. Full screen, with a back arrow. The viewfinder is the logo: the Home base corners in `lime` on `panel-deep`.

## 5. Messages

An agent's message is markdown ([ADR 0002](adr/0002-messages-are-markdown-drawn-by-the-app.md)): CommonMark, with GitHub's tables, strikethrough and bare links. The app draws it itself:

| Markdown | Drawn as |
| --- | --- |
| `**bold**`, `*italic*`, `~~struck~~` | semibold, italic, a line through |
| `` `code` `` | mono, in `accent` |
| `# Heading` (1 to 6) | semibold, 19, 17, then 15 sp/pt |
| lists, ordered or not, nested | a mono marker in `text-faint`, then the item |
| `> quote` | a 2 dp `accent` bar, the text in `text-muted` |
| fenced or indented code | mono on `panel-deep`, scrolling sideways |
| table | columns as wide as their widest cell (up to 240), header bold, hairlines between rows, scrolling sideways |
| `---` | a hairline |
| a single newline | a line break (CommonMark would join the lines; chat is written line by line) |

Nothing in a message is fetched or run:

- an image (`![alt](url)`) shows as its alt text and `↗`, a link to it; the app never loads it;
- HTML shows as its literal text;
- a link opens, in the browser, only when it is `http`, `https` or `mailto`; any other scheme is plain text.

The person's own messages are not markdown: they show as typed. Previews (inbox, and later notifications) are the words without the marks.

The runtime's plugin tells the agent what the app draws, so that it formats when it helps (Hermes: the plugin's `platform_hint`, in enclos).

## 6. Motion

Short and functional, never decorative: something moves to show where it came from or went. Durations: 150 ms for a change in place, 250 ms for a screen or a message arriving, 150 ms for what leaves. Arriving decelerates, leaving accelerates.

- A full screen arrives over the tabs: the tabs fade out drifting left, the screen fades in from a tenth of the width. Going back, the screen slides off to the right, opaque, the tabs drifting back under it; with the back gesture it follows the finger and goes or returns where it is let go.
- Tabs fade through each other (200 ms); the corners close in on the tab chosen.
- A message fades in while the others slide up; a conversation that moves to the top of the inbox slides there.
- Badges, chips, pairing steps, the send button and the theme change smoothly.
- With the system's animations turned off, transitions end at once and the working wave stands still.

## 7. Theme

Light or dark: the system's setting by default. Once the person chooses (System · Light · Dark, in Status), the app keeps their choice on this device until they choose System again. Switching crossfades the colours; the screen is not rebuilt. The colours are the design system's tokens, the same in both apps.

## 8. On the phone

- **Keys**: the device's signing key is made in, and never leaves, the platform's key store; the Noise key is wrapped by it.
- **Stored**: the paired hosts (each bridge's address and key, the device's id there, the wrapped Noise key), the last message read per conversation, the theme. Conversations are kept in memory and synced again from the bridge.
- **Nothing leaves the phone** ([ADR 0003](adr/0003-nothing-of-the-app-leaves-the-phone.md)): no backup, no transfer to a new phone. A new phone pairs again.

## Platforms

| | Android (built) | iOS (not started) |
| --- | --- | --- |
| UI | Jetpack Compose, Material 3 structure in enclos's tokens | SwiftUI, the platform's structure in the same tokens |
| Core | `core-ffi` through UniFFI's Kotlin bindings (JNA) | the same crate through UniFFI's Swift bindings |
| Destinations | bottom navigation bar; the active tab inside the logo's corners | `TabView`, the same framed active tab |
| Destination title | title at the top, no bar above (28 sp); its action on the same line | large navigation title (34 pt), its action in the navigation bar |
| Portrait only | `screenOrientation="portrait"` (Android 16+ ignores it on large screens) | supported orientations: portrait (`Info.plist`) |
| New conversation | extended FAB | compose button in the navigation bar |
| Back | arrow; predictive back gesture follows the finger | chevron with the previous title; the interactive swipe from the edge |
| Pairing | full screen | sheet with Cancel |
| QR scanning | ZXing's scanner activity | the system camera (VisionKit's data scanner) |
| Keys | Android Keystore, StrongBox where available | Keychain and Secure Enclave, `ThisDeviceOnly` |
| Nothing leaves the phone | `allowBackup="false"`, data extraction rules exclude every domain from cloud backup and device transfer | Keychain items `ThisDeviceOnly`; files excluded from backup |
| Markdown | commonmark-java (+ GFM tables, strikethrough, autolink), drawn in Compose | Apple's swift-markdown (cmark-gfm), drawn in SwiftUI |
| Theme kept | app preferences; `UiModeManager.setApplicationNightMode` on Android 12+ | `UserDefaults`; `preferredColorScheme` |
| Animations off | Remove animations (animator duration scale 0) | Reduce Motion |
| Push (not built) | FCM, one notification channel per agent | FCM through APNs, the conversation as thread identifier |

## Not built yet

Push notifications (the bridge encrypts a preview for the device; the app decrypts it and fetches the message over the tailnet; two actions only, Reply and Mark read), sharing (ADR 0043), an app lock, landscape, the notification card of the design.

## To verify

- **Transfer to a new phone** excludes the app's data: the rules say so, but no transfer was run (Android).
- **iOS** equivalents in the table above: none is built.
