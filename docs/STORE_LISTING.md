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

PRIVATE BY DESIGN
• No account. No ads. No tracking.
• Your photo never leaves your phone. The sprite is made on the device.

Your first pet is free. PawPixel Pro (coming soon) adds more pets and hand-finished sprites by a pixel artist.

Made in Naga City, Philippines.

**Category:** Lifestyle · **Tags:** Pets, Widgets
**Content rating:** Everyone (no user-generated content, no ads, no purchases in v1)
**Data safety form:** PawPixel itself collects nothing, but the Android build uses Google ML Kit (pet cut-out), and Google says ML Kit collects diagnostic data. Per [Google's ML Kit disclosure guide](https://developers.google.com/ml-kit/android-data-disclosure), declare:
- **Device or other IDs** (a per-installation ID): collected, not shared, for analytics.
- **App info and performance: diagnostics** (device model, OS version, app version, latency, error codes): collected, not shared, for analytics.
- Encrypted in transit: yes. Users can request deletion: no (not tied to an account).
- Photos, pet data and care history: **not collected** (they never leave the device).
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
**App Privacy:** Data Not Collected (the iOS build uses Apple's Vision framework on-device, with no third-party SDKs). Re-check if you add any SDK such as RevenueCat or analytics.

## Screenshots to capture (from the running app)
Phone screenshots must show the real app, so capture these once it builds. Use 6.7" iPhone (1290×2796) and any 1080×1920+ Android phone:
1. **Pet screen, happy** with hearts after a tap. Caption: "Your real pet, in pixels"
2. **Home screen with the widget** showing a hungry pet and the Done button. Caption: "Lives on your home screen"
3. **Sprite maker** with the photo → sprite preview. Caption: "Made from your photo, on your phone"
4. **Care list** with Done buttons and "Adjusted to your routine". Caption: "Mood follows real care"
5. **Eating reaction** right after tapping Feed Done. Caption: "Tap Done, watch them eat"
6. **Share sheet with the GIF.** Caption: "Share their pixel twin"

## Launch checklist links
- Privacy policy: publish `docs/PRIVACY.md`, then put its URL in both stores and in `SettingsScreen.kt`.
- Support email: set `SUPPORT_EMAIL` in `SettingsScreen.kt`.
- Google Play: a new personal developer account must run a closed test with testers for 14 days before production.
