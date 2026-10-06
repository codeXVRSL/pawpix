# Store listing pack

Character counts are checked against each store's limit. Feature graphic: `docs/brand/feature-graphic-1024x500.png` (made with the real sprite engine). App icon: `docs/brand/icon-512.png`.

## Google Play

**App name** (26/30)
PawPixel: Pixel Pet Widget

**Short description, English** (79/80)
Turn your real pet into a pixel pet on your home screen. Its mood follows care.

**Short description, Filipino** (79/80)
Gawing pixel pet ang alaga mo sa home screen. Sumusunod ang mood sa pag-aalaga.

**Full description**

Meet your pet's pixel twin.

Take a photo of your dog, cat or any pet, and PawPixel turns it into a pixel-art sprite that looks like *your* pet: its fur colours and face markings on a cute pixel character, dog or cat, pointy or floppy ears. Then it moves in. Your pixel pet lives on your home screen, breathes, blinks, wanders around, and reacts to the real care you give.

HOW IT WORKS
• Snap or pick a photo. PawPixel cuts your pet out and draws it in pixels, right on your phone.
• Add care tasks: feeding, walks, fresh water, medicine, grooming, litter.
• Your pixel pet's mood follows real life. Skip dinner and it gets hungry. Miss the walk and it paces. Do it all and it's happy.
• Tap Done on the widget or the reminder, and watch them eat, do zoomies or shake off after a bath.

MADE FOR REAL ROUTINES
• Reminders learn your routine and shift toward when you actually feed or walk (up to 2 hours).
• Medicine gets a follow-up nudge if it's missed.
• Weekly tasks like grooming, and up to four times a day for meals.

SHARE THEM
• Make a looping GIF of your pet being themselves.
• Share a before/after card: the real photo next to the pixel version.
• Pals: a circle of up to 20 friends whose pixel pets visit your pet's room and send treats. No feed, no followers.

IF THEY EVER GO MISSING
• One tap raises a Lost alert: owners within 15 km see the photos and can report a sighting; you close it with "Safe home".
• A Pet ID card for the collar: scan the QR and reach the owner without a phone number on the tag.
• Noise-night reminders before New Year's Eve, the night most pets run away.

LITTLE THINGS, EVERY DAY
• The weather outside in the room: hot pavement, rain and thunder, in your pet's own words.
• Time a walk and your pixel pet trots along while your phone counts the steps.
• Birthdays, gotcha days and seasons decorate the room.

PRIVATE BY DESIGN
• No account. No ads. No tracking.
• Your photo never leaves your phone. The sprite is made on the device.

Your first pet is free. PawPixel Pro (coming soon) adds more pets and hand-finished sprites by a pixel artist.

Made in Naga City, Philippines.

