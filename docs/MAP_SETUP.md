# Switching on the pet map and household sharing

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
     (both the upload key and Play's app-signing key: Play Console → App integrity).
2. In Supabase → Authentication → Sign In / Providers → **Google**: enable it, paste the *web* client ID
   (and secret). Leave "Skip nonce checks" **off**: the app sends a nonce.

## 3. Sign in with Apple (iOS)
1. In your Apple Developer account, enable **Sign in with Apple** for the App ID `com.pawpixel.app`
   (the entitlement is already in `iosApp/iosApp/iosApp.entitlements`).
2. In Supabase → Authentication → Providers → **Apple**: enable it and add `com.pawpixel.app` as a
   client ID (native sign-in needs no Services ID or secret).

### 3b. Revoking Sign in with Apple when an account is deleted (App Store rule 5.1.1(v))
Apple requires that deleting an account also revokes the app's Sign in with Apple token, using
Apple's REST API. PawPixel does this on the server, in the Edge Function
`supabase/functions/apple-revoke`:
- Right after Sign in with Apple, the iPhone app sends Apple's one-time **authorization code** to the
  function. The function swaps it at `https://appleid.apple.com/auth/token` for a **refresh token** and
  keeps it in the `apple_tokens` table (migration `0008`). Only the function (service role) can read
  or write that table; the app never sees the token.
- **Delete my map account** on an iPhone calls the function, which revokes the token at
  `https://appleid.apple.com/auth/revoke` and then deletes the account (the same `delete_account()`
  as before). Deleting always wins: if Apple is down, the account is still deleted and the function
  logs the failure. If the function can't be reached at all, the app deletes the account directly.

To talk to Apple, the function signs a short-lived "client secret" (an ES256 JWT) with a Sign in with
Apple **key** from your Apple Developer account. The key lives only in Supabase function secrets:
never in the repo, the app, or GitHub.

**Until you do the steps below, nothing breaks:** accounts are still deleted, and the function's log
says `Sign in with Apple revocation isn't set up (APPLE_TEAM_ID, APPLE_KEY_ID, APPLE_PRIVATE_KEY)`.
Do them before you submit the iPhone app for review.

1. **Make the key.** [Apple Developer](https://developer.apple.com/account) → Certificates, Identifiers
   & Profiles → **Keys** → **+**. Name it "PawPixel Sign in with Apple", tick **Sign in with Apple**,
   click **Configure** and choose the primary App ID **com.pawpixel.app**. Save, Continue, Register.
   **Download** the `AuthKey_XXXXXXXXXX.p8` file: Apple lets you download it only once. Keep it in
   your password manager, never in the repo.
2. **Note two IDs.**
   - `APPLE_KEY_ID`: the 10-character **Key ID** shown on the key's page (also in the file name).
   - `APPLE_TEAM_ID`: your 10-character **Team ID** (Membership details, or top right of the portal).
3. **Apply the database and deploy the function** (from the repo, after `supabase link`):
   ```
   supabase db push                          # creates apple_tokens (0008_apple_sign_in_tokens.sql)
   supabase functions deploy apple-revoke    # reads supabase/config.toml: verify_jwt = false
   ```
   (`verify_jwt = false` is intended: the function checks the caller's session itself with Supabase
   Auth, which also works with the newer asymmetric JWT signing keys.)
4. **Set the secrets** (Supabase → Edge Functions → Secrets, or the CLI):
   ```
   supabase secrets set APPLE_TEAM_ID=ABCDE12345 APPLE_KEY_ID=XYZ987WVUT
   supabase secrets set APPLE_PRIVATE_KEY="$(cat ~/Downloads/AuthKey_XXXXXXXXXX.p8)"
   ```
   The whole `.p8` file, including the `-----BEGIN PRIVATE KEY-----` lines. In the dashboard you can
   paste it as is (line breaks, or literal `\n`, both work).
   `APPLE_CLIENT_ID` is optional and defaults to the app's bundle ID, **com.pawpixel.app** (the
   client ID for native Sign in with Apple). Set it only if the bundle ID ever changes.
   `SUPABASE_URL`, `SUPABASE_ANON_KEY` and `SUPABASE_SERVICE_ROLE_KEY` are provided by Supabase
   automatically; don't set them.
5. **Check it on an iPhone** (TestFlight build pointing at this project):
   - Sign in with Apple on the pet map. Supabase → Table editor → `apple_tokens` now has a row for you.
   - Pet map → More → **Delete my map account**. The row and the account are gone, the function's log
     (Edge Functions → apple-revoke → Logs) has no errors, and on the iPhone, Settings → your name →
     Sign-In & Security → Sign in with Apple no longer lists PawPixel.

Notes:
- An Apple account that signed in while the secrets were missing has no token on file, so its
  deletion can't be revoked; signing in again after the setup fixes that. Set the secrets before
  real users sign in.
- If you rotate the key (revoke it in the portal and make a new one), update `APPLE_KEY_ID` and
  `APPLE_PRIVATE_KEY`. Stored tokens keep working: they belong to the app, not the key.
- **Deleting an account for someone (support):** revoke first, then delete the user in Supabase →
  Authentication → Users (everything else cascades):
  ```
  curl -X POST https://<project-ref>.supabase.co/functions/v1/apple-revoke \
    -H "Authorization: Bearer <service_role key>" -H "Content-Type: application/json" \
    -d '{"action":"revoke","user_id":"<the user's id>"}'
  ```
  Run it from your own computer only: the service role key never goes in the app or the repo.

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
