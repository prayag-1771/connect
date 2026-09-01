# Architecture

## The core problem

Getting a photo from one person's camera onto another person's home screen
is the hard part of this app. Everything else — chat, drawing — is ordinary
realtime sync.

A widget cannot fetch anything for itself. It is passive: the system decides
when it redraws, and an app that has been swiped away gets no CPU time at all.
Something else has to wake up, get the photo, and hand it over.

The clean answer is a push — a server tells the phone, the phone wakes, the
widget updates in seconds. That needs Cloud Functions, which needs a paid plan.

This project runs entirely on the free tier, so it does the other thing: the
receiving device wakes on a schedule and goes looking. That works, and it
costs nothing, and it means a photo can sit unseen for up to fifteen minutes.
Most of the design below follows from that trade.

## The pipeline

This project runs on Firebase's free Spark plan, which rules out two things
the obvious design would use: **Cloud Storage** and **Cloud Functions**. Both
need a card on file. What follows is shaped by working around exactly that.

```
    Device A                  Firestore                   Device B
   ----------                -----------                 ----------
   take photo
   downscale to 720px
   compress until
   under 700KB
       |
       +-- write ----------> moments/{id}
             (JPEG inline         |
              as a Blob)          |
                                  |    app open:  live listener
                                  +--> after boot: BootReceiver
                                  |    otherwise:  periodic worker (15 min)
                                       |
                                       v
                                 decode + write to disk
                                       |
                                       v
                                 Glance widget updates
```

## Why the photo lives in the database

Cloud Storage is the natural home for an image and it is not available here.

It turns out not to matter, because the photo was already tiny. Glance passes
widget bitmaps to the launcher across a Binder transaction capped near 1MB, so
these images are downscaled hard before they go anywhere — 720px on the long
edge, compressed until under 700KB. A Firestore document holds up to 1MiB.

So the constraint that made the widget awkward is the same one that makes this
work.

The compressor steps quality down and then dimensions until the result
actually measures small enough, rather than encoding once at a fixed quality
and hoping. A noisy photo — foliage, confetti, anything high-frequency —
encodes several times larger than a smooth one at identical dimensions, and a
document over 1MiB is rejected outright with an error that explains nothing.

Old photos are pruned to the most recent 30 per pairing after every send. The
free plan caps the entire database at 1GiB and nothing else reclaims it.

## Why the receiver polls

Pushing to another person's device needs credentials that cannot ship inside a
client app, which means a trusted server, which means Cloud Functions, which
means a paid plan. There is no free way around this.

So delivery is inverted: instead of being told, the receiving device looks.

| Situation | Latency |
|---|---|
| App open on the receiving phone | Round trip — a Firestore listener |
| Just rebooted | Immediate, via `BootReceiver` |
| App closed | **Up to 15 minutes** |

Fifteen minutes is WorkManager's floor for periodic work, not a number chosen
here; anything smaller is silently rounded up to it. That worst case is the
honest price of the free plan.

The Cloud Functions in `functions/` are complete and unused. `FirebaseMessagingService`
is still registered, and a push arriving there simply triggers the same sync
the worker runs. Deploying the functions later converts polling into instant
delivery without a single client change.

## Known constraints

- **Up to 15 minutes for a photo to land on a closed phone.** Inherent to
  polling; see above.
- **Aggressive OEM battery management makes that worse.** Xiaomi, Oppo,
  Samsung and OnePlus will defer or drop background work entirely. The
  mitigation is exempting the app from battery optimisation.
- **1GiB total database size**, which is also where every photo lives.
  Pruning keeps 30 per pairing, so roughly 6MB of photos per couple.
- **20,000 Firestore writes and 50,000 reads per day.** Not close to
  reachable with two people.

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
