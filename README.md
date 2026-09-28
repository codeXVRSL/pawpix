# PawPixel

Turn a photo of your real pet into a full-body pixel pet: a hand-drawn-style chibi character (big round head, stubby legs, glossy eyes) painted in your pet's own fur colours and face markings, read from the photo. It lives on your home screen, and its mood follows the real care you give: fed, walked, medicine given.

Android and iOS, with one Kotlin codebase (Compose Multiplatform) plus a native widget on each platform.

## What's in this MVP

| Feature | Where |
|---|---|
| Photo to fur colours and face markings, fully on device (cut-out, face square guessed and adjustable, OKLab palette, patch map) | `core/.../sprite/SpritePipeline.kt` |
| **Full-body chibi pets**: head about half the height, walking legs, a wagging tail, drawn eyes that blink, and a curled-up sleeping pose. Works from face close-ups and full-body photos alike | `core/.../sprite/Chibi.kt` |
| 7 mood poses (happy, content, hungry, restless, needs meds, sleepy, sad) drawn with posture, lighting and original pixel icons, never by redrawing the pet's face | `core/.../sprite/Poses.kt` |
| **Living pet animation**: breathing, blinking, looking around, wandering the floor, hopping, wet-dog shakes, begging when hungry, pacing when restless, drooping when sad, curling up asleep at night. Tap the pet for hearts. Logging care plays a reaction: eating from a bowl after feeding, zoomies after a walk, a shake after grooming | `core/.../sprite/Animator.kt` (frames), `PetBrain.kt` (behaviour), `composeApp/.../ui/LivePet.kt` |
| One consistent pixel style: every pet is drawn from the same chibi template (dog or cat body, pointy or floppy ears), with three-tone shading and a soft coloured outline. The photo supplies up to three fur tones and where they go on the face (white muzzle, dark ears, patches) | `sprite/Chibi.kt` (`PetLook`, `Chibi.compose`) |
| **Share animation**: a 5-second looping GIF of your pet, watermarked | `core/.../sprite/Stage.kt` (`AnimatedExport`, `Gif`) |
| Care tasks (feed, water, walk, play, meds, groom, litter), daily or every N days, 1 to 4 times a day | `core/.../core/CareEngine.kt` |
| Mood engine: overdue tasks lower the mood over each task's grace period | `core/.../core/MoodEngine.kt` |
| "Learn my routine": reminders drift toward when you actually do the task (up to 2 hours) | `AdaptiveTiming` in `CareEngine.kt` |
| Reminders with a **Done** button on the notification; medicine gets a follow-up nudge | `ReminderPlanner.kt` + platform code |
| Home-screen widget with a one-tap **Done**. It shows the pet that needs you most, and its mood changes on time even with the app closed | Android: Glance, `widget/PetWidget.kt`; iOS: WidgetKit, `iosApp/PetWidget/PetWidget.swift` |
| Shareable before/after "reveal card" with a PawPixel watermark (the growth loop) | `core/.../sprite/RevealCard.kt` |
| First pet free, more pets gated for Pro (beta toggle until billing is wired) | `StateOps.canAddPet` |
| Privacy by design: no account, no uploads, delete-all in Settings | whole app |
| **Map backend, ready for the Phase 3 Naga pilot (not in the app yet):** grid-snapped locations, cells need 3+ owners before showing, venue revealed only after RSVP, blocking, reporting | `supabase/migrations/0001_gatherings_map.sql`, `core/.../core/LocationGrid.kt` |

Why the map isn't in the app yet: the research showed "approximate distance" features leak exact locations (dating apps were pinpointed to <5 m). The map needs accounts, moderation and Data Privacy Act paperwork, and it only works once enough Naga pets are on the app. The privacy-safe backend is built and tested, so it's ready when you are. See `docs/MAP_SAFETY.md`.

## Phase 0: the web Pet Maker
`web/` is a phone-friendly page where anyone uploads a pet photo and watches it become a living pixel pet, using the same Kotlin engine as the apps (compiled to JavaScript) plus an in-browser pet detector (U²-Net-p). Photos never leave the device. It has:
- an **"Is that your pet?"** tally, kept on the device you're testing with. Hand your phone to owners, then read the result.
- GIF and before/after card sharing.
- a waitlist button, which appears once you set `WAITLIST_URL` in `page.template.html` (e.g. a Google Form).

Build: see the top of `web/build_web.py`.

## Project layout

```
core/          Pure Kotlin (Android, iOS, JVM). All logic and the sprite generator. Tested.
composeApp/    Shared Compose UI + Android app (androidMain) + iOS glue (iosMain).
iosApp/        SwiftUI host, Swift services (photos, Vision, notifications, share), WidgetKit widget.
supabase/      Database migration for the future gatherings map.
docs/          Privacy policy draft and map safety notes.
web/           Phase 0 web Pet Maker (likeness test + waitlist).
```

## Build and run

You need a Mac for iOS. Android works on any OS.

### Android (Android Studio)
1. Open the `pawpixel` folder in Android Studio (Ladybug or newer, with the Kotlin Multiplatform plugin).
2. Let Gradle sync, then run the **composeApp** configuration on a phone or emulator (Android 8.0+).
3. Long-press the home screen, open Widgets, and add **PawPixel**.

Command line: `./gradlew :composeApp:assembleDebug`

