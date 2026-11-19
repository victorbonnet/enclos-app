---
status: proposed
---

# Outgoing files live only in draft memory

The accepted backend contract (enclos ADR 0076, message.begin/message.part) bounds four files to 10,000,000 bytes each and identifies retries by the original begin ID.

Keep prepared bytes in ViewModel draft memory, without persisted URI grants or an upload queue. Reencode images as bounded JPEG pixels to remove metadata and convert HEIC; documents keep their declared type and bytes. Camera output has a narrow private cache path and is deleted after cancellation/preparation or draft destruction.

Only an exact stored message ID or acknowledgement settles the payload. Failure or a bounded receipt deadline restores explicit retry, never background resend. An uncertain connection closes before retry. Restart discards drafts; reconnect can settle an unchanged retry from canonical history. Durable drafts and resumable uploads are outside this proposal.
