# Switching on the pet map and family sharing

The map and family sharing use the same server and sign-in. Both are built and tested (against a real Supabase in CI), but a store build needs **your**
accounts. Until these settings are filled in, the app shows "The pet map is coming soon", and
everything else works as before.

You'll set up four things: a Supabase project, Google sign-in (Android), Sign in with Apple (iOS)
and a map-tile key. Then you put six values into the build.

## 1. Supabase (the map server)
1. Create a project at [supabase.com](https://supabase.com). Pick the **Singapore** region (closest to Naga).
2. Apply the database: install the [Supabase CLI](https://supabase.com/docs/guides/cli), then from the repo:
   ```
   supabase link --project-ref <your-project-ref>
   supabase db push          # applies every file in supabase/migrations (map, name filter, family sharing)
   ```
   Always run `supabase db push` **before** releasing an app update that uses a new migration (the
   app sends the new columns, e.g. a pet's outfit; an older database would refuse them).
3. Note **Project URL** and the **anon / publishable key** (Project Settings → API).
   Never put the service/secret key in the app.

## 2. Google sign-in (Android)
1. In [Google Cloud Console](https://console.cloud.google.com) → APIs & Services → Credentials, create:
   - an **OAuth client ID, type "Web application"**. Its client ID is `PAWPIXEL_GOOGLE_WEB_CLIENT_ID`.
   - an **OAuth client ID, type "Android"** for package `com.pawpixel.app` with your app's **SHA-1**
     (both the upload key and Play's app-signing key: Play Console → App integrity).
2. In Supabase → Authentication → Sign In / Providers → **Google**: enable it, paste the *web* client ID
   (and secret). Leave "Skip nonce checks" **off**: the app sends a nonce.

## 3. Sign in with Apple (iOS)
1. In your Apple Developer account, enable **Sign in with Apple** for the App ID `com.pawpixel.app`
   (the entitlement is already in `iosApp/iosApp/iosApp.entitlements`).
2. In Supabase → Authentication → Providers → **Apple**: enable it and add `com.pawpixel.app` as a
   client ID (native sign-in needs no Services ID or secret).

## 4. Map tiles
The map draws standard 256 px raster tiles, crisp and pixelated. Any provider with a `{z}/{x}/{y}`
URL works. The default suggestion is **MapTiler**:
- Create a key at [maptiler.com](https://www.maptiler.com/cloud/). Restrict it to your app.
- `PAWPIXEL_TILE_URL` = `https://api.maptiler.com/maps/streets-v2/256/{z}/{x}/{y}.png?key=YOUR_KEY`
- `PAWPIXEL_TILE_ATTRIBUTION` = `© MapTiler © OpenStreetMap contributors`

Pricing note: MapTiler's **Free** plan is for testing and non-commercial use (5,000 map sessions a
month). Once PawPixel earns money (Pro), switch to their **Flex** plan (about $30 a month), or another
provider. Only the two settings above change.

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
- **Reports** arrive in the `reports` table. To remove someone: set `map_profiles.banned = true` for
  the `target_user`. Their pets disappear from everyone's map at once.
- Presence expires after 14 days without opening the map.

## 7. Before launching (also in docs/MAP_SAFETY.md)
- Update your privacy policy (docs/PRIVACY.md has the map section) and the store privacy labels
  (docs/STORE_LISTING.md).
- Google Play: fill the **User-generated content** and **Location** declarations.
- Know who handles reports, and how fast (aim: within 24 hours during the pilot).
