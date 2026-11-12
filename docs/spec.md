# enclos app: what it does

How the app behaves, on every platform. It starts with the parts of pairing and of the connection that a review asked to write down; the rest of the app's behaviour is added as it is written down.

## 2. Pairing

**A link naming a host already paired.** A pairing link gives the host's name itself, so a name proves nothing; the bridge's key, which the Noise handshake checks, tells one bridge from another. When the link names a host this phone is already paired with:

- **Same bridge key**: the app pairs again, as for a new host. Once the bridge accepts, the new pairing takes the old one's place, the old pairing's device key is deleted from the key store, and what the app shows of the host stays.
- **Another bridge key**: the app makes no key and does not connect. It shows a card, "Replace <host>?", with the paired bridge's address and the link's. **Keep <host>** leaves the pairing as it was. **Replace** pairs with the link's bridge; once it accepts, the old pairing, its device key and what the app showed of that host are gone.

The replaced host still lists this phone as a paired device until it is removed on that host.

**A pairing that is not accepted** (refused, the link expired, the connection ended, the key could not be made) deletes the device key it made. The keys are made off the main thread, and a failure to make them ends the pairing with its reason.

## 3. Connection

A message from the bridge that the app cannot read (a field missing, as from another version of `enclos app serve`) ends that host's connection, with that reason on screen. The app does not open it again on its own, since the next connection would get the same message: Reconnect does, or the app's next return to the foreground.
