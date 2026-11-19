---
status: proposed
---

# Outgoing files live only in draft memory

The accepted backend contract (enclos ADR 0076, message.begin/message.part) bounds four files to 10,000,000 bytes each and identifies retries by the original begin ID.

Keep prepared bytes in ViewModel draft memory, without persisted URI grants or an upload queue. Reencode images as bounded JPEG pixels to remove metadata and convert HEIC; documents keep their declared type and bytes. Preparation and its status share the ViewModel lifetime so rotation does not discard the selected results. Camera output has a narrow private cache path and is deleted after cancellation/preparation or draft destruction. Process restart clears that directory; a recovered result without its file is refused and asks the person to take the photo again.

Only an acknowledgement of the original begin ID settles the payload. Canonical history uses fresh envelope IDs without an origin ID and cannot safely settle an upload. Failure or a bounded receipt deadline leaves delivery unknown and retains the immutable original text, files and begin ID separately from the newer composer. Explicit Retry original message starts that exact payload from byte zero; check history first because it may already be stored. New text drafts remain usable; another file message requires retrying or discarding the retained original. An uncertain connection closes before retry. Restart discards drafts and retries. Durable drafts and resumable uploads are outside this proposal.
