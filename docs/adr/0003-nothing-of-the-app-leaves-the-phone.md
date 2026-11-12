---
status: proposed
---

# Nothing of the app leaves the phone

The app holds, for each host, the device's id, its Noise key (wrapped by the platform's key store) and the bridge's address and key; later, conversations. Phones copy app data to the cloud and to a new phone by default. A copy of the wrapped key is useless without the old phone's key store, but the rest (which hosts, which tailnet addresses, what was said) would sit in a backup the person may not think of. On Android 12 and later, `allowBackup="false"` no longer stops the copy to a new phone.

Decision (proposed): **no backup and no transfer to a new phone**, on every platform and release the app supports. On Android, `allowBackup="false"` with backup rules for Android 9 to 11 and data extraction rules for 12 and later, each excluding every domain from cloud backup and device transfer. On iOS, key store items `ThisDeviceOnly` and the app's files excluded from backup. A new phone pairs with each host again.

Reason: pairing again takes a minute per host and is needed anyway, since the device's keys cannot leave the phone's key store; in exchange, what the app knows stays on the one phone the person paired.

## Consequences

- Moving to a new phone, the app starts empty there; the old phone's devices stay paired on each host until `enclos app unpair` removes them.
- The person's history is kept by the bridge on each host, and synced to a phone that pairs again.
- That a transfer to a new phone carries nothing is in the rules, not yet shown by a transfer (`docs/spec.md`, To verify).
