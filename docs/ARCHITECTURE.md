# Architecture

## The core problem

Getting a photo from one person's camera onto another person's home screen
is the hard part of this app. Everything else — chat, drawing — is ordinary
realtime sync.

Home screen widgets can't poll. They're passive: the system decides when they
redraw, and an app that's been swiped away gets no CPU time at all. So the
photo can't be *pulled*. It has to be *pushed*, and the push has to be able to
wake a process that isn't running.

That means Firebase Cloud Messaging. There's no way around it.

## The pipeline

```
    Device A                  Firebase                    Device B
   ----------                ----------                  ----------
   take photo
   downscale
       |
       +-- upload ---------> Storage
       |
       +-- write ----------> Firestore
                             moments/{id}
                                  |
                                  | onCreate trigger
                                  v
                             Cloud Function
                                  |
                                  +-- high-priority ------> FcmService
                                      data message              |
                                                                | enqueue
                                                                v
                                                          WorkManager
                                                                |
                                                          download image
                                                          write to disk
                                                                |
                                                                v
                                                          Glance widget
                                                          updates in place
```

## Why each piece

**Downscale before upload.** Widgets pass their bitmaps across a Binder
transaction, which has a hard ~1 MB limit. Blow past it and the widget
silently fails to draw. Photos get resized to roughly the widget's actual
pixel size and compressed to JPEG before they ever leave the sender.

**A data message, not a notification.** Notification messages get handled by
the system tray when the app is backgrounded, and your code never runs. Only
data messages reach `onMessageReceived` reliably enough to trigger work.
They're sent at high priority so Doze doesn't sit on them.

**WorkManager, not a raw thread.** `onMessageReceived` gives you about 20
seconds before the system can kill you, which is not enough to guarantee an
image download on a bad connection. WorkManager survives that and retries.

**Write to internal storage, then update.** Glance state should point at a
file on disk rather than carry a bitmap around. That keeps the Binder payload
small and means the widget can redraw after a reboot without a network call.

## Known constraints

- **Doze mode can still delay delivery.** High-priority data messages are
  exempt in most cases, but a device in deep Doze with aggressive OEM battery
  management (Xiaomi, Oppo, Samsung) may hold the push. There's no universal
  fix; the mitigation is asking the user to exempt the app from battery
  optimization.
- **Cloud Functions requires the Blaze plan.** Sending a push to another user
  needs a trusted server, because the FCM server credential cannot be shipped
  inside a client app. Blaze has a free monthly grant that this app's traffic
  won't exceed, but it needs a card on file.

## Module layout

```
core/       models, Firebase repositories, no Android UI
app/        phone app — auth, pairing, camera, chat, drawing, widget
functions/  Cloud Functions (TypeScript)
```

There is no watch module and none is planned — the widget targets the phone
home screen only.

`core` stays free of Android UI anyway. That keeps the Firebase layer testable
on the JVM without an emulator, which is the part most worth testing.

## Data model

```
users/{uid}
  displayName, photoUrl, fcmToken, pairId

pairs/{pairId}
  members: [uidA, uidB]
  createdAt
  inviteCode

moments/{momentId}
  pairId, senderId, storagePath, caption, createdAt

pairs/{pairId}/messages/{messageId}
  senderId, text, createdAt

pairs/{pairId}/strokes/{strokeId}
  senderId, points, color, width, createdAt

pairs/{pairId}/reminders/{reminderId}     <- the shared list
  title, note, dueAt, done, createdBy, completedBy, completedAt, createdAt

pairs/{pairId}/nudges/{nudgeId}
  reminderId, reminderTitle, fromUid, toUid, createdAt

users/{uid}/reminders/{reminderId}        <- the private list
  title, note, dueAt, done, createdBy, createdAt
```

## Why the private list lives somewhere else

Both reminder lists hold identical documents. They could have been one
collection with a `private: true` field, and that would have been simpler.

It would also not have worked. Security rules can restrict a *path*, but they
cannot stop someone from changing a field on a document they are already
allowed to write. Anyone with write access to the shared list could flip that
flag and pull a private item into view — or flip it the other way and hide
something from their partner.

Putting the private list under `users/{uid}` makes the guarantee structural.
There is no field to flip, because reachability is decided by where the
document sits.

One thing that makes this work: **Firestore subcollection rules do not inherit
from the parent document.** `users/{uid}` is readable by any signed-in user so
partners can show each other's names, but `users/{uid}/reminders` underneath it
is owner-only, and the permissive rule above does not reach in.

Strokes live in their own subcollection so the drawing canvas can attach a
realtime listener scoped tightly to one pair, without pulling message history
along with it.
