# Connect

A private messaging app for two people who want to stay close.

Send a photo and it lands directly on the other person's home screen widget —
no notification to open, no app to launch. It's just there, on their phone,
next to everything else they look at all day.

## Features

| Feature | Status |
|---|---|
| Pairing two accounts with an invite code | Built |
| Shared reminder list, with nudges | Built |
| Private reminder list | Built |
| Photo to partner's home screen widget | Built, untested on a device |
| Text messaging | Planned |
| Shared live drawing canvas | Planned |

### The two reminder lists

**Together** is shared. Both people see it, either can add, tick off or edit
anything on it, and either can nudge the other about an item — which arrives
as a notification on their phone.

**Just mine** is private. It lives under your own user document rather than
under the pairing, so it is unreachable by your partner. That is enforced by
security rules on the path, not by a flag on the document; a flag would be
something anyone who could write the document could flip.

## Stack

- **Kotlin** + **Jetpack Compose** for the phone app
- **Glance** for the home screen widget
- **Firebase** — Firestore, Storage, Auth, and Cloud Messaging
- **Cloud Functions** to push updates between paired devices

Minimum SDK 26 (Android 8.0), target SDK 35.

## Getting started

You need Android Studio and a Firebase project.

1. Clone the repo and open it in Android Studio.
2. Create a Firebase project and register an Android app with the package
   name `com.obsidian.connect`.
3. Download `google-services.json` into `app/`. This file is gitignored —
   it never gets committed.
4. Enable Firestore, Storage, Authentication, and Cloud Messaging in the
   Firebase console.
5. Sync Gradle and run.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the photo-to-widget
pipeline actually works.
