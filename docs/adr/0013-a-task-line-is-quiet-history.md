---
status: proposed
---

# A task line is quiet history

A runtime announces changes of a task to the conversation that asked for it (Hermes's kanban pings: done, blocked, sent to triage). Under [enclos ADR 0071](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0071-a-runtimes-task-events-reach-the-app-as-task-lines.md) (proposed) these come as messages of their own kind, `task_event`, with the task and what happened, and the bridge keeps them in history and pushes none. Before, each showed as a message the agent sent on its own and counted as unread; on a board shared by several agents they filled a Home. Some say a task needs a decision, so leaving them out of the count can hide one.

Decision (proposed): the app draws a task event as a task line, a quiet line as a status line is, never a bubble, with **Open task** only when the phone keeps the task. It is a message of the history, kept and synced, but never unread, never previewed in the inbox while another message is there, and offers no suggested reply. A `task_event` whose details the app cannot read is its text as a `notify`.

Reason: the person sees that a task moved, and which one, without it reading as the agent talking or counting like it. What needs the person reaches them by its own way: the agent's answer in the turn its runtime wakes it for is its message and counts; a held task is a card in Needs you. The task card ([0010](0010-a-task-card-sits-in-its-conversation-without-being-a-message.md)) stays the task's place in the conversation; a task line is the runtime's own message, which the bridge already kept, so the app invents no history.

## Considered options

- **Unread, as a `notify` was**: rejected for the noise ADR 0071 removes, and the bridge already sends no push for it.
- **Unread only for some events** (triage, blocked, gave up): rejected for now: which event needs the person is the runtime's and the agent's call, which the held task and the agent's answer carry.
