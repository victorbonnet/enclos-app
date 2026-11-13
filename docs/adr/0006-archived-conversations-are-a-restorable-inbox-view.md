---
status: proposed
---

# Archived conversations are a restorable inbox view

People need to put old conversations aside while keeping them available to search and resume. Archiving only on the phone would disagree with another paired device, while calling Hermes's own archive would tie the app to one runtime.

Decision (proposed): the bridge owns a conversation's archived state ([enclos ADR 0049](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0049-conversation-archives-belong-to-the-bridge.md)). The Inbox opens on active conversations, with an Archived view using the same search. Archive and Unarchive require a connected host and take effect when its metadata arrives; nothing is queued offline or optimistically hidden. Archived conversations retain their history and read position but contribute no preview, recency or unread count to the active Inbox. Unarchive restores the same conversation, and new messages leave it archived until that explicit action.

Direct and group conversations follow the same behaviour. Home is the runtime's delivery route and cannot be archived, so the agent's notices and output without another destination stay in the active Inbox. The bridge's Home flag identifies it; an ordinary conversation named Home remains archivable. A Home archived by an older version keeps its id, history and delivery route, continues receiving output and remains available in Archived conversations and from Agents. Unarchive restores it to the active Inbox; opening it alone leaves that legacy state unchanged. Routines can still deliver to active Home, archived direct conversations and legacy archived Home. Archiving changes visibility only: it neither resets context nor stops the agent or a group's running discussion. Stopping work and starting a new conversation remain separate actions. Android keeps the flag and the bridge's nonnegative metadata revision in its conversation cache; missing revisions begin at 0. Name, read-position and archive changes advance the revision, and the app ignores lower revisions from broadcasts or sync responses so older metadata cannot replace a newer state. iOS follows the same contract when built.

Reason: one durable visibility state across devices keeps the app's behaviour independent of the runtime. A future OpenClaw channel uses the same device protocol and needs no archive-specific plugin method.
