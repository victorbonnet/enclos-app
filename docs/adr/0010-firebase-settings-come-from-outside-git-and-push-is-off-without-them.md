---
status: proposed
---

# Firebase settings come from outside git, and push is off without them

A host sends pushes through FCM with a service-account key of the Firebase project the app was built with (enclos [ADR 0065](https://github.com/victorbonnet/enclos/blob/main/docs/adr/0065-each-host-sends-push-through-fcm-with-a-key-its-user-gives-it.md), proposed). FCM gives a device a token for one project, and a host whose key is for another project cannot reach it. Firebase's usual setup, the google-services Gradle plugin reading a `google-services.json` committed with the app, fixes that project in the repository.

Decision (proposed): **the app takes Firebase's four settings (application id, API key, project id, sender id) at build time from a file kept out of git**: on a laptop `~/.android/enclos-firebase.properties` (or `ENCLOS_FIREBASE_PROPERTIES`), in CI from the `ENCLOS_FIREBASE_PROPERTIES_B64` repository secret. The app starts Firebase itself with them; it uses neither the google-services plugin nor Firebase's automatic start. A build without them has push off: it asks Firebase for nothing, gives hosts no token, and gets new messages at its next sync, as before.

Reason: the settings are not secret, since every APK carries them, but they bind a build to one project. Kept outside, the repository names no project: the publisher builds with theirs, and anyone who builds the app with a project of their own, and gives their hosts that project's key, gets push without changing the code.

## Consequences

- Builds with different settings cannot share pushes: a host's key works only for devices whose app was built with its project.
- Firebase Messaging still talks to Google from the phone, for the token and the pushes; the app adds no other Firebase product, and Firebase's delivery-metrics export stays off (its default).
