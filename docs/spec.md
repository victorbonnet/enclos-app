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
2. The person scans the code, types or pastes a link, or taps it wherever it is: the app opens on any `enclos://pair` link. A chat or a note opens only https links, so `enclos app pair` also prints `https://<bridge>/pair#…`, a page of the bridge whose "Open in enclos" opens the `enclos://pair` link (enclos `docs/protocol.md`, Pairing); pasted in the app, that https link reads as the other, but only when its own address is the bridge address it carries (as the bridge prints it): one that names another address is refused, since the person checks the address at the start of the link. The app reads the host's name, its bridge's address and the link's expiry, and shows them with a countdown. The address is the bridge's host name and port only: a link whose `url` holds a path, a query or no host name is refused, so what is shown is where the app connects. A link that is not a pairing link is refused with the app's own reason, never the link's text. A tapped link pairs only once the person confirms ("Pair with <host>?", Pair or Not now), since any page or message can hold one; a link opened while a pairing goes on is ignored, and so is one opened before (the system hands it again when it restarts the app, after an update): such a link can still be typed.
3. The app makes the device's signing key in the platform's key store (StrongBox where the phone has it) and a Noise key pair, wrapped by the key store.
4. It connects, sends a pairing request (its model name, its public key, a proof from the link's code), and shows the terminal's question: `<model> (tailscale login: <your login>) wants to pair. Accept? [y/N]`. The person answers in the terminal; the app shows Paired or why not (refused, or the connection ended). A pairing that ends unpaired deletes the signing key made for it.

The pairing screen has three steps, always in this order: code read, device key made, accept on the host. Its copy states what happens, never how safe it is.

**A link naming a host already paired.** A pairing link gives the host's name itself, so a name proves nothing; the bridge's key, which the Noise handshake checks, tells one bridge from another. When the link names a host this phone is already paired with:

- **Same bridge key**: the app pairs again, as for a new host. Once the bridge accepts, the new pairing takes the old one's place and the old pairing's device key is deleted from the key store; what the host sent before is forgotten, as for any pairing again ([On the phone](#8-on-the-phone)).
- **Another bridge key**: the app makes no key and does not connect. It shows a card, "Replace <host>?", with the paired bridge's address and the link's. **Keep <host>** leaves the pairing as it was. **Replace** pairs with the link's bridge; once it accepts, the old pairing, its device key and what the app showed of that host are gone.

The replaced host still lists this phone as a paired device until it is removed on that host.

**A pairing that is not accepted** (refused, the link expired, the connection ended, the key could not be made) deletes the device key it made. The keys are made off the main thread, and a failure to make them ends the pairing with its reason.

## 3. Connection

- The app opens a connection to each paired host when it comes to the foreground, and keeps it while the phone lets it.
- A connection that ends while the app is in the foreground is opened again after 2 s, then 4, 8, 16, 32, then every 60 s, until it is authenticated. Reconnect in Settings tries at once.
- On each connection the app asks for the host's agents and syncs the messages after the newest one it [keeps](#8-on-the-phone) from that host (every message, the first time); the bridge sends each message with the time it was stored.
- In the background the app does not connect. Until push notifications are built, what arrives meanwhile is shown at the next sync.
- When the bridge reports an error (an agent it could not reach), the conversation and Settings show it, in the bridge's words.

A message from the bridge that the app cannot read (a field missing, as from another version of `enclos app serve`) ends that host's connection, with that reason on screen. The app does not open it again on its own, since the next connection would get the same message: Reconnect does, or the app's next return to the foreground.

## 4. Screens

**The first screen** has two views, the conversations (the [inbox](#inbox)) and the [agents](#agents). Its top line holds the switch between them (a pill with the conversations' bubble and the agents' glyph, the one shown on `surface`), then, on the conversations, unread only, and Settings' gear. Swiping left on the main content opens the agents; swiping right returns to the conversations. The content follows the finger and settles on a view when released; there is no wrapping at either end. The switch shows the view a swipe would settle on and can still be tapped; the actions on the top line and the search field's label change once the swipe has settled. Vertical swipes scroll the current list. Over the end of the view, on a fade to the ground, sit the search for that view (a pill on `surface`: "Search conversations", "Search agents") and the compose button, which opens a [new conversation](#new-conversation); both show once a host is paired, and stay in place while the views slide, with the same search text across both. Back from the agents view leads to the conversations. Pairing a host lands on the agents.

**Full screens** arrive over it from the right: a conversation, an [agent's page](#an-agents-page), a [sandbox's page](#agents), a new conversation, [Settings](#settings), pairing and a policy request. Back from each leads to the first screen, except an agent's page opened from its sandbox's page, which goes back to that page. The app is in portrait only, for now, the QR scanner included.

### Inbox

By agent, always: one row per agent of every host, the agent with the newest conversation first, and under it, folded, only its conversations with something unread, so a new message is one tap away without the list growing with every conversation.

**An agent's row** shows its avatar (its initial inside the logo's corners, in `accent` while its host is connected, pulsing while it works), its name, the time of its newest message in any conversation (today: `07:12`, this week: the weekday, else `3 Nov`), and its [Home](#home)'s last message (words without markdown on two lines at most, the person's own prefixed "You:") with Home's unread count. While the agent works, the wave and what it does take the message's place, with where when that is not Home (`· in Weather in Lyon`, `· in 2 conversations`). Tapping the row opens Home.

**What is folded** is said at the end of the agent's group, on a line of its own starting where its conversations do, in `accent`, with a chevron pointing down: `17 conversations`, or `15 more conversations` when some show already. Tapping it unfolds them all, newest first, and the line, now at the end of the list, says `Show less` with the chevron pointing up. An agent stays unfolded while the app runs; with nothing folded away, there is no line.

**A long press on an agent's row** (with a haptic tick) opens its menu under its name: **New conversation** with it (not while its host no longer lists it), **All conversations** or **Fewer conversations** (as the line above, when some are folded away), and, under a line, **About** the agent, its page. Its routines will come here later.

**Sections fold.** The Groups and Agents headings each fold their section away, or back, when tapped: the name, small and faint with no line under it, and a chevron at its end, pointing down while open and right while folded, turning between the two. A folded section shows its unread count by its name. What is folded stays so across starts of the app; a search shows all it finds, with the headings unfoldable meanwhile.

**A conversation under its agent** starts where the agent's name does, without an avatar: its [name](#names) ("No name yet" while it has none), the time of its last message, an "on its own" header when the agent sent it unprompted, the last message's words (or, while the agent works on it, the wave and what it does), and the unread count. A conversation with no message is not listed (one left empty, here or on an earlier version of the app). With no conversation yet, it points to the compose button. With no host paired, the inbox says how to pair one.

**Search and unread only.** The search at the bottom finds conversations by their name, their agent's address, and what was said in them (the agent's messages as their words, without markdown), whatever the case and the accents; with several words, each must be found, in the name or in any of its messages, not necessarily the same one. A conversation found by a message shows that message, the newest that holds the most words, in place of the last, with the words in `accent` (Home's, on its agent's row). Search reads every message, so it runs off the main thread, each message's words worked out once while the app runs. An action on the top line, the inbox's bubble with a square at its corner, shows only the conversations with something unread, and everything again when tapped again: drawn in outline and `text-muted` while off, filled and in `accent` while on. There is no filter by agent, since the inbox is by agent already. While searching or showing unread only, only agents with something found show, and everything found shows under them, folded or not, with nothing to unfold. Nothing found says so.

### Home

Each agent has one Home conversation, which its host's bridge makes and keeps (enclos ADR 0044, `docs/protocol.md`, Home): the runtime's home channel. What belongs to no conversation lands there: a cron job that has no conversation of its own, a message the agent sends with no other place to go, the runtime's notices about itself. It is named Home, opened by its agent's row in the inbox, shown even when empty ("Cron jobs and the agent's notices come here."), and the person may talk to the agent in it too. A cron job started in a conversation still answers in that conversation. Hermes's `/sethome` is answered by its plugin, not run: there is nothing to set.

**Status lines.** The runtime's notices about itself (Hermes: the gateway online, shutting down, restarting) are not the agent speaking: they come as `status` and show as a quiet centred line, small and faint, with their time, never a bubble. They never count as unread, and the inbox previews the last other message when there is one. The plugin, in the sandbox, chooses a message's kind, so a status line is the sandbox's word, never the app's or the bridge's: it starts with the runtime's name (`hermes: gateway online · 14:02`), is cut to one line, and drops the characters that would reorder or hide part of it. One that carries files shows as a message, so its files show. Status lines coming only from the runtime's gateway, never from a turn, would have to be enforced on the bridge; the app does not.

### Sandbox policy requests

**Above Groups in the inbox**, a Sandbox requests section shows pending, applying and failed policy requests from all paired hosts, newest first, even when there is no conversation. Its heading folds the section away and back, with the same chevron as Groups and Agents and the request count beside it while folded. The folded state stays across starts; a search temporarily unfolds matching requests and disables folding, as for the other sections. Search matches their target, sandbox and host; the unread-only filter does not hide them. Each request is a compact two-line list row, without a card or inline decision controls: the action on one line (a file target uses its final name, such as `Write to enclos`), then its agent when known, sandbox and host on one metadata line. The requesting agent's usual round, coloured avatar leads the row at 32 dp, matching that same agent elsewhere, and their name appears first in its metadata. A proxy-only refusal has no known agent: it uses the Enclos mark in grey (the theme's faint text colour, so it follows dark mode), in the same 32 dp space and says `Agent unknown`, without attributing it to an agent. Both lines truncate with an ellipsis. Saving, failed and unavailable requests add a short status to the metadata. The whole row opens its detail page. Its spacing, sans-serif metadata, title-line chevron and absence of dividers match the inbox's agent rows. A proxy refusal names the sandbox, since its host cannot identify an agent. The full target, policy scope, reasons and **Approve** / **Deny** controls appear only on the detail page.

Tapping the row opens its detail page: attempted action, the selectable scope approval writes, who requested it, the exact change the host described, private-address access if requested, and whether all running sandbox work ends for a restart. It links to the originating conversation when one is known, and shows the decision controls at the bottom. A file change requiring a restart says **Approve & restart**; unsupported scopes explain why and keep approval disabled. It says approval persists for every agent of that sandbox and updates their policy knowledge for later work. An approval does not automatically retry the attempted action. Network scopes are one host:port; write scopes cover the named path and everything beneath it. The row title and the detail page lead with the host's `scope`, the normalized destination or the writable path with its symlinks resolved, never with the agent's own text over it; when the host resolved a write path through a link to another place, the page also shows the requested path and says approval grants the folder it leads to, not the link. A host that sends no `scope` (before enclos's change) shows the requested target. The agent's action label is shown without hidden characters, and a target's hidden characters are written out (⟨U+202E⟩), never applied. A request the bridge sends without a field the app needs (its id, sandbox, state, time or access) is dropped, never shown and never fatal. Sensitive read denials remain unavailable for approval. Host behaviour is [enclos ADR 0046](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0046-the-hosts-person-approves-sandbox-policy-scopes-from-the-phone.md), proposed; this is a sandbox policy request, not a runtime tool approval.

Only the host's own person on a still-paired device may decide. Approve and Deny each ask for the phone's biometric or screen lock first (the [app lock](#app-lock)'s prompt: a strong biometric or the screen lock), every time and whatever the app-lock setting, so a phone left unlocked in someone else's hands cannot widen an agent's access; cancelled or failed, nothing is sent and the page says what the system said. A phone without a screen lock cannot decide, and the page says to set one. Buttons need a connected host and a completed fresh sync, remain disabled during a decision, and show Saving decision. Nothing is queued offline or optimistically marked approved. A changed policy refreshes the preview; the person must review it again. A request decided on another device shows that result. Completed approvals and denials leave the inbox but stay on an open detail page; failed or interrupted applications stay visible with a reason to review the host's policy. They are not retried automatically.

Requests are kept in the app's private preferences, covered by its existing no-backup rules. On authentication the host's cached requests are replaced by its fresh sync; forgetting that host or clearing the cache removes them. They show offline with both controls disabled. Android is built; iOS follows the same behaviour when implemented.

### Groups

A group is one conversation between the person and two to six agents of **one sandbox**, which the host's bridge keeps and whose turns it runs (enclos ADR 0045, proposed; `docs/protocol.md`, Groups): the app shows it and sends the person's messages.

- **Starting one**: the compose button's agent chip lists **New group** when a sandbox has at least two agents. Its agents are ticked (once one is, only the agents of its sandbox can be), with a name or none (their names then); the first message starts the group (`group.new`, then the message). A group the bridge refuses (an `error` naming the `group.new`: a member it no longer knows, an older bridge) leaves the phone at once, and where it was started, or opened, says `The group was not started:` and the bridge's words.
- **In the conversations view**: a **Groups** section above the agents, each group with its members' avatars overlapping, its name, its last message with who said it, how long ago or the working wave, and its unread count. Groups are not in an agent's tree.
- **In the group**: the members' avatars and handles in the bar; each agent message with its speaker's avatar and name above it. Typing `@` lists the members matching what follows, with their avatars, and **@all** last ("everyone answers"); picking one puts `@handle ` in the field. `@handle` addresses that member, several those members, `@all` every member, each of whom must answer; a message naming no one invites them all, and they may pass.
- **Where it is**: a line over the field, from the bridge's `group.state`: `@coder is answering · round 1 of 3` with the wave and **Stop** (`group.stop`, which cancels what is queued and the running turn; what was said stays), then Settled, Paused after the rounds or the replies allowed, Stopped, or Failed with why.

### Names

A conversation has a name: its agent's, from what was said in it (Hermes names each session; the plugin passes the model's summary on, not the first message's cut), or the person's. Tapping the name in a conversation's bar renames it; the person's name replaces the agent's, which no longer replaces it, and clearing it lets the agent name it again. The bridge keeps the name and sends it to every device of the person (enclos `docs/protocol.md`, A conversation's name), so a name is saved only while its host is connected: until then the rename dialog says so and cannot save. A name is shown without the characters that change how text reads unseen (bidirectional overrides, zero-width characters: the app's `Visible` rule), whoever gave it.

### Agents

**By host, then sandbox.** Each paired host has a heading, even when it is the only one: its connection (a filled square in `accent` while connected, an outlined one otherwise) and its name, with the chevron of the conversations view's sections, folding the host's sandboxes and agents away or back; what is folded stays so across starts, and a search shows only the hosts with something found, unfolded. A host with no agent yet says the host lists them once connected. Under it, each sandbox's heading (the Enclos mark, its name, its runtime), then its agents. No heading has a line under it.

**A sandbox's page.** Each sandbox heading in the agents view ends with a chevron, and tapping it opens the sandbox's page: its mark (in `accent` while its host is connected), its name, runtime and host; its agents as the agents view shows them, each opening its page (back from which returns to the sandbox's page); its groups, each opening; what it runs on (runtime, host and connection); and a line saying its state, policy and sessions show on the host (`enclos sandbox show <sandbox>`), since the bridge sends nothing more about a sandbox. Read only.

**An agent's row**: its avatar (pulsing while it works), its name, and, while it works, the wave and what it does (the tool it runs, as its label says, else `working`), else when it last sent a message (`active 3 hours ago`, `active yesterday`, its runtime's notices aside), `no message yet` before its first one; a chevron at its end, and tapping it opens [its page](#an-agents-page). An agent works on a conversation from the person's message until its answer, while its runtime says it is writing, and while a tool it started has not ended; the phone knows only what comes over the bridge, so not, for instance, a cron job the runtime runs without saying so.

The search finds agents by their address and runtime. With no host paired, the view says how to pair one. A person sees only what is shared with them; the list never hints that more exists.

### An agent's page

Opened from an agent's row, its menu's About, or a sandbox's page. Its avatar (pulsing while it works), name and address; **Conversations**: its [Home](#home) ("Cron jobs and the agent's notices come here."), which opens, **New conversation** with it, and how many other conversations it has, in the conversations view; **Runs on**: its runtime, sandbox, host and the host's connection, and what its channel lets it do once the bridge has reached its plugin (says when it is working, shows the tools it runs, names conversations, lists its commands, sends files), else that this is known once its host reaches it; **Commands**, when its runtime lists them, the first six with **Show all**. Read only: changing an agent from the app comes later, for the host's own user, which the page says.

### New conversation

The compose button's screen: Close; the agent it is with, a chip at the top (its avatar and name, the agent most recently active first), whose list picks another agent or starts a [group](#groups); the agent's card in the middle (avatar, name, sandbox, runtime and host, and how many commands it has); and the field. The first message starts the conversation (a draft until then, which the bridge learns of with that message, `conversation.new` then the message, so a screen left before sending leaves nothing behind) and opens it. A send that does not go leaves the person on the screen with the text, and the next tap reuses what the last one made: the same draft while the agent is the same (another agent's draft is dropped), the same group while its members and name are the same, so a failed send makes no second group on the host. Sending needs the agent's host connected, as in a conversation. A conversation screen whose conversation the phone does not hold (a draft lost when the system stopped the app, or one forgotten or cleared while open) goes back to the first screen.

### Settings

In sections, each named small, faint and in capitals above a hairline, its rows separated by hairlines. Nothing here acts on a sandbox.

- **Hosts**: each paired host, its connection (who it is connected as, or why not), the bridge's last error, its address, then Reconnect (when it is not connected) and Forget this host on one line; and a last row, Pair a host, with the QR glyph.
- **Security**: the [app lock](#app-lock), a switch beside what it does; the whole row toggles it.
- **Appearance**: the [theme](#7-theme).
- **On this phone**: what is kept (and, when it could not be read, such as after going back to an older build, that it could not), and Clear cache.
- **About**: the app's version and the core's.

- **Forget this host**, after a confirmation: connected, the app asks the bridge to unpair this phone (enclos `docs/protocol.md`, `device.forget`); not connected, the host keeps it listed until `enclos app unpair` there, which the confirmation says; when the connection ended between the confirmation and Forget, the app says so after it. Either way the phone deletes its device key and Noise key for that host, and everything it kept from it: agents, conversations, messages, files (those of messages not read from the phone's copy yet too), how far they were read, whether its heading in the agents view was folded. Pairing again with a host deletes the device key of the pairing it replaces, and at each start the app deletes any device key no paired host uses. The conversations stay on the host. Using it again means pairing again.
- **Clear cache**, after a confirmation: deletes the conversations, messages, agents and files kept on this phone, then syncs everything again from each connected host. Pairings stay, and how far each conversation was read comes back from the bridges.

### Conversation

The conversation's name in the bar (the agent's name while it has none), the agent and its address under it; tapping them renames it. Messages bottom-up, anchored at the newest: the screen follows what arrives unless the person scrolled up to read, and stays anchored when the keyboard opens. A day separator in lowercase (`today`, `yesterday`, `mon 3 nov`) starts each day. Each message has its time (`HH:mm`).

- The person's messages: right, on `button-bg`, shown exactly as typed.
- The agent's: left, on `surface` with a hairline, [markdown](#5-messages) drawn, selectable to copy. One the agent sent on its own (`kind: notify`, such as a cron job's) has an "on its own" header; the protocol does not say which job.
- **The agent at work**: three squares rising in turn, a 900 ms wave, from the moment the person's message is sent until the agent answers, and whenever the runtime says it is typing. It stops when the connection ends, when the bridge refuses the message (an `error` naming it, such as `agent_unavailable`), and after 10 minutes with no word from the agent.
- **What it does** (runtimes with the `tools` capability): above the working wave, "Working · n steps" and each tool the agent runs, one a line: its mark (a square pulsing in `accent` while it runs, a check once done, a cross if it failed or a policy refused it), its name in mono, what it does (`ls -la`, `weather Lyon`) and how long it took. When the answer arrives, its steps fold into one line above it ("3 steps", with a chevron), which opens them. When the turn ends with no answer (the runtime stops typing), a step still running is marked done and the steps are no longer shown; an answer that follows still takes them, and the person's next message starts a turn with none. A tool's name and what it does show a hidden character as `⟨U+XXXX⟩` (the app's `Visible` rule). Steps come live from the bridge, which does not keep them: the phone keeps them with the answer they led to, so they stay after the app restarts, on that phone only (another device, or this one after missing them, shows the answer without its steps).
- Under the messages, why the host is not connected, or what the bridge said, when there is something to say.
- **Sending needs the host connected.** While it is not, the field still takes text, the send button stays off, and a line above the field says so (`Not connected to mac: send once it's back.`); nothing is queued to go later. What was written stays, for each conversation, while the app runs (leaving and coming back keeps it); Forget this host and Clear cache drop it with the conversations. Sending takes it out of the field, but it is sent only once the bridge shows it back as stored: should the connection end, or the bridge refuse it (`error` naming it), first, the text comes back to the field, before anything written since, and the line above says `Not sent:` and why. If the bridge had it after all, the next sync shows it and takes it out of the field again, unless the person changed it. A conversation started on the phone stays a draft until the bridge sends it back, so its start goes again with the next try.
- **The agent's commands** (runtimes with the `commands` capability; enclos `docs/protocol.md`, An agent's commands): typing `/` opens, over the field, the commands the agent's runtime lists, in its order, on `surface`; each `/name` in mono, what follows it faint (`[name]`, `<prompt>`), and what it does on one line. Typing more keeps the commands whose name starts with it, then those that have it elsewhere, whatever the case, with the typed part in `accent`; about four and a half rows show, the rest scroll. Picking one puts `/name ` in the field, the cursor after it, and the list closes, as it does once a space follows the name. A command is sent like any message, and the runtime answers it; the app runs nothing. The bridge sends the list after the agents' and when the runtime lists it anew; it is kept while the host is connected, and dropped by Forget this host and Clear cache. Each name shows once.

Opening a conversation marks it read, as does a message arriving while it is open: unread counts are the agent's messages after the last one read. The bridge keeps how far each conversation was read and sends it with the conversation, at each sync and when it moves (enclos `docs/protocol.md`, How far a conversation was read): a message read on one device is not unread on another, nor after pairing again. The runtime cannot tell, as it does not see what the person reads. A conversation read further while offline tells the bridge at the next sync. A position past the conversation's newest message is taken once its messages came, and never past the newest of them, as the bridge itself never goes further.

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
| table | columns as wide as their widest cell (up to 240), header bold, hairlines between rows, scrolling sideways; at most 12 columns and 50 rows, the header included, with a line above saying how much is shown when there are more |
| `---` | a hairline |
| a single newline | a line break (CommonMark would join the lines; chat is written line by line) |

Nothing in a message is fetched or run:

- an image (`![alt](url)`) shows as its alt text and `↗`, a link to it; the app never loads it;
- HTML shows as its literal text;
- a link opens, in the browser, only when it is `http` or `https` with a host and no user-info (`https://bank.example@evil.example` reads as the bank), or `mailto`; anything else is plain text;
- a link's text drops hidden characters (bidirectional overrides, zero-width characters: the app's `Visible` rule), so it cannot read as another address;
- a `mailto` link opens the mail app with the recipient, subject and body the agent wrote; the person still decides to send it.

The person's own messages are not markdown: they show as typed. Previews (inbox, and later notifications) are the words without the marks.

**Files.** An agent's message may carry files: an image it made, a chart, a document (enclos `docs/protocol.md`, Files). The app fetches each from the bridge over the conversation's connection, in parts, when the message is first shown: on an unmetered network (Wi-Fi), any file up to the bridge's 25 MiB; on a metered one (mobile data, a hotspot, as the phone's system counts it), a file above 10 MB only when tapped, so an agent cannot make the phone pull it over mobile data unasked, and by itself once the phone is on an unmetered network again. With no network, or before the system says, the network counts as metered, and keeps it in its cache (never backed up), in a folder per host and file; a file whose id is not of the bridge's form (letters, digits and `-`, as a UUID) is not kept. A file's name shows without the characters that change how text reads unseen (the app's `Visible` rule): `report` U+202E `fdp.html` does not read as a PDF. An image shows in the bubble, above the words, decoded at the screen's size, in a box of its own shape held between 1:3 and 3:1, the image fitted inside; tapped, it opens full screen, edge to edge, on near black in both themes (as photo viewers do: the image is the only colour), with Back, its name and Share over it on a shade, which a tap on the image hides or shows. Pinch zooms it, up to 6×, around the fingers; while zoomed it moves with a finger, its edges held at the screen's; a double-tap zooms 2.5× on that point, or back to the whole image. It is decoded at up to 4,096 px on its longest side, to stay sharp when zoomed; an image is never decoded above 64 MB in memory, a smaller sample being taken (Android draws no bitmap above 100 MB). An image Android cannot decode (an SVG chart, a damaged file) shows as another file does. Another file shows as a line with its name and size; tapped, it is shared. Share hands that one file to another app through the system's share sheet. A file that did not come says why and is fetched again when tapped: a fetch the connection broke, a part out of order, or a file the phone could not write (storage full); it goes on from where it was. A message with files and no words previews as "Image", or the file's name.

The runtime's plugin tells the agent what the app draws, so that it formats when it helps (Hermes: the plugin's `platform_hint`, in enclos).

## 6. Motion

Short and functional, never decorative: something moves to show where it came from or went. Durations: 150 ms for a change in place, 250 ms for a screen or a message arriving, 150 ms for what leaves. Arriving decelerates, leaving accelerates.

- A full screen arrives over the tabs: it slides in from the right edge, opaque, while the tabs drift a tenth of the width to the left under it. Going back is the same move reversed: the screen slides off to the right, uncovering the tabs drifting back. Both take 250 ms at an even pace, so a finger can drag the move: with the back gesture the screen follows the finger and goes or returns where it is let go.
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
- **Conversations are kept on the phone**: each host's agents, the conversations and their names, every message with its files' descriptions, and the steps of each answer. They show when the app opens, before any connection, and while a host is unreachable; a sync then brings only what is newer. The copy is read off the main thread, in two passes: first what the first screens show (every conversation with its last message, and the last 40 messages of the 10 most recent), with the launch screen kept until then (at most a second) and the connections opened after it; then every other message, merged in. Until that second pass ends, search and unread counts see only the first pass's messages. The bridge stays the source: what the phone keeps is what the bridge sent, and a conversation started on the phone is kept once the bridge confirms it. Pairing again with a host forgets what it sent before. A message that cannot be written (a full disk) leaves no gap: no later message of that host is kept until the app starts again, so the next sync, which starts after the newest message kept, brings it back. A kept row the app can no longer read is skipped, not fatal. A conversation is known by its id: one that a host's bridge sends with the id of another host's conversation is ignored. The copy is in the app's private storage, readable by the app only, and not encrypted by the app itself: it is as safe at rest as the phone's own storage encryption makes it. Files stay in the cache, which the system may clear: they are fetched again when shown.
- **Nothing leaves the phone** ([ADR 0003](adr/0003-nothing-of-the-app-leaves-the-phone.md)): no backup, no transfer to a new phone. A new phone pairs again.

### App lock

For a phone lost or left unlocked: with the app lock on, the app asks the person to prove it is them, with a strong biometric the phone has (fingerprint, or a face unlock the phone rates as strong; a weak face unlock, which a photo may fool, does not count) or else the phone's own screen lock (PIN, pattern, password), when it opens and when it comes back after a minute or more in the background. Until then the app shows only a lock screen (the mark, "Enclos is locked", and Unlock): nothing else of the app is drawn, so no screen, dialog, sheet or menu left open is there to see or reach, by touch, keyboard or accessibility services, and no message is marked read. Once unlocked, the app is back on the screen and the place the person left. Back leaves the app. The prompt shows on its own as the app opens and each time it comes back locked; after a cancel, Unlock asks again, and the lock screen says what the system said (cancelled, too many attempts). While the lock is on, the app's window is secure: screenshots, screen recordings and casts show nothing of it, nor do the recent apps.

It is off until the person turns it on in Settings, Security, which needs a screen lock on the phone (else Settings says to set one) and asks to unlock once, so a lock that cannot be opened is never set. Turning it off asks to unlock too, so someone who picks the phone up in the minute after it was put down cannot turn it off. A phone whose screen lock is removed has nothing left to unlock the app with: the lock then turns itself off as the app opens or comes back, rather than lock the person out, and Settings says so. It hides the app; it does not encrypt what the phone keeps, nor tie the keys to it: the conversations and keys are as safe at rest as the phone's own storage encryption and key store make them, and the connections stay open behind the lock, so messages keep arriving.

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
| Files | cache `attachments/<id>/<name>`; shared through a `FileProvider` and `ACTION_SEND` | Caches directory; shared through `ShareLink` / `UIActivityViewController` |
| App lock | the platform's `BiometricPrompt` (Android 11+: `BIOMETRIC_STRONG` or `DEVICE_CREDENTIAL`; 10: `setDeviceCredentialAllowed`), the screen-lock page on Android 9; `FLAG_SECURE` on the window while the lock is on, and `setRecentsScreenshotEnabled(false)` on Android 13+ | LocalAuthentication, `deviceOwnerAuthentication` (Face ID, Touch ID or the passcode); the app's snapshot blurred while in the background |
| Push (not built) | FCM, one notification channel per agent | FCM through APNs, the conversation as thread identifier |

## Not built yet

Push notifications (the bridge encrypts a preview for the device; the app decrypts it and fetches the message over the tailnet; two actions only, Reply and Mark read), sharing (ADR 0043), landscape, the notification card of the design.

## To verify

- **Transfer to a new phone** excludes the app's data: the rules say so, but no transfer was run (Android).
- **A metered network through Tailscale**: Android counts the VPN as metered when the network under it is, so a large file waits on mobile data and comes by itself on Wi-Fi; the rule is tested, the network state on a phone was not (Android).
- **iOS** equivalents in the table above: none is built.
- **The app lock on Android 9 and 10**: its older calls are written as the platform documents them, but run on Android 11 and later only. Before Android 13, `FLAG_SECURE` alone keeps the app out of the recent apps' picture: not run there yet.
- **The app lock on a phone**: that nothing of the app is reachable while locked follows from the lock screen being all that is composed, and its moves are tested on the JVM (`AppLockTest`); no instrumented test runs it on a phone yet.
