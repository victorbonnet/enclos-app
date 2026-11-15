---
status: proposed
---

# A task card sits in its conversation without being a message

When an agent hands work to another through its runtime, the person who asked for it watches the conversation they asked in, so that is where they need to see the work go on and end. A task is not a message: the bridge keeps it apart from history, with no `seq`, changes it in place as its state moves, and drops it a few days after it ends ([enclos ADR 0067](https://github.com/victorbonnet/enclos/blob/tasks/1-protocol/docs/adr/0067-tasks-and-agent-activity-are-reported-not-controlled.md), proposed).

Decision (proposed): a task the bridge reports with a `conversation` is drawn in that conversation as a card, placed among the messages by the time the phone first saw it (the `since` it first came with), and updated in place until it ends with its summary. It is not a message: it is never unread, never previews in the inbox, is not found by search, and leaves the conversation when the bridge stops keeping it, as at a sync that no longer sends it.

Reason: the person sees delegated work where they asked for it without the app inventing history the bridge does not keep. Making each state change a message would flood the conversation, mark it unread at every step, and outlive the bridge's own retention.

## Considered options

- **A message per state change** (the bridge or the app writing one): rejected for the noise, the unread counts and the history the bridge would have to keep.
- **Only on the assignee's page**: rejected: the person who asked would not see the work from where they asked for it.
