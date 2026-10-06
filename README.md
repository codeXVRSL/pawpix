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
| **Share with your household (opt-in):** care for a pet together with a partner or family (up to 8 people). Same sign-in as the map, an invite code to join, and every Done shows on everyone's phone and widget as "Fed by Jamaica · 7:02 AM", so nobody feeds twice. Offline-first: an append-only care log synced incrementally, undo travels, the later of two offline edits wins. Pets travel as their pixel look code, never a photo | `core/.../core/HouseholdSync.kt`, `core/.../map/HouseholdClient.kt`, `composeApp/.../FamilyModel.kt`, `ui/FamilyScreen.kt`, `supabase/migrations/0004_households.sql`, `0006_household_log.sql` |
| **Pet map (opt-in, Naga pilot):** Google/Apple sign-in, 18+ and consent, your area as a ~1 km square (location snapped on the phone), areas shown only with 3+ owners, pixel pets redrawn from look codes (never photos), block/report by pet, gatherings with RSVP and venue revealed after RSVP, leave and account deletion. A real street map of the world drawn by the app itself as a cartoon, from OpenFreeMap's free vector tiles (OpenStreetMap data, no key, no limits): candy-coloured water, parks, blocks and roads as chunky pixels, street names in the app's own type, paw pins. **Community:** owners host their own walks from the app (a public meeting place tapped on the map, approved by the moderator before it shows), walks get flag pins, every walk and the map itself can be shared as an invite, a one-line count of owners, pets, areas and walks, and each walk shows the pixel pets of the owners going (never the owners) | `composeApp/.../ui/PetMapScreen.kt`, `WalkHosting.kt`, `TileMap.kt`, `core/.../map/MapClient.kt`, `supabase/migrations/` (`0008_community_walks.sql`), setup: `docs/MAP_SETUP.md` |
| **Lost and Found:** one tap on a missing pet's page raises an alert with the pixel twin, the album photos you choose and the spot last seen; every owner within 15 km sees it as a red flag on the map and in a "Lost pets" tab, with the photos, and can report a sighting (spot, note, photo); the owner sees sightings and closes the alert with "Safe home" (confetti). A share link carries the alert to Facebook and Messenger, and a web page shows it to finders without the app. **Noise nights:** two reminders before New Year's Eve everywhere (and July 4 in the US, Bonfire Night in the UK), the nights most pets run away | `composeApp/.../ui/LostAndFound.kt`, `core/.../map/LostAndFound.kt`, `supabase/migrations/0010_lost_and_found.sql`, `web/lost.template.html`, `core/.../core/ReminderPlanner.kt` (`noiseNights`) |
| **Languages:** English and Filipino in full; Spanish and Brazilian Portuguese for the everyday strings (the room, care, moods, reminders, the maker, the main buttons) and the newer screens (Lost and Found, Pals and moments, walks, the monthly challenge, weather, occasions, units), chosen from the phone's language or in Settings, with English filling what isn't translated yet. Adding a language is one Kotlin map | `core/.../i18n/I18n.kt`, `EsCore.kt`, `EsScreens.kt`, `PtCore.kt`, `PtScreens.kt` |
| **Vet visit summary:** one page from the Health panel with the pet, birthday and age, microchip, the latest weight and how it moved, every vaccine, deworming and parasite item with last-given and next-due dates, the daily routine and this week's walks; copy it or send it ahead by Messenger or email | `core/.../core/VetSummary.kt`, `composeApp/.../ui/HealthSection.kt` (`VetVisitPanel`) |
| **Seasons in the room:** Halloween (Oct 24 to 31: orange-and-black bunting, a jack-o'-lantern lit at night), Christmas (Dec 15 to Jan 6: string lights, a little tree with baubles) and Valentine's (Feb 12 to 14: a heart garland), drawn in the room's own pixels from the phone's calendar; no update needed | `core/.../sprite/Season.kt`, `Room.kt` |
| **Birthdays and gotcha days:** on the pet's birthday (from the birthday the owner typed) and on the anniversary of the day they came home, the room gets a bunting card with confetti, the pet says so, and a reminder comes at 9:00. Leap-day birthdays fall on Feb 28 | `core/.../core/Occasions.kt`, `ReminderPlanner.occasions` |
| **Walks:** "Start a walk" on the Care panel times a walk with the app open: the pixel pet trots along, the phone counts steps (Android step counter, iOS pedometer; permission asked once), and ending it records the walk (minutes, steps, rough km) and logs the pet's walk task as care. "Walks this week" on the Care panel. No location is used; nothing leaves the phone | `composeApp/.../ui/WalkScreen.kt`, `core/.../core/Model.kt` (`Walk`), `CareStats.walkWeek` |
| **Weather in the room:** a chip under the name plate ("31° · sunny") and, when it matters, the pet says so: hot pavement (walk early or after sunset), rain (a towel after), thunder (stay close), chilly, or perfect walk weather. Free data from Open-Meteo (CC BY 4.0), fetched hourly for the owner's ~1 km map area only | `core/.../core/Weather.kt`, `composeApp/.../WeatherModel.kt` |
| **Units that follow the country:** km and kg for most of the world, miles and pounds on a US phone (miles with kg in the UK), or either by choice in Settings. Walks, map distances, the lost-pet radius and weigh-ins all follow; weights stay in grams underneath. Outside the Philippines the health notes cite the WSAVA guidelines instead of Philippine practice, the Philippine law card and the Rabies Awareness Month note stay home | `core/.../core/Units.kt`, `composeApp/.../ui/SettingsScreen.kt` |
| **Vaccine schedules by country:** the first-year plan and the usual items follow the phone's country: the Philippine plan at home; AAHA/AAFP in the US and Canada (DAPP or FVRCP to 16 weeks, rabies from 12 weeks, leptospirosis, FeLV for kittens, monthly heartworm); the UK and Ireland two-dose course with leptospirosis and no rabies; C5/F3 in rabies-free Australia and New Zealand; the WSAVA core schedule with international names everywhere else. A plan made on one phone keeps its schedule on a household phone in another country | `core/.../core/HealthPlan.kt` (`Region`) |
| **Moments for pals:** one photo of the day with a caption, for your pals and nobody else; it replaces yesterday's and is gone after two days, with no likes or comments. Shrunk on the phone (location data dropped), chosen from the album or the camera roll | `supabase/migrations/0014_pal_moments.sql`, `core/.../map/Pals.kt`, `composeApp/.../ui/PalsScreen.kt` |
| **Monthly challenge:** one small shared goal a month, read from what's already logged (care on 25 days in October, 40 km of walks in September, 8 album photos in February; cats get care or photo months instead of walking ones), with a bar on the Care panel and a cheer when it's done. Nothing to join, nothing stored | `core/.../core/Challenge.kt`, `composeApp/.../ui/ProgressSection.kt` |
| **Pals:** a circle of up to 20 friends by code, not a feed: pals see each other's pixel pets (never photos, places or care), a pal's pet visits your pet's room each day, and a pal can send your pet a treat, a pat or a ball that shows as a speech bubble with hearts. Unpal any time | `composeApp/.../ui/PalsScreen.kt`, `core/.../map/Pals.kt`, `supabase/migrations/0012_pals.sql` |
| **Pet ID card:** a page per pet that a collar tag's QR code opens (pixel twin, name, a note for the finder, microchip), with a message box that reaches the owner in the app; the owner's number is never shown. The QR is drawn by the app's own encoder (`core/.../core/QrCode.kt`, byte mode, level M, verified against qrcode-generator) and by the web page for printing | `composeApp/.../ui/PetCardSection.kt`, `core/.../map/PetCard.kt`, `supabase/migrations/0011_pet_cards.sql`, `web/card.template.html` |
| **Album:** real photos kept with each pet (captions, full-screen viewer), on the phone and in backups, never uploaded; photo metadata is dropped. **In loving memory:** when a pet passes away its reminders and needs stop and its room goes quiet (a starry night, the pet resting), while the album, care history and weight log stay; undoable | `composeApp/.../ui/AlbumPanel.kt`, `core/.../core/Model.kt` (`AlbumPhoto`, `Pet.rememberedDay`) |

The map is off until you add your server and sign-in settings (`docs/MAP_SETUP.md`); until then it shows "coming soon". Why it's built this way: "approximate distance" features leak exact locations (dating apps were pinpointed to <5 m), so no coordinate ever leaves the phone. See `docs/MAP_SAFETY.md`.

## Phase 0: the web Pet Maker
`web/` is a phone-friendly page where anyone uploads a pet photo and watches it become a living pixel pet, using the same Kotlin engine as the apps (compiled to JavaScript) plus an in-browser pet detector (U²-Net-p). Photos never leave the device. It has:
- an **"Is that your pet?"** tally, kept on the device you're testing with. Hand your phone to owners, then read the result.
- GIF and before/after card sharing.
- a waitlist button, which appears once you set `WAITLIST_URL` in `page.template.html` (e.g. a Google Form).

Build: see the top of `web/build_web.py`.

## The look

"Soft pixel": crisp pixel pets inside soft, warm, rounded chrome. One design system in
`composeApp/.../ui/Theme.kt` (palette, shapes, type) and `Components.kt` (cards, pills, chips, hearts,
confetti, spring motion) drives every screen on both platforms:

- Warm cream by day, deep plum by night; one coral accent for what matters (Done, the pet's mood),
  leaf green for good news, lavender for sleep. Every text colour meets WCAG AA on its surface.
- The pet's stage has a sky that follows the real time of day (peach dawn, soft blue day,
  apricot-to-lavender dusk, starry indigo through the owner's set bedtime), dimmed in dark mode.
- Its own chrome, the "toy box" (`composeApp/.../ui/Toy.kt`), not stock Material: every button,
  chip, tab and card is a chunky key with a hard lip under it in a darker shade of its own colour
  that squashes flat when pressed (the way Duolingo's and Pou's buttons do), panels are outlined
  stickers, and care meters are segmented pixel bars. Each kind of care has a candy colour (feeding
  is butter, water is sky, litter is mint) used for its icon, its meter and its Done key.
- Care feels rewarding, never nagging: pixel hearts fill as care is logged, Done sends up a burst of
  hearts with a haptic, the pet reacts on stage, milestones get short confetti, and the week shows
  days cared for rather than a streak that can break. Mood copy is never accusatory.
- Two typefaces of its own (both Open Font License, in `docs/fonts`): Pixelify Sans, a pixel
  display face, for names and screen titles so the type matches the pet; Nunito, soft and
  readable, for everything else. Generous 16dp gutters,
  48dp+ touch targets; every control keeps a spoken label for screen readers.
- The pet lives in a room (`core/.../sprite/Room.kt`), drawn in its own pixels: papered walls, a
  window onto the sky of the hour, a shelf with a plant and a framed paw, a clock, a lamp that comes
  on at night, a rug, a cushion and a bowl. The same room backs the home card, the maker and the Studio.
- One icon style, the pet's own (`core/.../sprite/PixelIcons.kt`): 12x12 pixel icons tinted by the
  UI, never emoji (they render differently on every phone and read as placeholders).
- It plays like a pet game, not a form: the pet's room fills the whole screen (home *is* the room),
  and everything else is a HUD laid over it, the way Pou and My Talking Tom do it. A name plate with
  hearts and age, a speech bubble for the mood, one tappable need meter per care task (tap it to log
  the care, the pet reacts, Undo sits there for a few seconds) and five keys (Care, Health,
  Wardrobe, Share, More) that raise panels over the room. The Studio and the maker keep their own
  pages; everything else is a panel.
- The **Pet Studio**: 70+ ways to draw the pet (head, eyes, eye colour, shine, brows, nose, mouth,
  ears, body, tail, coat pattern, chest, blush, whiskers, collar, and fur, marking and collar
  colours). Choices travel in the look code, so the household and the map draw the same pet.

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
- The Android widget is alive (its idle animation plays on the home screen through a `ViewFlipper`, the one thing launchers animate on their own); the iOS widget shows the mood's still pose, as iOS doesn't allow animated widgets. Both draw the sky of the hour behind the pet.
- Body markings (spots, socks) aren't copied yet; the chest and paws use the pet's lighter tone when it has one. A hand-drawn body from the owner's photos is a natural Pro upgrade, and a fit for your pixel art commissions.
- Likeness comes from colours and markings, not a pixelated copy of the photo. Pasting the real (pixelated) face on a drawn body was tried and dropped: the two styles clashed and the head looked out of proportion. Fine stripes (tabby) are not reproduced yet.
- A widget shows one pet: the one you pick in its settings, or whichever needs attention most.
- Adaptive timing learns from the last 4 weeks and needs 3 completions near a time before moving it.
- Daylight-saving shifts may move a reminder by an hour on the change day (not an issue in the Philippines).
