# Pet gatherings map: safety design

Status: **built.** Backend in `supabase/migrations/` (0001 + 0002), app screens in `composeApp/.../ui/PetMapScreen.kt`. Every CI run plays owners through each rule below on a real Supabase stack (`supabase/tests/map_test.py`) and runs the Android app against it. It switches on once you fill in the settings in `docs/MAP_SETUP.md`. Pilot scope: Naga, gatherings you create and approve.

Pets on the map are **pixel pets only**: the app sends a short look code (colours and markings), never a photo. Owner ids are never returned to other users; blocking and reporting work by pet.

## The threat
"Approximate distance" features leak exact locations. Researchers pinpointed dating-app users (Grindr, Hornet, Bumble, Hinge) to within 2–5 m by faking their own position and measuring distances from several points. Hornet's randomized distances were defeated too, partly because the *order* of results leaked distance. Pet theft mostly happens at owners' homes, and a map of real pet photos near where people live is exactly what a thief would want.

## The rules the design enforces
1. **No coordinate leaves the phone.** `LocationGrid.snap()` turns GPS into a ~1 km cell on the device, and only the cell id and cell centre are uploaded. Everyone in a cell shares one point, so there is nothing to trilaterate.
2. **k-anonymity (k = 3).** A cell, and the pets in it, only appear once 3 or more opted-in owners share it (`nearby_cells`, `pets_in_cell`). Blocked users don't count toward the 3.
3. **No distance sorting.** Pets in a cell come back in random order, and distances are shown only as coarse labels ("Nearby", "~3 km").
4. **Meet at public places only.** Gatherings are approved by a moderator. The venue is hidden until you RSVP (`gathering_details`).
5. **Blocking works both ways**, and reports are filed in-app (categories include child safety).
6. **18+ only.** The app records a yes/no adult confirmation, not a birthdate. A birthdate would be sensitive personal information under the Data Privacy Act.
7. **Leaving is one tap** (`leave_map`), and deleting the account cascades through everything.
8. Presence **expires after 14 days** without opening the map.

## Before launching the map
- [ ] Explicit consent screen (DPA: freely given, specific, informed)
- [ ] Appoint a Data Protection Officer (you) with a dedicated email; know the 72-hour breach rule
- [ ] Decide on NPC registration (required for automated profiling or 1,000+ people's sensitive data)
- [ ] Google Play: UGC policy (terms, report, block, moderation), Child Safety Standards page and contact, account deletion in the app and on the web
- [ ] Apple: guideline 1.2 (filter, report, block, published contact), in-app account deletion
- [ ] Strip EXIF/GPS from any uploaded real photos on the device
- [ ] Moderation plan: who checks reports, and how fast
- [ ] Start with one founder-hosted monthly walk in Naga, not a city-wide map

## Limits that make scanning expensive (migration 0017)
An area shows only with three or more owners in it. Someone with puppet accounts could in theory move them into a square to tip a lone owner over the line, so an account can change its area at most three times a day, a banned account keeps its ban even if it leaves and re-joins, RSVPs and walk proposals only go through the server functions that check capacity and bans, pal codes can be replaced and wrong guesses are limited to ten an hour, and a pal who removed you can't be added back with their old code.
