---
status: proposed
---

# Downloads are written through MediaStore, never overwriting

## Context

Agents send files such as build APKs that a person wants in the phone's shared Downloads folder so the system installer or a file manager can open them. The app's `minSdk` is 28 (Android 9), while `MediaStore.Downloads` was added in API 29 (Android 10). Writing to Downloads on API 28 would require `WRITE_EXTERNAL_STORAGE`, a broad runtime permission for one OS version.

## Decision

Every Download action in the app goes through one write path (`Downloads.kt`) backed by `MediaStore.Downloads` on API 29 and later, with no storage permission in the manifest. On API 28, Download is not offered (absent, not disabled); Share and Open stay unchanged. Each write inserts a row with `IS_PENDING = 1`, streams the cached bytes unchanged, and clears `IS_PENDING = 0` only on completion; any failure or cancellation deletes the pending row. The file is written under the sanitised ASCII cache name (`Attachments.fileName`), never overwriting an existing file in Downloads (taking the first free `name (n).ext` when the name is already taken).

## Reason

A single `MediaStore.Downloads` write path avoids requesting broad external-storage permissions, keeps partial or failed writes hidden until complete, prevents clobbering existing files in Downloads, and reuses the existing ASCII name sanitiser so filenames never contain path separators or bidirectional overrides.
