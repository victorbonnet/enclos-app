---
status: proposed
---

# Runtime approvals use native buttons with explicit consent scopes

A runtime command guard may ask for consent through a chat message containing `/approve` instructions. An ordinary question picker would add inappropriate Other/Skip controls, and an old approval button could otherwise target a later request.

Decision (proposed): Android and iOS render explicitly typed runtime approvals inside their conversation bubbles, using native controls in the app's shared design tokens. Show the complete display command, wrapped, with its hidden characters written out and characters outside ASCII flagged, its reason and only the offered Allow once / Allow for session / Always allow / Deny buttons. There is no default action; a Deny-only request stays Deny-only. Name the runtime's patterns a session or always grant covers beside the decision, and ask for confirmation before Always allow, saying where its grant is revoked. Disable submission while offline, pending, uncertain or terminal; confirm only the exact correlated action receipt and never claim the command ran. A native decision made elsewhere closes the card without inventing an undisclosed action. Keep host-scoped cached state and current revisions through restart/reconnect, and never automatically resend any decision.

Reason: this preserves the runtime's real gate and scope semantics while keeping command review and consent in the app. The bridge's exact-request binding and host-person authority for structured controls are defined in [enclos ADR 0053](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0053-runtime-approvals-use-exact-native-requests-and-explicit-scopes.md), proposed; sandbox policy requests remain separate. A dedicated message kind lets older apps display readable typed-command fallback, whose native authority rules stay unchanged. Android is built first; iOS must implement the same behaviour with SwiftUI buttons and selectable, wrapped command text written out the same way. Its implementation and device coverage remain pending in the spec.
