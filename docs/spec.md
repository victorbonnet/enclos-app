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
2. The person scans the code, types or pastes a link, or taps it wherever it is: the app opens on any `enclos://pair` link. A chat or a note opens only https links, so `enclos app pair` also prints `https://<bridge>/pair#…`, a page of the bridge whose "Open in enclos" opens the `enclos://pair` link (enclos `docs/protocol.md`, Pairing); pasted in the app, that https link reads as the other. The app reads the host's name, its bridge's address and the link's expiry, and shows them with a countdown. A tapped link pairs only once the person confirms ("Pair with <host>?", Pair or Not now), since any page or message can hold one; a link opened while a pairing goes on is ignored, and so is one opened before (the system hands it again when it restarts the app, after an update): such a link can still be typed.
3. The app makes the device's signing key in the platform's key store (StrongBox where the phone has it) and a Noise key pair, wrapped by the key store.
4. It connects, sends a pairing request (its model name, its public key, a proof from the link's code), and shows the terminal's question: `<model> (tailscale login: <your login>) wants to pair. Accept? [y/N]`. The person answers in the terminal; the app shows Paired or why not (refused, or the connection ended).

The pairing screen has three steps, always in this order: code read, device key made, accept on the host. Its copy states what happens, never how safe it is.

## 3. Connection

- The app opens a connection to each paired host when it comes to the foreground, and keeps it while the phone lets it.
- A connection that ends while the app is in the foreground is opened again after 2 s, then 4, 8, 16, 32, then every 60 s, until it is authenticated. Reconnect in Settings tries at once.
- On each connection the app asks for the host's agents and syncs the messages after the newest one it [keeps](#8-on-the-phone) from that host (every message, the first time); the bridge sends each message with the time it was stored.
- In the background the app does not connect. Until push notifications are built, what arrives meanwhile is shown at the next sync.
- When the bridge reports an error (an agent it could not reach), the conversation and Settings show it, in the bridge's words.

## 4. Screens

The first screen has two views, Inbox and Agents, chosen with the switch at the top. Swiping left on the main content opens Agents; swiping right returns to Inbox. The content follows the finger and settles on a view when released; there is no wrapping at either end. The switch stays in sync and can still be tapped. Vertical swipes scroll the current list. The search and compose controls stay in place below the content, with the same search text across both views. Settings and the other full screens (a conversation, pairing and a policy request) open over the first screen. Back from Agents returns to Inbox. Android is built; iOS follows when implemented. The app is in portrait only, for now, the QR scanner included.

### Inbox

By agent, within the selected active or archived view: one row per agent with a matching conversation, the agent with the newest matching conversation first. In the active view, only its conversations with something unread show under it while folded, so a new message is one tap away without the list growing with every conversation. The archived view shows all matching conversations expanded.

**An agent's row** shows its avatar (its initial inside the logo's corners, in `accent` while its host is connected, pulsing while it works), its name, the time of its newest message in any conversation (today: `07:12`, this week: the weekday, else `3 Nov`), and its [Home](#home)'s last message (words without markdown on two lines at most, the person's own prefixed "You:") with Home's unread count. While the agent works, the wave and what it does take the message's place, with where when that is not Home (`· in Weather in Lyon`, `· in 2 conversations`). Tapping the row opens Home.

**What is folded** is said at the end of the agent's group, on a line of its own starting where its conversations do, in `accent`, with a chevron pointing down: `17 conversations`, or `15 more conversations` when some show already. Tapping it unfolds them all, newest first, and the line, now at the end of the list, says `Show less` with the chevron pointing up. An agent stays unfolded while the app runs; with nothing folded away, there is no line.

**A long press on an agent's row** (with a haptic tick) opens its menu under its name: **New conversation** with it (not while its host no longer lists it), **All conversations** or **Fewer conversations** (as the line above, when some are folded away), and, under a line, **About** the agent, its page. Its routines will come here later.

**Sections fold.** The Groups and Agents headings each fold their section away, or back, when tapped: the name, small and faint with no line under it, and a chevron at its end, pointing down while open and right while folded, turning between the two. A folded section shows its unread count by its name. What is folded stays so across starts of the app; a search shows all it finds, with the headings unfoldable meanwhile.

**A conversation under its agent** starts where the agent's name does, without an avatar: its [name](#names) ("No name yet" while it has none), the time of its last message, an "on its own" header when the agent sent it unprompted, the last message's words (or, while the agent works on it, the wave and what it does), and the unread count. A conversation with no message is not listed (one left empty, here or on an earlier version of the app). The extended button "New conversation" opens Agents. With no host paired, the inbox says how to pair one.

**Search and unread only.** A search field under the title finds conversations by their name, their agent's address, and what was said in them (the agent's messages as their words, without markdown), whatever the case and the accents; with several words, each must be found. A conversation found by a message shows that message, the newest that holds the most words, in place of the last, with the words in `accent` (Home's, on its agent's row). An action on the title's line, the inbox's bubble with a square at its corner, shows only the conversations with something unread, and everything again when tapped again: drawn in outline and `text-muted` while off, filled and in `accent` while on. There is no filter by agent, since the inbox is by agent already. While searching or showing unread only, only agents with something found show, and everything found shows under them, folded or not, with nothing to unfold. Nothing found says so.

