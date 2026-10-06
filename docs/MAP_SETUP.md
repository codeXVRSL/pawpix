# Switching on the pet map and household sharing

## The short way (one command)

```
SUPABASE_ACCESS_TOKEN=sbp_...  scripts/setup-map-server.sh --repo codeXVRSL/pawpix
```

The token comes from [supabase.com/dashboard/account/tokens](https://supabase.com/dashboard/account/tokens).
The script creates the Supabase project (Singapore), applies every migration, makes a test account
for debug builds, writes `pawpixel.properties` for local builds, and stores the values as GitHub
secrets. The next CI run's **debug APK** then has the map switched on: it signs in with the test
account (no Google needed), so you can join, see the pixel pets around you, RSVP and host walks
right away. Walks owners propose wait in the dashboard for your approval (section 6).

Google sign-in (section 2) is the one thing left by hand for store builds. The street map works
out of the box (section 4: free CARTO tiles, repainted as a cartoon; a free key keeps them loading).

Until a server exists, debug APKs offer **"Try the demo map"** on the map screen: pretend owners,
pets and walks around Naga, kept on the phone, so the whole flow can be tried without any setup.

## The long way, step by step

The map and household sharing use the same server and sign-in. Both are built and tested (against a real Supabase in CI), but a store build needs **your**
accounts. Until these settings are filled in, the app shows "The pet map is coming soon", and
everything else works as before.

You'll set up four things: a Supabase project, Google sign-in (Android), Sign in with Apple (iOS)
and a map-tile key. Then you put six values into the build.

## 1. Supabase (the map server)
1. Create a project at [supabase.com](https://supabase.com). Pick the **Singapore** region (closest to Naga).
2. Apply the database: install the [Supabase CLI](https://supabase.com/docs/guides/cli), then from the repo:
   ```
   supabase link --project-ref <your-project-ref>
   supabase db push          # applies every file in supabase/migrations (map, name filter, households)
   ```
   Always run `supabase db push` **before** releasing an app update that uses a new migration (the
   app sends the new columns, e.g. a pet's outfit; an older database would refuse them).
3. Note **Project URL** and the **anon / publishable key** (Project Settings → API).
   Never put the service/secret key in the app.

## 2. Google sign-in (Android)
1. In [Google Cloud Console](https://console.cloud.google.com) → APIs & Services → Credentials, create:
   - an **OAuth client ID, type "Web application"**. Its client ID is `PAWPIXEL_GOOGLE_WEB_CLIENT_ID`.
   - an **OAuth client ID, type "Android"** for package `com.pawpixel.app` with your app's **SHA-1**
     (both the upload key and Play's app-signing key: Play Console → App integrity). For **debug
     builds** (CI's APK and Android Studio) add a third Android client with the checked-in debug
     keystore's SHA-1: `37:5B:E3:3D:DA:5C:CC:BF:75:2D:09:C4:F4:9D:D5:AC:99:75:47:4B`
     (`composeApp/debug.keystore`, password `android`; a debug key, not a secret).
2. In Supabase → Authentication → Sign In / Providers → **Google**: enable it, paste the *web* client ID
   (and secret). Leave "Skip nonce checks" **off**: the app sends a nonce.

## 3. Sign in with Apple (iOS)
1. In your Apple Developer account, enable **Sign in with Apple** for the App ID `com.pawpixel.app`
   (the entitlement is already in `iosApp/iosApp/iosApp.entitlements`).
2. In Supabase → Authentication → Providers → **Apple**: enable it and add `com.pawpixel.app` as a
   client ID (native sign-in needs no Services ID or secret).

## 4. Map tiles (free)
The map is a real street map of the world (OpenStreetMap data) that the app repaints as its own
cartoon: water, parks, roads and buildings come out in PawPixel's candy colours as chunky pixels,
with the street names drawn crisp on top (`core/.../map/MapStyle.kt`). No setting is needed to get
it: by default the app loads **CARTO's Voyager** raster tiles (`basemaps.cartocdn.com`), which are
free with the attribution the map already shows ("© OpenStreetMap contributors © CARTO").

CARTO asks for a **free key** (no account needed): request one at
[carto.com/basemaps](https://carto.com/basemaps/) and put it in `PAWPIXEL_TILE_KEY` (a GitHub secret,
or `pawpixel.properties`). Free limits are generous for a pilot: 5 million tile loads a month for
non-commercial use, 1 million for commercial use; one owner looking at the map loads a few dozen
tiles. Keep the attribution visible (the app does). Without a key the tiles may stop loading, and
the map falls back to a plain grid with the pins still working.

Any other provider with a `{z}/{x}/{y}` URL works too (MapTiler, Stadia, your own tile server):
set `PAWPIXEL_TILE_URL` and `PAWPIXEL_TILE_ATTRIBUTION`, and the cartoon repaint applies to it as
well. Don't point the app at `tile.openstreetmap.org`: OpenStreetMap's own servers aren't meant
for apps.

## 5. Put the values into the build
Either as **GitHub repository secrets** (for CI release builds) with these names, or in a local,
git-ignored `pawpixel.properties` file in the repo root:
```
PAWPIXEL_SUPABASE_URL=https://xxxx.supabase.co
PAWPIXEL_SUPABASE_ANON_KEY=eyJ...
PAWPIXEL_GOOGLE_WEB_CLIENT_ID=1234-abc.apps.googleusercontent.com
PAWPIXEL_TILE_URL=https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=...
PAWPIXEL_TILE_ATTRIBUTION=© MapTiler © OpenStreetMap contributors
```
(`PAWPIXEL_TEST_EMAIL`/`PAWPIXEL_TEST_PASSWORD` are only for the CI test server. Never set them in a
store build.) For the release job, add the secrets to the "Build release bundle" step's `env`.

## 6. Run the pilot
- **Create a gathering** (you, as moderator): Supabase → Table editor → `gatherings` → Insert row.
  Fill title, starts_at, cell_id (copy from any `map_presence` row in that area), area_label
  (e.g. "Plaza Rizal area"), venue_name, venue_lat/lng, capacity, and set **approved = true**.
- **Walks owners propose** ("Host a walk" in the app) also land in `gatherings`, with **approved = false**
  and the host's `details`. Check that `venue_name` / `venue_lat`,`venue_lng` is a public place (open the
  coordinates in a map), then set **approved = true**; it shows to everyone at once, with the host marked.
  Delete the row to decline. A host has at most 3 proposals waiting, and the walk must be within 90 days.
- **Reports** arrive in the `reports` table. To remove someone: set `map_profiles.banned = true` for
  the `target_user`. Their pets disappear from everyone's map at once.
- Presence expires after 14 days without opening the map.

## 7. Before launching (also in docs/MAP_SAFETY.md)
- Update your privacy policy (docs/PRIVACY.md has the map section) and the store privacy labels
  (docs/STORE_LISTING.md).
- Google Play: fill the **User-generated content** and **Location** declarations.
- Know who handles reports, and how fast (aim: within 24 hours during the pilot).
