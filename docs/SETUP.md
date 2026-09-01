# Setting up Connect

Everything needed to go from a fresh clone to a photo landing on someone
else's home screen.

Budget about 30 minutes. Most of it is waiting on Firebase.

## Before you start: you need two phones

This is not a nice-to-have. The whole app is one person sending to another
person, and the widget only ever shows what your *partner* sent. One device
gets you as far as an invite code with nobody to give it to.

Two Android phones is the good setup. One phone plus an emulator works, but
the emulator image must be a **Google Play** or **Google APIs** one — plain
AOSP images have no Google Play services, and without those, push messages
never arrive and the widget never updates.

---

## 1. Create the Firebase project

In a browser, at [console.firebase.google.com](https://console.firebase.google.com):

1. **Add project** → name it whatever you like → you can turn Google Analytics
   off, nothing here uses it.
2. Inside the project, click the **Android** icon to add an app.
3. Package name must be exactly:

   ```
   com.obsidian.connect
   ```

   It has to match `applicationId` in `app/build.gradle.kts`. A mismatch fails
   the build with "No matching client found for package name".
4. Download **`google-services.json`** and put it at `app/google-services.json`,
   replacing the placeholder that is already there.

   That file is gitignored and never gets committed.
5. Skip the SDK setup steps the console shows you — the Gradle wiring is
   already in the repo.

## 2. Turn on the four services

Still in the console, in the left sidebar:

| Service | What to do |
|---|---|
| **Authentication** | Sign-in method → enable **Email/Password** |
| **Firestore Database** | Create database → **Production mode** → pick a region |
| **Storage** | Get started → **Production mode** → same region |
| **Cloud Messaging** | Already on. Nothing to do. |

Production mode locks everything down by default. That is correct — the real
rules get deployed in step 4 and they are stricter than the test-mode ones.

Pick the region carefully. **It cannot be changed later**, and Firestore and
Storage should be in the same one. Choose whichever is closest to you.

## 3. Upgrade to the Blaze plan

Cloud Functions need it. Console → the **Spark/Blaze** indicator at the bottom
of the sidebar → **Upgrade to Blaze**. It needs a card.

Blaze includes a free monthly allowance that a two-person app will not come
close to exhausting, so realistically this stays at zero. But the card is
required and there is no way around it.

**What breaks without it:** everything on-device still works — signing in,
pairing, both reminder lists, taking photos, uploading them. What stops is
delivery. Nothing reaches the other phone, because the push has to be sent by
a trusted server and Functions is that server. Photos upload and then sit
there. Nudges go nowhere.

If you would rather not put a card down, the alternative is running the same
two functions on any free Node host and pointing them at the project with a
service account key. That is a real option, just more moving parts.

## 4. Deploy the backend

```bash
npm install -g firebase-tools
firebase login                    # opens a browser
firebase use --add                # pick the project you just made
```

Then deploy the rules and indexes:

```bash
firebase deploy --only firestore:rules,firestore:indexes,storage
```

And the functions:

```bash
cd functions && npm ci && cd ..
firebase deploy --only functions
```

The first functions deploy takes a few minutes and will ask to enable some
Google Cloud APIs. Say yes.

Two functions should appear: `onMomentCreated` and `onNudgeCreated`.

### The index matters

`firestore:indexes` deploys a composite index on `moments` over `pairingId`
plus `createdAt`. Without it the photo history query fails at runtime with a
"query requires an index" error rather than at build time. It takes a minute
or two to build after deploying.

## 5. Build the APK

You must rebuild after dropping in the real `google-services.json` — the
config is compiled into the APK, so a build made against the placeholder will
point at a project that does not exist.

```bash
./gradlew assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

If Gradle cannot find the SDK, check `local.properties` has a correct
`sdk.dir`. That file is machine-specific and gitignored.

## 6. Get it onto the phones

On each phone: **Settings → About phone → tap "Build number" seven times**,
then **Settings → Developer options → USB debugging** on.

Plug in over USB, accept the debugging prompt on the phone, then:

```bash
adb devices                                                  # confirm it shows
adb install app/build/outputs/apk/debug/app-debug.apk
```

Repeat for the second phone. Same APK on both.

## 7. Actually use it

**On phone A**

1. Open Connect, create an account.
2. You land on the pairing screen. Tap **Create an invite**.
3. A six-character code appears.

**On phone B**

4. Create a *different* account.
5. Type the code into **Have a code?** and tap **Join**.

Both phones should move to the main screen on their own. Phone A does not
need a refresh — it is watching the pairing document and advances when B
joins.

**Add the widget on phone B**

6. Long-press an empty spot on the home screen → **Widgets** → find
   **Connect** → drag it out.
7. It will say "Nothing yet".

**Send something from phone A**

8. **Send** tab → shutter → review the shot → optionally add a caption →
   tap send.
9. Within a few seconds the widget on phone B shows the photo.

**Try the reminders**

10. **Reminders** tab → **Together** → add something. It appears on the other
    phone.
11. Tap the bell on an item to nudge — a notification lands on the other
    phone.
12. **Just mine** is private. Add something there and confirm it does *not*
    show up on the other phone.

## When something does not work

**The widget never changes.** Almost always the push. Check in order:

1. Is `onMomentCreated` deployed? Console → Functions.
2. Its logs — `firebase functions:log`. "No FCM token for recipient" means the
   receiving phone never registered; reopen the app on it while signed in.
3. Battery optimisation. Samsung, Xiaomi, Oppo and OnePlus are aggressive
   about killing background apps, and a killed app does not receive data
   pushes. Settings → Apps → Connect → Battery → **Unrestricted**.

**"No matching client found for package name"** — the Android app registered
in Firebase has a different package name than `com.obsidian.connect`. Register
another Android app in the same project with the right name and download a
fresh `google-services.json`.

**"PERMISSION_DENIED" in the app** — rules did not deploy. Re-run step 4.

**"The query requires an index"** — the composite index has not finished
building, or `firestore:indexes` was not deployed. The error message in
logcat contains a direct link that creates it.

**Nudges never arrive but photos do** — notification permission. Android 13
and up needs it granted; the app asks on first launch, and if it was denied
the notification is dropped silently. Settings → Apps → Connect →
Notifications.

## Reading device logs

```bash
adb logcat | grep -iE "connect|firebase|glance|fcm"
```

Genuinely the fastest way to find out what is happening.
