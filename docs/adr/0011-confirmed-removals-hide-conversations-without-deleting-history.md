---
status: proposed
---

# Confirmed removals hide conversations without deleting history

Removed agents leave permanent Home rows in the inbox, but an absent app channel does not prove that an agent was removed: a missing token or `agent rm --keep-data` can leave its identity and data intact. [Enclos ADR 0067](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0067-the-hosts-person-can-forget-a-removed-agent.md) reserves Forget for removal the host verifies.

Decision (proposed): hide a direct conversation, including Home, only when its agent is absent from its host's inventory and the host confirms removal. Otherwise preserve inbox access to saved history. Refresh that confirmation on connection and live inventory changes; use a returned agent's inventory entry to restore visibility and clear any earlier page-close flag. Groups stay independent. Hiding changes no stored history or read position.

Extend 0067's proposed app entry point: the host details page offers the existing confirmed Forget action in one Removed agents section, with full sandbox/agent labels. An agent page already open keeps the same action. This keeps permanent cleanup reachable after an inbox row is hidden; the host's eligibility checks, explicit confirmation and deletion records stay unchanged.

Reason: inventory absence alone can hide valid history with no recovery action. Keeping every removed Home visible until Forget preserves access but leaves the inbox crowded. Confirmed removal separates those cases, while host details keeps the deliberate deletion separate from the inbox's presentation. Without a fresh confirmation, including after restart or with an older bridge, preserve history access rather than infer removal.