**Category:** Lifestyle · **Tags:** Pets, Widgets
**Content rating:** Everyone, with **user interaction** (the optional pet map lets users see other users' pet names and meet at events; there's reporting and blocking). The map is 18+ inside the app.
**Data safety form:** PawPixel itself collects nothing, but the Android build uses Google ML Kit (pet cut-out), and Google says ML Kit collects diagnostic data. Per [Google's ML Kit disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure), declare:
- **Device or other IDs** (a per-installation ID): collected, not shared, for analytics.
- **App info and performance: diagnostics** (device model, OS version, app version, latency, error codes): collected, not shared, for analytics.
- Encrypted in transit: yes. Users can request deletion: no (not tied to an account).
- Photos, pet data and care history: **not collected** (they never leave the device).
- **If the pet map is switched on in your build**, also declare (all optional for the user, tied to their account, deletable in the app):
  - **Location → Approximate location**: collected, shared with other users (as a ~1 km square), for app functionality.
  - **Personal info → Email address** and **User IDs**: collected (from Google sign-in), for account management.
  - **Personal info → Other info** (pet names) and **App activity → Other user-generated content** (RSVPs, reports): collected, shared with other users (names only), for app functionality.
  - Users can request deletion: **yes** (in the app).
- **If household sharing is switched on** (same server and sign-in as the map), also declare (optional, tied to the account, deletable in the app):
  - **Personal info → Name** (the name a member shows their household): collected, shared with other users (household members), for app functionality.
  - **Personal info → Other info** (shared pets' names, birthday, pixel look) and **App activity → Other actions** (the care log: when a task was done or undone, and by whom; care and health schedules): collected, shared with other users (household members only), for app functionality.
  - Photos and location: still **not collected** (households never receive them).
- **If Lost and Found, Pet ID cards and Pals are switched on** (same server and sign-in), also declare (all optional, tied to the account, deletable in the app):
  - **Photos and videos → Photos**: collected, shared with other users, for app functionality. Only the album photos the owner chooses for a lost-pet alert (metadata stripped); nothing else ever uploads a photo.
  - **Location → Approximate location**: a lost pet's last-seen spot (chosen by the owner) and a sighting's spot; shared with other users, for app functionality.
  - **Personal info → Other info** (pet names, the ID card's note and microchip number) and **Messages → Other in-app messages** (finders' messages, sightings, treats between pals): collected, shared with other users, for app functionality.
- **Walks:** the step counter is read on the device while a walk is timed (`ACTIVITY_RECOGNITION` on Android 10+, Motion on iOS); the count stays on the phone, **not collected**. Declare the permission's purpose in the Play Console as "fitness/activity tracking for the user's pet walks".
- **Weather:** once the owner has a map area, the centre of that ~1 km square is sent to Open-Meteo (open-meteo.com) for the weather; no account or key, nothing identifying.
Re-check the guide before submitting; it's Google's list and can change.

## Apple App Store

**Name** (26/30)
PawPixel: Pixel Pet Widget

**Subtitle** (24/30)
Your real pet, in pixels

**Promotional text** (145/170)
Snap a photo of your dog or cat and meet their pixel twin. It lives on your home screen, gets hungry at mealtime and cheers up when you tap Done.

**Keywords** (94/100, no spaces after commas, no trademarks)
virtual pet,pet care,reminder,feed,walk,pixel art,dog,cat,puppy,kitten,sprite,8bit,cute,mascot

**Description:** use the Google Play full description above (Apple doesn't render bullets specially; plain line breaks are fine).

**Category:** Lifestyle (secondary: Entertainment) · **Age rating:** 4+
**App Privacy:** without the pet map: Data Not Collected (the iOS build uses Apple's Vision framework on-device, with no third-party SDKs). **With the pet map switched on**, declare as "Data Linked to You", not used for tracking: **Coarse Location**, **User ID**, **Email Address** (if the user shares it via Sign in with Apple), and **Other User Content** (pet names, RSVPs, reports), all for App Functionality. **With household sharing**, add **Name** (the name shown to household members) and **Other User Content** (shared pets, their care schedules and the care log of who did what), linked to the user, for App Functionality. Re-check if you add any SDK such as RevenueCat or analytics.

## Screenshots (captured from the real app by CI)
Ready to upload, taken from the end-to-end runs (only the photo picker is stubbed):
- **Google Play:** `docs/store-screenshots/android/` (1080×2160, Android 14 emulator)
- **App Store:** `docs/store-screenshots/ios/` (1320×2868, iPhone 16 Pro Max = the required 6.9" size)

| # | Screen | Suggested caption |
|---|---|---|
| 1 | Make your pet (photo → pixel pet preview) | "Made from your photo, on your phone" |
| 2 | Pet screen after care (hearts, eating) | "Tap Done, watch them eat" |
| 3 | Home list | "Mood follows real care" |
| 4 | Home-screen widget (Android) / care task editor | "Lives on your home screen" / "Feeding, walks, medicine" |
| 5 | Care task editor (Android) | "Reminders that learn your routine" |

Every CI run refreshes the full set of screens on the `ci-results/android` and `ci-results/ios` branches. For daytime (awake) shots, use a run made in daytime UTC, or pick the ones you like best from those branches.

## Launch checklist links
- Privacy policy: publish `docs/PRIVACY.md`, then put its URL in both stores and in `SettingsScreen.kt`.
- Support email: set `SUPPORT_EMAIL` in `SettingsScreen.kt`.
- Google Play: a new personal developer account must run a closed test with testers for 14 days before production.
