# PawPixel Privacy Policy (draft)

_Last updated: [date]. Replace the bracketed parts and publish this page before release._

**Short version:** PawPixel works on your phone. Your photos are never uploaded. Only if you choose to join the **pet map** or **share pets with your household** do a few things go to PawPixel's server: for the map, your pixel pets, their names and your rough area (about 1 km); for a household, the pets you share, their care and who did it; and in both cases the Google or Apple account you sign in with.

## What PawPixel stores, and where
- **Your pet's album:** photos you add to a pet's album are re-encoded on your phone (which drops their location and other metadata) and kept only on your phone and in backup files you save yourself. They are never uploaded.
- **Your pet's photo:** used on your phone to make the pixel sprite. PawPixel keeps only a small (256×256) crop so it can make your before/after card and remake the sprite. The original stays in your photo library.
- **Your pets, care tasks and when you did them:** stored in the app's private storage on your phone, and used to set your pet's mood and your reminder times.
- **Widget data:** a copy of your pet's mood and sprite, in storage shared only with PawPixel's own home-screen widget.
- **Health records you add:** vaccine, deworming, tick & flea and vet-visit dates you log, kept on your phone like your other care tasks.
- **Backups:** on Android, your phone's Google backup includes PawPixel (pets, tasks, history, sprites), so a new phone gets them back; the pet-map sign-in is left out. If you tap **Save backup file**, PawPixel makes a file and opens your share sheet; it goes only where you choose to save it.

## The pet map (optional)
If you join the pet map, and only then:
- **Sign-in:** you sign in with Google (Android) or Apple (iPhone). Our map server (Supabase, hosted in [region]) stores your account ID and the email address your provider shares (Apple lets you hide it).
- **Your pixel pets:** each pet you choose is sent as its name, dog or cat, ear shape and a short "look code" (up to three fur colours and where they sit on the face). Never a photo.
- **Your area:** your phone asks for your *approximate* location, turns it into a square about 1 km wide, and sends only that square. Your exact location never leaves your phone and is not stored. Your area is refreshed when you open the map and removed after 14 days if you don't.
- **Who sees what:** other map members see pets, names and squares, only for squares with 3 or more owners, and never who owns which pet. Gathering venues are shown only to people who said they're going.
- **Gatherings, blocks and reports:** we store your RSVPs, the owners you block, and reports you send (with the reported pet), so we can keep the map safe. Moderators (us) read reports and can remove people who break the rules.
- **Legal basis:** your consent, which you give on the join screen and can withdraw any time.
- **Leaving and deleting:** "Leave the map" removes your pets and area. "Delete my map account" (or Settings → Delete all my data) deletes your account and everything above from the server immediately.
- The map is for people 18 and over.

## Lost and Found (optional)
If your pet goes missing and you raise an alert, and only then:
- **What is sent:** the pet's name, dog or cat, its look code, the note you write, the album photos you choose (up to 3, shrunk, with photo metadata such as GPS removed), and the spot where the pet was last seen (which you pick on the map). This is the only time PawPixel uploads a photo.
- **Who sees it:** signed-in PawPixel owners within about 15 km of the spot, and anyone who opens the alert's share link. Never your name, your account or your home.
- **Sightings:** someone who saw your pet sends a spot (their approximate location), a note and perhaps a photo. You see the sighting; they are not identified to you, and you are not identified to them.
- **How long:** an alert leaves the map when you mark the pet safe home or remove it, and in any case after 60 days. The share page shows a found pet as found for 30 days, then nothing. "Delete my map account" deletes your alerts and sightings too.
- Raising an alert needs a sign-in (Google or Apple) but not a place on the map.

## Pet ID card (optional)
If you make an ID card for a pet, and only then: the pet's name, dog or cat, look code, your note and (if you add it) the microchip number are stored on our server and shown to anyone who opens the card's link, usually by scanning the QR code on the collar tag. Not your name, your account or your number. A finder can send you a message (and a contact line if they choose) which you read in the app. "Remove card" deletes the card and its messages; deleting your map account does too.

## Pals (optional)
If you add a pal by code, and only then: you and that person see each other's pixel pets (name, dog or cat, look code), the treats you send each other's pets, and nothing else: no photos, no location, no care history, no name or account. Your pal code is yours alone to give out. "Unpal" ends it from either side; deleting your map account deletes your code, pals and treats.

## Sharing with your household (optional)
If you start or join a household (for example you and your partner both caring for your dog), and only then:
- **Sign-in:** the same Google or Apple sign-in as the pet map.
- **What the server stores and your household sees:** the name you choose to show them (e.g. "Jamaica"); the pets you choose to share: name, dog or cat, ear shape, outfit, birthday if you gave one, where you tapped its eyes, and a short pixel "look code" (up to three fur colours and where they sit on the face), which is all another phone needs to draw the same pixel pet; their care tasks and health schedules; and a care log: each Done (when, and who), and each Undo (when, and who). The names of the people in the household.
- **Never shared:** photos (not the pet's photo, not its face crop, not vaccination-card photos, not the album), your location, your reminder settings, your weigh-ins.
- **Walks you host:** if you propose a walk on the map, its title, time, your note, the meeting place you tapped (a public spot, never your home) and the account you signed in with go to the server, and the title, time, area and note are shown to other owners once a moderator approves it. The exact meeting place is shown only to people who say they're going.
- **Who can see it:** only the people in your household, who joined with an invite code (8 characters, valid for 7 days). Our server enforces this; a household has at most 8 people.
- **How long:** until you leave, the household ends, or you delete your account. Undone records are kept in the log (marked undone) so every phone learns about the undo; each task keeps its latest 400 records.
- **Leaving and deleting:** "Leave" removes you from the household; shared pets stay on your phone. Care records you logged stay with the household. The person who started it can remove people, and "Stop sharing for everyone" deletes the household and everything the server kept for it (each phone keeps its own copy). Deleting your account removes you and marks your records as by an unknown person; a household with nobody left is deleted.

## What PawPixel does not do
- No ads or tracking. Outside the optional pet map and household sharing, PawPixel has no account and receives none of your data.
- No precise location. The optional map uses approximate location only, turned into a ~1 km square on your phone.
- Photos are processed on your device by your phone's built-in tools: Google ML Kit on Android, Apple Vision on iPhone. Your photo is not uploaded.
- **On Android only:** Google ML Kit sends Google anonymous diagnostic data (device model, OS and app version, performance and error information, and a per-installation ID) so Google can maintain the feature. It does not include your photos. See Google's ML Kit terms and privacy information.

## Sharing
When you tap **Share before/after**, PawPixel creates an image and opens your phone's share sheet. What happens next is up to you and the app you share to.

## Deleting your data
Settings → **Delete all my data** removes everything immediately, including your PawPixel account (pet map and household) if you signed in. Uninstalling the app removes everything on the phone; if you joined the map, delete your map account in the app first (or email us and we'll delete it).

## Children
PawPixel is a general-audience app. The pet map is only for people 18 and over; we don't knowingly collect data from children, and we delete any map account we learn belongs to a child.

## Contact
[Your name], Naga City, Philippines · [support email]
Data protection officer: [your name] · [support email]
Under the Philippine Data Privacy Act of 2012 (RA 10173), you can contact us about your data at any time: to see it, correct it or have it deleted.
