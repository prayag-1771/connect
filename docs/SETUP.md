# Setting up Connect

Everything needed to go from a fresh clone to a photo landing on someone
else's home screen.

Budget about 20 minutes. No payment details needed at any point —
this runs entirely on Firebase's free tier.

## Before you start: you need two phones

This is not a nice-to-have. The whole app is one person sending to another
person, and the widget only ever shows what your *partner* sent. One device
gets you as far as an invite code with nobody to give it to.

Two Android phones is the good setup. One phone plus an emulator works — use a
**Google Play** or **Google APIs** system image, since Firebase's client
libraries expect Google Play services to be present.

Be aware an emulator is a poor model of the thing most likely to go wrong:
real phones aggressively defer background work to save battery, and emulators
do not.

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

## 2. Turn on the two services

Still in the console. This project runs entirely on the **free Spark plan**, so
there are only two things to enable — and you should deliberately *not* touch
Storage.

| Service | Where | What to do |
|---|---|---|
| **Authentication** | Security → Authentication | Get started → Sign-in method → **Email/Password** → enable the first toggle → Save |
| **Firestore** | Databases and storage → Firestore | Create database → **Production mode** → pick a region → Enable |

If a menu item is hard to find, use **Search for products** at the top left.

Pick the region carefully. **It cannot be changed later.** Choose whichever is
closest to you.

### Skip Storage. Skip Blaze.

Cloud Storage now requires the paid Blaze plan, and so do Cloud Functions.
Neither is used. Photos travel inside Firestore documents instead, and the
receiving phone polls rather than being pushed to — see
[ARCHITECTURE.md](ARCHITECTURE.md) for why that works and what it costs.

If you open the Storage page it will offer to upgrade your project. Don't.

**What you give up by staying free:** a photo can take up to 15 minutes to
appear on a closed phone. With the app open it is near-instant, and after a
reboot it syncs immediately. That 15-minute worst case is WorkManager's floor
for background work and there is no way around it without a server.

## 3. Deploy the rules and indexes

```bash
npm install -g firebase-tools
firebase login                    # opens a browser
firebase use --add                # pick your project
```

Then:

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

**Only that command.** A bare `firebase deploy` will try to deploy Cloud
Functions and fail, because those need Blaze.

### The indexes matter

Three composite indexes go up with that command, and the queries behind them
fail at *runtime* without them — not at build time. The important one is
`moments` over `pairingId`, `senderId`, `createdAt`, which is how the widget
finds the newest photo from your partner specifically. They take a minute or
two to finish building after deploying.

## 4. Build the APK

You must rebuild after dropping in the real `google-services.json` — the
config is compiled into the APK, so a build made against the placeholder will
point at a project that does not exist.

```bash
./gradlew assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

If Gradle cannot find the SDK, check `local.properties` has a correct
`sdk.dir`. That file is machine-specific and gitignored.

## 5. Get it onto the phones

On each phone: **Settings → About phone → tap "Build number" seven times**,
then **Settings → Developer options → USB debugging** on.

Plug in over USB, accept the debugging prompt on the phone, then:

```bash
adb devices                                                  # confirm it shows
adb install app/build/outputs/apk/debug/app-debug.apk
```

Repeat for the second phone. Same APK on both.

## 6. Actually use it

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
9. **With the Connect app open on phone B**, the widget updates within a
   second or two. Do this first — it confirms the whole path works.
10. Now close the app on phone B entirely and send another. This time it can
    take up to 15 minutes. That is the free-plan behaviour, not a bug.

**Try the reminders**

11. **Reminders** tab → **Together** → add something. It appears on the other
    phone immediately — reminder lists use live listeners, so they are not
    affected by the polling delay at all.
12. Tap the bell on an item to nudge. The notification arrives on the other
    phone on the sync schedule: immediately if their app is open, otherwise
    within 15 minutes.
13. **Just mine** is private. Add something there and confirm it does *not*
    show up on the other phone.

## When something does not work

**The widget is not updating.** First, open the app on the receiving phone —
if it updates within a second or two, sync is working and you are just seeing
the polling delay.

If it still does not update:

1. **Battery optimisation.** This is the usual cause. Samsung, Xiaomi, Oppo
   and OnePlus defer or silently drop background work. Settings → Apps →
   Connect → Battery → **Unrestricted**.
2. Confirm both phones are signed in as *different* accounts and paired.
3. Check the indexes finished building — Console → Firestore → Indexes.

**Photos take up to 15 minutes.** Working as designed on the free plan. See
[ARCHITECTURE.md](ARCHITECTURE.md).

**"No matching client found for package name"** — the Android app registered
in Firebase has a different package name than `com.obsidian.connect`. Register
another Android app in the same project with the right name and download a
fresh `google-services.json`.

**"PERMISSION_DENIED" in the app** — rules did not deploy. Re-run step 3.

**"The query requires an index"** — the composite index has not finished
building, or `firestore:indexes` was not deployed. The error message in
logcat contains a direct link that creates it.

**Nudges never arrive** — notification permission. Android 13 and up needs it
granted; the app asks on first launch, and if it was denied the notification
is dropped silently. Settings → Apps → Connect → Notifications. Note nudges
arrive on the same polling schedule as photos.

**"Photo is too large"** — should not happen; the compressor steps quality and
then dimensions down until it fits under 700KB. If you see it, the photo
somehow resisted every reduction, and it is worth reporting.

## Reading device logs

```bash
adb logcat | grep -iE "connect|firebase|glance|fcm"
```

Genuinely the fastest way to find out what is happening.
