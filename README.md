# Connect

A private messaging app for two people who want to stay close.

Send a photo and it lands directly on the other person's home screen widget —
no notification to open, no app to launch. It's just there, on their phone,
next to everything else they look at all day.

## Features

| Feature | Status |
|---|---|
| Photo to partner's home screen widget | In progress |
| Pairing two accounts with an invite code | Planned |
| Text messaging | Planned |
| Shared live drawing canvas | Planned |
| Wear OS watch face | Planned |

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
