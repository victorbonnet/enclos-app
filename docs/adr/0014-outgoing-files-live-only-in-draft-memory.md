---
status: proposed
---

# Outgoing files live only in draft memory

The accepted backend contract (enclos ADR 0076, `message.begin`/`message.part`) bounds four files to 10,000,000 bytes each and identifies a retry by the original begin id. The #298 contract adds `incoming_images`, dependent on `incoming_files`, and a refusal after the receipt kept as a `status` message in the original conversation's history, with no new structured delivery state.

Prepared bytes live in the ViewModel's memory only, without persisted URI grants or an upload queue, within one budget of 80,000,000 bytes for the whole app, reserved as they are read, released once per file and never evicted. Each host and conversation has one aggregate: a newer draft, and at most one send (id, text, files) in a sealed phase (preparing, sending, awaiting its receipt, refused, unknown). An unknown send keeps its bytes, unchanged, for an explicit retry; a refused one keeps only its id, words and file names. Its jobs run in the ViewModel's scope, so rotation keeps them, and cancelling a job, not an ownership token, stops it. Camera and Photos images are written again to drop their metadata; Files keep their bytes.

Only the receipt of the original id settles a send: history has fresh ids and no origin id. A refusal the bridge names is final; a lost connection, an uncertain error or no receipt in time leaves delivery unknown, for that send only, without closing the shared connection, and an explicit Retry sends the same id from byte zero (history may already hold it). After the receipt the app keeps names only, to tell a late refusal in its conversation; what survives a restart is the bridge's status line in history. Restart discards drafts and unsent originals. Durable drafts and resumable uploads are outside this proposal.
