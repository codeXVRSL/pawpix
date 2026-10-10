# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

PawPixel turns a photo of a real pet (dog, cat or rabbit) into a pixel-art chibi that lives in a room on the phone and on the home-screen widget; its mood follows the real care the owner logs. One Kotlin codebase (Compose Multiplatform) for Android and iOS, plus a native widget on each platform and an optional Supabase backend.

## Commands

```
./gradlew :core:jvmTest                                    # all core unit tests (the main fast check)
./gradlew :core:jvmTest --tests 'com.pawpixel.RabbitTest'   # one test class
./gradlew :core:jvmTest --tests 'com.pawpixel.RabbitTest.heatIsAWarningForARabbitFromTheHighTwenties'
./gradlew :composeApp:assembleDebug                        # Android debug APK
./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest && scripts/android-e2e.sh   # e2e on an emulator
./gradlew :core:spriteLab --args="photos/ out --species=cat"   # sprites, poses, GIFs and cards for a folder of photos
cd iosApp && xcodegen                                      # generate the Xcode project (iosApp/project.yml); Mac only
python3 supabase/tests/map_test.py                         # backend privacy rules (needs a running `supabase start` and SUPABASE_URL / ANON_KEY / SERVICE_KEY)
python3 supabase/tests/household_test.py
```

There is no separate linter; the Kotlin compiler and the tests are the checks. CI (`.github/workflows/build.yml`, every push) runs six jobs: core tests, Supabase backend tests (migrations + Python tests + `:core:mapLive`), debug APK, signed release bundle, Android emulator end-to-end, iOS simulator build + UI test. A new push cancels the previous run on the same branch. Results (APK, screenshots, logs) are pushed to `ci-results/<branch>-{android,ios,apk,backend}` branches; read them with `git fetch origin <branch> && git show FETCH_HEAD:<file>`.

## Architecture

**`core/`** is pure Kotlin (common, JVM, iOS, JS) with all logic and no UI; everything testable lives here.
- `core/` package: the state model (`Model.kt`: `AppState`, `Pet`, `CareTask`, `TaskKind`, `Species`), `StateOps` (pure state transitions), `StateCodec` (JSON save format), `CareEngine`/`MoodEngine` (what's due, the pet's mood), `ReminderPlanner` (notifications), `HealthPlan` (per-country vaccine schedules, dose series counted from the birthday), `WidgetSnapshot` (precomputed widget timelines), `HouseholdSync`, `Units` (km/miles, kg/lb by country), `Weather`, `Challenge`, `Occasions`.
- `sprite/`: the photo-to-pet pipeline (`SpritePipeline` cut-out and palette, `PetLook` colours/markings), `Chibi` (draws every pet procedurally from look + species + `PetStyle` on a 41x34 canvas; rabbits get 4 extra rows of headroom, so use the image's own height, never `Chibi.HEIGHT`), `Animator`/`PetBrain` (frames and wandering), `Room` (the pixel room), `Accessory`, PNG/GIF encoders.
- `map/`: HTTP clients for the Supabase backend (pet map, gatherings, Lost and Found, pals/moments, households, pet ID cards) and the vector-tile (MVT) decoder for the cartoon street map.
- `i18n/`: translation by English source string. Every user-facing string is written as `tr("English text {0}", arg)` and looked up in `Fil*.kt`, `Es*.kt`, `Pt*.kt` maps. `TranslationTest` (jvmTest) scans `composeApp/src` and `core/src/commonMain` for `tr("...")` calls and fails the build if any string lacks Filipino, Spanish or Portuguese, or if placeholders differ. **Any new or changed `tr()` string needs entries in all three languages.** Stored default names (e.g. a task titled "Feed") stay English and are shown through `trName()`.

**`composeApp/`**: `commonMain` is the shared Compose UI and `PawRepository` (the single owner of state: loads/saves `state.json`, runs `StateOps`, then `publish()` writes `widget.json` + mood PNGs and reschedules reminders). `ui/App.kt` holds the screen stack (`Screen` sealed class) and transitions. `Platform.kt` is the expect-style interface that `androidMain` (`AndroidPlatform`, Glance widget, alarms, ML Kit) and `iosMain` (`IosPlatform`, bridging to Swift through `SwiftHost`) implement. `DemoMapServer` fakes the backend in debug builds when no server is configured. Map/server settings are generated at build time into `MapBuildConfig` from env vars or `pawpixel.properties` (`PAWPIXEL_SUPABASE_URL`, `PAWPIXEL_SUPABASE_ANON_KEY`, ...).

**Data flow:**
```
UI (Compose) → PawRepository → StateOps (pure) → state.json
                     └→ publish(): widget.json + mood PNGs → widgets
                                   ReminderPlanner → AlarmManager / UNUserNotificationCenter
iOS widget "Done" → pending_done.json → ingested by the app on next open
Android widget "Done" → ActionCallback → PawRepository.complete()
```
Widgets never run the mood engine; they show the right precomputed entry for the current time.

**`iosApp/`**: SwiftUI host (`SwiftHost.swift`: photos, Vision cut-out and species classifier, notifications, share sheet, Sign in with Apple), the WidgetKit extension (`PetWidget/`, which mirrors `Sky.phase` in `WidgetSky.swift`), and UI tests. Project generated by XcodeGen from `project.yml`; native strings live in `*.lproj` folders.

**`supabase/`**: SQL migrations (numbered, applied in order; never edit an applied one, add a new number) with row-level security and `security definer` RPCs for every write, plus Python tests that play several owners through each privacy rule. `docs/MAP_SAFETY.md` and `docs/PRIVACY.md` describe the rules the migrations enforce.

**`web/`**: the static web Pet Maker and the public Lost-pet / pet-card pages, using `core` compiled to JS (`web/build_web.py`).

## Conventions worth knowing

- Logic changes go in `core` with a commonTest; UI code calls into `core` rather than re-deriving rules.
- Species are handled explicitly (`DOG`, `CAT`, `RABBIT`, `OTHER`); search for `Species.` when adding behaviour so rabbits and "other" pets aren't treated as dogs. Older clients decode unknown enum names to a default (`enumOr`), and the server's species checks list every species.
- Health items match across regions by `HealthPlan.Item.key`, and one item title maps to one schedule everywhere, so household phones in different countries agree on due dates.
- Release checklist and store material: README "Before you release", `docs/STORE_LISTING.md`, `docs/MAP_SETUP.md` (`scripts/setup-map-server.sh` sets up the real server and CI secrets).