### iOS (Xcode 15+, iOS 17+)
1. `brew install xcodegen`
2. `cd iosApp && xcodegen`, which creates `PawPixel.xcodeproj`
3. Open it, and set your **Team** on both targets (PawPixel and PetWidget).
4. In your Apple Developer account, register the App Group `group.com.pawpixel.app` and enable it for both bundle IDs (`com.pawpixel.app`, `com.pawpixel.app.widget`). If you change the ID, update it in both `.entitlements` files and in `SwiftHost.swift` / `PetWidget.swift`.
5. Run on a real iPhone. The pet cut-out uses Vision, which doesn't run in the Simulator. There the app falls back to its own plain-background cut-out.

The Xcode build step runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` to compile the Kotlin framework, so the first build takes a few minutes.

### Tests and the likeness test tool
```
./gradlew :core:jvmTest                                     # 45 tests: care, mood, reminders, sprite, animation, GIF, PNG, JSON, grid
# End-to-end on your own emulator/phone:
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest && scripts/android-e2e.sh
./gradlew :core:spriteLab --args="path/to/photos out --species=cat"   # full-body pets, poses, GIFs and reveal cards for a folder of photos
```
**Do the likeness test before building further:** put 5 real pet photos in a folder, run `spriteLab`, and show each owner the result. Ask "Is that your pet?" If ~4 of 5 say yes, go. If not, tune `SpritePipeline` first. Tip: add `<name>.mask.png` (white = pet, from any background remover) next to a photo to preview what the phone's native cut-out will do. 

## How the pieces talk

```
UI (Compose) → PawRepository → StateOps (pure) → state.json
                     └→ publish(): widget.json + mood PNGs → widgets
                                   ReminderPlanner → AlarmManager / UNUserNotificationCenter
iOS widget "Done" → pending_done.json → ingested by the app on next open
Android widget "Done" → ActionCallback → PawRepository.complete()
```
Widgets never run the mood engine. The app precomputes a 24-hour mood timeline (plus an "if done now" timeline for the Done button) into `widget.json`, and each widget just shows the right entry for the current time.

## Automatic checks (every push)

| CI job | What it proves |
|---|---|
| Core tests | 45 unit tests: care, mood, reminders, sprite style, animation, GIF, PNG, JSON, map grid |
| Android debug APK / release bundle | The app builds; the release bundle (R8) builds and is signed if the secrets below are set; native libraries are 16 KB aligned |
| **Android end-to-end** | On an Android 14 emulator, the real app goes photo → pixel pet → ears → face square → name and save → Done → add medicine → share GIF and card → notification **Done** → home-screen **widget** → relaunch. Screenshots, step log and logcat are pushed to the `ci-results/android` branch |
| **iOS build and end-to-end** | App, widget and UI tests build; in the iPhone 16 Simulator the app goes photo → pet → save → Done → medicine → share sheet → settings → relaunch. Screenshots and logs are pushed to `ci-results/ios` |

Only the system photo picker is stubbed in these tests; everything else is the real app.

## Before you release

- [ ] **Likeness test** with 5 real pets (see above) or with the web Pet Maker
- [ ] Set `SUPPORT_EMAIL` and `PRIVACY_URL` in `SettingsScreen.kt`, and publish `docs/PRIVACY.md` (GitHub Pages works)
- [ ] **Android signing:** create an upload key (`keytool -genkeypair -v -keystore upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000`), then add repo secrets `PAWPIXEL_KEYSTORE_B64` (`base64 -w0 upload.jks`), `PAWPIXEL_KEYSTORE_PASSWORD`, `PAWPIXEL_KEY_ALIAS`, `PAWPIXEL_KEY_PASSWORD`. The "Android release bundle" job then produces a signed `.aab` for Play; its versionCode is the CI run number.
- [ ] Google Play: new personal developer accounts must run a closed test with 12+ testers for 14 days before production. Start early. Use `docs/STORE_LISTING.md`, `docs/brand/` and the screenshots in `ci-results/android`.
- [ ] **iOS:** set your Team, register the App Group (see above), archive in Xcode and upload. The privacy manifests (`PrivacyInfo.xcprivacy`) are included.
- [ ] Pro: extra pets are locked in release builds ("coming soon"); the unlock switch only shows in debug builds. Wire in-app purchases (RevenueCat's KMP SDK suggested) when you're ready to charge.
- [ ] Android 14+: "Exact time" reminders need the user to allow alarms (Settings, Apps, PawPixel, Alarms & reminders). Without it they arrive a few minutes late.

## Known limits (by design, for the MVP)
- Widgets show still mood poses, not animation. iOS doesn't allow animated widgets, and it keeps battery use low. The app itself animates.
- Body markings (spots, socks) aren't copied yet; the chest and paws use the pet's lighter tone when it has one. A hand-drawn body from the owner's photos is a natural Pro upgrade, and a fit for your pixel art commissions.
- Likeness comes from colours and markings, not a pixelated copy of the photo. Pasting the real (pixelated) face on a drawn body was tried and dropped: the two styles clashed and the head looked out of proportion. Fine stripes (tabby) are not reproduced yet.
- One widget pet: with several pets, the widget shows whichever needs attention most.
- Adaptive timing learns from the last 4 weeks and needs 3 completions near a time before moving it.
- Daylight-saving shifts may move a reminder by an hour on the change day (not an issue in the Philippines).