### Archived conversations

The Inbox starts with active conversations. Its archive filter offers **Active conversations** and **Archived conversations**, keeping search and unread-only filtering available within the selected view. Policy requests appear only in the active view; groups remain their own section in both views. An archived conversation keeps its identity and every message, file description, name, read position and group member. The Archived view can find it by the same names, addresses and message text as the active view; **Unarchive** restores that same conversation and history ([ADR 0006](adr/0006-archived-conversations-are-a-restorable-inbox-view.md), proposed).

**Archive** is available from a conversation's actions and its long-press menu; archived conversations offer **Unarchive** instead. Both require the host to be connected; while offline the disabled control says `Connect to <host> to change archive state.` While waiting it says **Saving archive state…** Nothing is queued offline or hidden optimistically: the list changes when the bridge sends the new metadata. The bridge owns this state and sends it to the person's other paired devices and at every sync, so reconnecting, clearing the phone cache or pairing another phone restores the same archived state.

Conversation metadata carries the bridge's revision; the phone keeps the highest it saw for each conversation and ignores a lower one, so a delayed broadcast cannot undo a newer archive, name or read state. Revisions assume a bridge's database is never reset or restored from an older copy: after that, a reused id (a Home's) keeps its older state on the phone until the person clears the cache. The phone keys conversations by id. A Home's id, `home.<sandbox>.<agent>`, is the same on two hosts that both have that sandbox and agent: the phone keeps the first host's and leaves the second's out, with its messages, rather than mixing them or letting one replace the other. Showing both needs the cache keyed by host as well (see [To verify](#to-verify)).

Archived conversations contribute no preview, newest-message ordering or unread count to the active Inbox and its folded-section counts. An agent with no conversation in the selected view is absent. Its header opens Home only when Home belongs to that view; otherwise it is a heading for its matching conversations, with no Home preview or tap target. Their stored read positions remain intact; opening one marks it read by the ordinary rule below. A new reply, status line or cron output stays in that archived conversation and leaves it archived. Direct, group and Home conversations use the same actions. Archiving leaves running work and group discussions alone, and agents stay available in Agents. It calls no runtime archive command, resets no context and removes no saved memory: Hermes and a future OpenClaw channel share this app behaviour through enclos's conversation protocol.

### Home

Each agent has one Home conversation, which its host's bridge makes and keeps (enclos ADR 0044, `docs/protocol.md`, Home): the runtime's home channel. What belongs to no conversation lands there: a cron job that has no conversation of its own, a message the agent sends with no other place to go, the runtime's notices about itself. It is named Home, opened by its agent's row in the inbox, shown even when empty ("Cron jobs and the agent's notices come here."), and the person may talk to the agent in it too. A cron job started in a conversation still answers in that conversation. Hermes's `/sethome` is answered by its plugin, not run: there is nothing to set.

An explicit archive applies to Home as it does to another conversation. It keeps the same id and delivery route, continues receiving output, and can be opened from Agents while archived without restoring it to the active Inbox. Opening it still marks its messages read normally; only Unarchive changes its archived state.

**Status lines.** The runtime's notices about itself (Hermes: the gateway online, shutting down, restarting) are not the agent speaking: they come as `status` and show as a quiet centred line, small and faint, with their time, never a bubble. They never count as unread, and the inbox previews the last other message when there is one.

### Sandbox policy requests

**Above Groups in the inbox**, a Sandbox requests section shows pending, applying and failed policy requests from all paired hosts, newest first, even when there is no conversation. Its heading folds the section away and back, with the same chevron as Groups and Agents and the request count beside it while folded. The folded state stays across starts; a search temporarily unfolds matching requests and disables folding, as for the other sections. Search matches their target, sandbox and host; the unread-only filter does not hide them. Each request is a compact two-line list row, without a card or inline decision controls: the action on one line (a file target uses its final name, such as `Write to enclos`), then its agent when known, sandbox and host on one metadata line. The requesting agent's usual round, coloured avatar leads the row at 32 dp, matching that same agent elsewhere, and their name appears first in its metadata. A proxy-only refusal has no known agent: it uses the Enclos mark in grey, in the same 32 dp space and says `Agent unknown`, without attributing it to an agent. Both lines truncate with an ellipsis. Saving, failed and unavailable requests add a short status to the metadata. The whole row opens its detail page. Its spacing, sans-serif metadata, title-line chevron and absence of dividers match the inbox's agent rows. A proxy refusal names the sandbox, since its host cannot identify an agent. The full target, policy scope, reasons and **Approve** / **Deny** controls appear only on the detail page.

Tapping the row opens its detail page: attempted action and selectable target, who requested it, the exact scope the host generated, private-address access if requested, and whether all running sandbox work ends for a restart. It links to the originating conversation when one is known, and shows the decision controls at the bottom. A file change requiring a restart says **Approve & restart**; unsupported scopes explain why and keep approval disabled. It says approval persists for every agent of that sandbox and updates their policy knowledge for later work. An approval does not automatically retry the attempted action. Network scopes are one host:port; write scopes cover the named path and everything beneath it. Sensitive read denials remain unavailable for approval. Host behaviour is [enclos ADR 0046](https://github.com/victorbonnet/enclos/blob/codex/mobile-policy-approvals/docs/adr/0046-the-hosts-person-approves-sandbox-policy-scopes-from-the-phone.md), proposed; this is a sandbox policy request, not a runtime tool approval.

Only the host's own person on a still-paired device may decide. Buttons need a connected host and a completed fresh sync, remain disabled during a decision, and show Saving decision. Nothing is queued offline or optimistically marked approved. A changed policy refreshes the preview; the person must review it again. A request decided on another device shows that result. Completed approvals and denials leave the inbox but stay on an open detail page; failed or interrupted applications stay visible with a reason to review the host's policy. They are not retried automatically.

Requests are kept in the app's private preferences, covered by its existing no-backup rules. On authentication the host's cached requests are replaced by its fresh sync; forgetting that host or clearing the cache removes them. They show offline with both controls disabled. Android is built; iOS follows the same behaviour when implemented.

Connections attributed to a host policy-test run stay blocked and logged but never become inbox requests, including private-IP probes. The proxy verifies a host-registered, sandbox-scoped marker; arbitrary client markers cannot hide normal traffic. A genuine agent request to the same address remains visible. The built-in network probe's reserved `probe.enclos.invalid` destination is not an approval request. The host suppresses new notifications for that exact host and retires existing pending ones as diagnostics without changing policy. Such notifications leave the inbox; a detail page already open says **Diagnostic probe · no approval needed** and offers no decision controls. Real destinations, including private addresses, remain eligible for requests.

### Groups

A group is one conversation between the person and two to six agents of **one sandbox**, which the host's bridge keeps and whose turns it runs (enclos ADR 0045, proposed; `docs/protocol.md`, Groups): the app shows it and sends the person's messages.

- **Starting one**: the compose button's agent chip lists **New group** when a sandbox has at least two agents. Its agents are ticked (once one is, only the agents of its sandbox can be), with a name or none (their names then); the first message starts the group (`group.new`, then the message).
- **In the conversations view**: a **Groups** section above the agents, each group with its members' avatars overlapping, its name, its last message with who said it, how long ago or the working wave, and its unread count. Groups are not in an agent's tree.
- **In the group**: the members' avatars and handles in the bar; each agent message with its speaker's avatar and name above it. Typing `@` lists the members matching what follows, with their avatars, and **@all** last ("everyone answers"); picking one puts `@handle ` in the field. `@handle` addresses that member, several those members, `@all` every member, each of whom must answer; a message naming no one invites them all, and they may pass.
- **Where it is**: a line over the field, from the bridge's `group.state`: `@coder is answering · round 1 of 3` with the wave and **Stop** (`group.stop`, which cancels what is queued and the running turn; what was said stays), then Settled, Paused after the rounds or the replies allowed, Stopped, or Failed with why.

### Names

A conversation has a name: its agent's, from what was said in it (Hermes names each session; the plugin passes the model's summary on, not the first message's cut), or the person's. Tapping the name in a conversation's bar renames it; the person's name replaces the agent's, which no longer replaces it, and clearing it lets the agent name it again. The bridge keeps the name and sends it to every device of the person (enclos `docs/protocol.md`, A conversation's name).

### Agents

**By host, then sandbox.** Each paired host has a heading, even when it is the only one: its connection (a filled square in `accent` while connected, an outlined one otherwise) and its name, with the chevron of the conversations view's sections, folding the host's sandboxes and agents away or back; what is folded stays so across starts, and a search shows only the hosts with something found, unfolded. A host with no agent yet says the host lists them once connected. Under it, each sandbox's heading (the Enclos mark, its name, its runtime), then its agents. No heading has a line under it.

**A sandbox's page.** Each sandbox heading in the agents view ends with a chevron, and tapping it opens the sandbox's page: its mark (in `accent` while its host is connected), its name, runtime and host; its agents as the agents view shows them, each opening its page (back from which returns to the sandbox's page); its groups, each opening; what it runs on (runtime, host and connection); a Policy row with accepted-access count and active version, opening the policy page below; and a line saying its remaining state and sessions show on the host (`enclos sandbox show <sandbox>`).

First, while any agent works, **Working now** and their count: each agent at work, its avatar pulsing, where it works (the conversation's name, or `in 2 conversations`) and what it does (the tool it runs, as its label says, else `working`); tapping one opens that conversation, the one with the newest message if several. An agent works on a conversation from the person's message until its answer, while its runtime says it is writing, and while a tool it started has not ended; the phone knows only what comes over the bridge, so not, for instance, a cron job the runtime runs without saying so.

Then each paired host, with a filled square while connected (`connected · tailnet`) and an outline otherwise (why it is not); under it each sandbox, with its runtime and, once the bridge has reached the agent's plugin, what its channel does: `full channel` (messages both ways and messages on its own, the plugin announced `notify`) or `request/reply`. Under each sandbox its agents, each `working` with the wave while it works, else when it last sent a message (`active 3 hours ago`, `active yesterday`, `active 2 days ago`, its runtime's notices aside), its address before its first one; tapping one opens a new conversation, a draft on this phone until its first message, which starts it on the bridge (`conversation.new`, then the message): a conversation opened and left leaves nothing behind. An action on the title's line pairs another host. A person sees only what is shared with them; the list never hints that more exists.

### Agent photos

Tapping the avatar on the agent page opens the photo picker. When a photo is set, **Remove photo** appears below it. There is no separate Change photo button. A photo belongs to the agent on its host and is shared across apps connected to that host. The host owner can edit it while connected to a supporting host; other paired people can see it. The platform's photo picker grants access only to the selected photo. Cancel leaves it unchanged. The app prepares a small square photo, then waits for host confirmation; progress and failures appear by the controls. Remove asks for confirmation and restores the coloured initial everywhere. An older host keeps the initial and disables changing the photo.

The same round photo appears in agent and inbox rows, agent pages, conversation headers, group stacks and speakers, mentions and identified sandbox requests. Working indicators remain visible. With no custom photo, existing initials and host connection styling remain. Photos are scoped by full host/sandbox/agent identity, so equal agent names on different sandboxes or hosts do not share an image.

The host stores and versions the asset ([enclos protocol](https://github.com/victorbonnet/enclos/blob/codex/mobile-agent-images/docs/protocol.md#shared-agent-photos)). Connected apps receive changes and removals, and reconnect fetches the current version. A stale edit is refused and refreshed so it cannot overwrite another device's change. Cached photos survive app starts for offline display, with editing disabled offline. Clear cache and Forget this host remove only the phone's copies; the shared host photo remains. Android is built; iOS follows when implemented.

### Sandbox policy management

The sandbox page's **Policy** row opens its active policy. Entries are grouped into collapsible sections for allowed destinations, private network access, writable paths, protected read paths, network mode and incoming connections, resource limits, network logging and secret references. Active entries that came from approvals say **Accepted access**; inherited and sandbox-specific settings name their origin. The active policy is the source, so removed entries stop appearing even though an approval remains in history. All accepted entries remain discoverable regardless of the inbox history window.

The host's person can add, edit or remove an access entry and change default settings for **this sandbox only**. Tapping an editable entry opens a small editor; **Remove** requests a removal. **Review change** asks the host for exact effects, followed by **Save policy** or **Save & restart**. The review explains when all running work ends and services restart. No change is optimistic or queued offline. The host refuses stale previews, pending or unapplied changes, invalid paths or destinations and protected built-in read-deny edits. Removing the last private destination also removes its unused private-address opt-in; public network mode's continuing web access is disclosed. Every agent receives updated active-policy knowledge after a save. Secret references and network logging are read-only; secret contents are never sent.

Cached policy is visible offline and survives app starts, but editing waits for a fresh host response on the current connection. Refresh asks again; another device's save updates this page and dismisses an obsolete review. Connection loss or a missing confirmation requires refreshing before trying again. Clear cache and Forget this host remove cached policy. Only a still-paired device of the host owner may load or change it ([enclos ADR 0047](https://github.com/victorbonnet/enclos/blob/codex/mobile-policy-management/docs/adr/0047-the-hosts-person-manages-sandbox-policy-from-the-phone.md), proposed). Android is built; iOS follows when implemented.

### Settings

In sections, each named small, faint and in capitals above a hairline, its rows separated by hairlines. Nothing here acts on a sandbox.

- **Hosts**: each paired host, its connection (who it is connected as, or why not), the bridge's last error, its address, then Reconnect (when it is not connected) and Forget this host on one line; and a last row, Pair a host, with the QR glyph.
- **Security**: the [app lock](#app-lock), a switch beside what it does; the whole row toggles it.
- **Appearance**: the [theme](#7-theme).
- **On this phone**: what is kept, and Clear cache.
- **About**: the app's version and the core's.

- **Forget this host**, after a confirmation: connected, the app asks the bridge to unpair this phone (enclos `docs/protocol.md`, `device.forget`); not connected, the host keeps it listed until `enclos app unpair` there, which the confirmation says. Either way the phone deletes its device key and Noise key for that host, and everything it kept from it: agents, conversations, messages, files, how far they were read. The conversations stay on the host. Using it again means pairing again.
- **Clear cache**, after a confirmation: deletes the conversations, messages, agents and files kept on this phone, then syncs everything again from each connected host. Pairings stay, and how far each conversation was read comes back from the bridges.

### Conversation

The conversation's name in the bar (the agent's name while it has none), the agent and its address under it; tapping them renames it. Messages bottom-up, anchored at the newest: the screen follows what arrives unless the person scrolled up to read, and stays anchored when the keyboard opens. A day separator in lowercase (`today`, `yesterday`, `mon 3 nov`) starts each day. Each message has its time (`HH:mm`).

- The person's messages: right, on `button-bg`, shown exactly as typed.
- The agent's: left, on `surface` with a hairline, [markdown](#5-messages) drawn, selectable to copy. One the agent sent on its own (`kind: notify`, such as a cron job's) has an "on its own" header; the protocol does not say which job.
- **The agent at work**: three squares rising in turn, a 900 ms wave, from the moment the person's message is sent until the agent answers, and whenever the runtime says it is typing. It stops when the connection ends.
- **What it does** (runtimes with the `tools` capability): above the working wave, "Working · n steps" and each tool the agent runs, one a line: its mark (a square pulsing in `accent` while it runs, a check once done, a cross if it failed or a policy refused it), its name in mono, what it does (`ls -la`, `weather Lyon`) and how long it took. When the answer arrives, its steps fold into one line above it ("3 steps", with a chevron), which opens them. Steps come live from the bridge, which does not keep them: the phone keeps them with the answer they led to, so they stay after the app restarts, on that phone only (another device, or this one after missing them, shows the answer without its steps).
- Under the messages, why the host is not connected, or what the bridge said, when there is something to say.
- **Sending needs the host connected.** While it is not, the field still takes text, the send button stays off, and a line above the field says so (`Not connected to mac: send once it's back.`); nothing is queued to go later. What was written stays, for each conversation, while the app runs (leaving and coming back keeps it), and goes only once sent: a connection that ended at the moment of sending leaves it in the field.
- **The agent's commands** (runtimes with the `commands` capability; enclos `docs/protocol.md`, An agent's commands): typing `/` opens, over the field, the commands the agent's runtime lists, in its order, on `surface`; each `/name` in mono, what follows it faint (`[name]`, `<prompt>`), and what it does on one line. Typing more keeps the commands whose name starts with it, then those that have it elsewhere, whatever the case, with the typed part in `accent`; about four and a half rows show, the rest scroll. Picking one puts `/name ` in the field, the cursor after it, and the list closes, as it does once a space follows the name. A command is sent like any message, and the runtime answers it; the app runs nothing. The bridge sends the list after the agents' and when the runtime lists it anew; it is kept while the app runs.

Opening a conversation marks it read, as does a message arriving while it is open: unread counts are the agent's messages after the last one read. The bridge keeps how far each conversation was read and sends it with the conversation, at each sync and when it moves (enclos `docs/protocol.md`, How far a conversation was read): a message read on one device is not unread on another, nor after pairing again. The runtime cannot tell, as it does not see what the person reads. A conversation read further while offline tells the bridge at the next sync.

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
- tapping an `http` or `https` link opens the preferred browser's Custom Tab on Android; `mailto` uses the system mail handler. Any other scheme is plain text. The browser handles the opened website with its normal state and capabilities. A system URL handler is the fallback when Custom Tabs are unavailable; no handler or a refused launch produces a native notice. Nothing is prewarmed or prefetched.

The person's own messages are not markdown: they show as typed. Previews (inbox, and later notifications) are the words without the marks.

**Files.** An agent's message may carry files: an image it made, a chart, a document (enclos `docs/protocol.md`, Files). The app fetches each from the bridge over the conversation's connection, in parts, when the message is first shown, and keeps it in its cache (never backed up). An image shows in the bubble, above the words, decoded at the screen's size; tapped, it opens full screen, edge to edge, on near black in both themes (as photo viewers do: the image is the only colour), with Back, its name and Share over it on a shade, which a tap on the image hides or shows. Pinch zooms it, up to 6×, around the fingers; while zoomed it moves with a finger, its edges held at the screen's; a double-tap zooms 2.5× on that point, or back to the whole image. It is decoded at up to 4,096 px on its longest side, to stay sharp when zoomed. Other files show as a line with their name and size; tapping shares the file, or opens it when it is an HTML artefact as described below. Share hands that one file to another app through the system's share sheet. A file that did not come says why and is fetched again when tapped; a fetch the connection broke goes on from where it was. A message with files and no words previews as "Image", or the file's name.

**Static HTML artefacts.** A self-contained UTF-8 HTML attachment opens as an artefact ([ADR 0005](adr/0005-static-html-artefacts-open-in-a-separate-viewer.md), proposed). An attachment whose media type is `text/html` has a card with its name and size that offers to open the page; a `.html` name alone does not make another media type an artefact. The viewer fills the screen, with a native bar for Back, its name and Share, and a scrollable page below. Loading and a failure to read the page are shown there; Back and Share stay available. A page larger than the attachment limit of 25 MiB or with invalid UTF-8 is refused. Sharing explicitly hands the original HTML file to another app, whose rendering is outside this viewer's controls.

The page may use HTML, inline CSS and data images or fonts. JavaScript, storage, network requests, file and content access, forms, automatic outgoing navigation and native bridges are unavailable; relative resources do not load. A link to an anchor in the same page may scroll there. A deliberate tap on a main-frame `http` or `https` link opens it through the same browser handoff as a conversation link; the private page stays in its viewer. Subframes, redirects, navigation without a user gesture and other schemes cannot start a browser from the artefact. The opened website uses the browser's normal network access, cookies and site permissions, outside the private viewer's controls. The private page receives no keys or app state. These controls are written for Android; platform probes remain in [To verify](#to-verify). HTML in message text stays literal, as above. Interactive pages, external assets and multi-file sites are not part of this version. The file uses enclos's existing attachment delivery, cache and ownership checks; there is no separate artefact service or history.

The runtime's plugin tells the agent what the app draws, so that it formats when it helps (Hermes: the plugin's `platform_hint`, in enclos).

An initial static-viewer probe on an Android 17 / API 37 emulator, Google WebView 145.0.7632.218, rendered inline CSS, Unicode text and an embedded data PNG. The script did not execute; a frame reported `ERR_BLOCKED_BY_CSP`; external styles, images, frames and script fetches never reached a local request counter that a separate emulator request proved reachable. Form submission and file/content link taps left the page in place. The browser-link handoff and remaining platform probes are listed below.

## 6. Motion

Short and functional, never decorative: something moves to show where it came from or went. Durations: 150 ms for a change in place, 250 ms for a screen or a message arriving, 150 ms for what leaves. Arriving decelerates, leaving accelerates.

- A full screen arrives over the tabs: the tabs fade out drifting left, the screen fades in from a tenth of the width. Going back, the screen slides off to the right, opaque, the tabs drifting back under it; with the back gesture it follows the finger and goes or returns where it is let go.
- The Inbox and Agents views slide side by side with a horizontal swipe; tapping their switch slides to the chosen view in 250 ms.
- A message fades in while the others slide up; an agent that moves to the top of the inbox slides there with its conversations, and those it unfolds fade in.
- Badges, chips, pairing steps, the send button and the theme change smoothly.
- An agent at work: its avatar's corners swell out of it and fade, every 1.4 s; the working wave beside it.
- With the system's animations turned off, transitions end at once, the working wave stands still and an avatar does not pulse.

## 7. Theme

Light or dark: the system's setting by default. Once the person chooses (System · Light · Dark, in Settings, Appearance), the app keeps their choice on this device until they choose System again. Switching crossfades the colours; the screen is not rebuilt. The colours are the design system's tokens, the same in both apps.

## 8. On the phone

- **Keys**: the device's signing key is made in, and never leaves, the platform's key store; the Noise key is wrapped by it.
- **Stored**: the paired hosts (each bridge's address and key, the device's id there, the wrapped Noise key), the last message read per conversation, the theme, whether the app lock is on.
- **Conversations are kept on the phone**: each host's agents, the conversations, their names and archived state, every message with its files' descriptions, and the steps of each answer. They show when the app opens, before any connection, and while a host is unreachable; a sync then brings only what is newer. The copy is read off the main thread, in two passes: first what the first screens show (every conversation with its last message, and the last 40 messages of the 10 most recent), with the launch screen kept until then (at most a second) and the connections opened after it; then every other message, merged in. Until that second pass ends, search and unread counts see only the first pass's messages. The bridge stays the source: what the phone keeps is what the bridge sent, and a conversation started on the phone is kept once the bridge confirms it. Pairing again with a host forgets what it sent before. The copy is in the app's private storage, readable by the app only, and not encrypted by the app itself: it is as safe at rest as the phone's own storage encryption makes it. Files stay in the cache, which the system may clear: they are fetched again when shown.
- **Nothing leaves the phone** ([ADR 0003](adr/0003-nothing-of-the-app-leaves-the-phone.md)): no backup, no transfer to a new phone. A new phone pairs again.

### App lock

For a phone lost or left unlocked: with the app lock on, the app asks the person to prove it is them, with a biometric the phone has (fingerprint, face) or else the phone's own screen lock (PIN, pattern, password), when it opens and when it comes back after a minute or more in the background. Until then a lock screen covers the app (the mark, "Enclos is locked", and Unlock): it takes every touch, accessibility services do not read what is under it, and Back leaves the app. The prompt shows on its own once per lock; after a cancel, Unlock asks again, and the lock screen says what the system said (cancelled, too many attempts). While the lock is on, the recent apps show no picture of the app.

It is off until the person turns it on in Settings, Security, which needs a screen lock on the phone (else Settings says to set one) and asks to unlock once, so a lock that cannot be opened is never set; off again needs nothing more, since whoever turns it off is already in. A phone whose screen lock is removed has nothing left to unlock the app with: the lock then turns itself off as the app opens or comes back, rather than lock the person out, and Settings says so. It hides the app; it does not encrypt what the phone keeps, nor tie the keys to it: the conversations and keys are as safe at rest as the phone's own storage encryption and key store make them, and the connections stay open behind the lock, so messages keep arriving.

## Platforms

| | Android (built) | iOS (not started) |
| --- | --- | --- |
| UI | Jetpack Compose, Material 3 structure in enclos's tokens | SwiftUI, the platform's structure in the same tokens |
| Core | `core-ffi` through UniFFI's Kotlin bindings (JNA) | the same crate through UniFFI's Swift bindings |
| Home views | `HorizontalPager` below the top switch, which follows the active page; search and compose stay fixed | a native paging view below the switch, with fixed search and compose controls (not built) |
| Destination title | title at the top, no bar above (28 sp); its action on the same line | large navigation title (34 pt), its action in the navigation bar |
| Portrait only | `screenOrientation="portrait"` (Android 16+ ignores it on large screens) | supported orientations: portrait (`Info.plist`) |
| New conversation | extended FAB | compose button in the navigation bar |
| Agent commands | a list over the field, opening with `/` | the same list over the field (not the keyboard's suggestion bar) |
| Agent photos | system Photo Picker (`PickVisualMedia`, images only), no gallery permission; tappable round image and shared Remove control | Photos picker and native shared-photo controls (not built) |
| Sandbox policy management | collapsible policy sections, entry editor and host-generated review dialog | native policy list, editor and review (not built) |
| Sandbox policy requests | collapsible section of compact two-line rows above Groups; full-screen details with Approve / Deny | a collapsible native two-line list section above Groups and a navigation detail page with decision controls (not built) |
| Inbox by agent | an agent's row opens Home; a line ending its group (`17 conversations ⌄`, `Show less ⌃`) unfolds the rest | a `List` of rows, an agent's row opening Home and a button row ending its group |
| Back | arrow; predictive back gesture follows the finger | chevron with the previous title; the interactive swipe from the edge |
| Pairing | full screen | sheet with Cancel |
| QR scanning | ZXing's scanner activity | the system camera (VisionKit's data scanner) |
| Pairing links | `VIEW` intent filter on `enclos://pair`, `BROWSABLE`; `singleTask`, the link through `onNewIntent` while running | `enclos` URL scheme (`CFBundleURLTypes`); `onOpenURL` |
| Keys | Android Keystore, StrongBox where available | Keychain and Secure Enclave, `ThisDeviceOnly` |
| Nothing leaves the phone | `allowBackup="false"`, data extraction rules exclude every domain from cloud backup and device transfer | Keychain items `ThisDeviceOnly`; files excluded from backup |
| Markdown | commonmark-java (+ GFM tables, strikethrough, autolink), drawn in Compose | Apple's swift-markdown (cmark-gfm), drawn in SwiftUI |
| Theme kept | app preferences; `UiModeManager.setApplicationNightMode` on Android 12+ | `UserDefaults`; `preferredColorScheme` |
| Animations off | Remove animations (animator duration scale 0) | Reduce Motion |
| Conversations kept | SQLite, the platform's (`SQLiteOpenHelper`), `conversations.db`, written off the main thread | SQLite, the platform's, in Application Support, excluded from backup |
| Archived conversations | conversation actions and an Active / Archived Inbox filter; archived metadata kept in the same SQLite cache | native conversation actions and Inbox filter, with cached metadata (not built) |
| Files | cache `attachments/<id>/<name>`; shared through a `FileProvider` and `ACTION_SEND` | Caches directory; shared through `ShareLink` / `UIActivityViewController` |
| Static HTML artefacts | a separate full-screen Compose surface around a WebView; in-memory document, restrictive response CSP, JavaScript and storage disabled, resource requests and automatic navigation refused; deliberate main-frame HTTP(S) link taps open Custom Tabs | a native full-screen viewer with equivalent offline controls and an explicit web-link handoff (not built) |
| Web links | HTTP(S) in conversation markdown and deliberate main-frame links in artefacts open the preferred browser's Custom Tabs, with a system URL-handler fallback; `mailto` in markdown uses the system handler | a native browser surface for HTTP(S) and the system mail handler (not built) |
| App lock | the platform's `BiometricPrompt` (Android 11+: a weak or strong biometric or the screen lock; 10: the same, `setDeviceCredentialAllowed`), the screen-lock page on Android 9; `setRecentsScreenshotEnabled(false)` on Android 13+ | LocalAuthentication, `deviceOwnerAuthentication` (Face ID, Touch ID or the passcode); the app's snapshot blurred while in the background |
| Push (not built) | FCM, one notification channel per agent | FCM through APNs, the conversation as thread identifier |

## Not built yet

Push notifications (the bridge encrypts a preview for the device; the app decrypts it and fetches the message over the tailnet; two actions only, Reply and Mark read), sharing (ADR 0043), landscape, the notification card of the design.

## To verify

- **Two hosts with the same sandbox and agent names**: the second host's Home is left out (above). Keying the cache by host and id, which needs a cache migration, would show both; not built.

- **Archived conversations**: check Archive / Unarchive from each action surface, archived search, active unread and recency filtering, offline feedback, and restoration across another paired device, reconnect and cache clearing. Include Home, new cron output and a running group; Android device coverage is still needed.
- **Static HTML artefacts and web links on Android**: verify deliberate HTTP(S) link taps open a Custom Tab, while forms, redirects, subframes and navigation without a user gesture stay blocked in the private page; check links in conversation markdown, browser fallback and missing-handler notices too. Repeat the offline resource probes against the final build after the browser-link change. Embedded data fonts, app-data access, Back, Share, loading errors, unavailable WebView and reopening still need device coverage. Code settings alone do not establish these restrictions on each WebView version.
- **Transfer to a new phone** excludes the app's data: the rules say so, but no transfer was run (Android).
- **iOS** equivalents in the table above: none is built.
- **The app lock on Android 9 and 10**: its older calls are written as the platform documents them, but run on Android 11 and later only. Before Android 13 the recent apps may still show a picture of the app.
