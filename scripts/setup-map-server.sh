#!/usr/bin/env bash
# Sets up PawPixel's map server in one go: a Supabase project with the migrations applied, a test
# account for debug builds, and the values stored as GitHub secrets so CI's APKs talk to it.
#
#   SUPABASE_ACCESS_TOKEN=sbp_... scripts/setup-map-server.sh [--name pawpixel] [--region ap-southeast-1] [--repo codeXVRSL/pawpix]
#
# Needs: curl, python3, the Supabase CLI (https://supabase.com/docs/guides/cli), and the GitHub CLI
# signed in (`gh auth login`) for the secrets step. Get the access token at
# https://supabase.com/dashboard/account/tokens. Re-running is safe: an existing project of the
# same name is reused.
#
# What you still do by hand afterwards (Google won't let a script do it): Google sign-in, section 2
# of docs/MAP_SETUP.md. Until then, debug APKs sign in with the test account this script creates.
# Optional: PAWPIXEL_TILE_KEY=... (the free CARTO street-tile key, section 4) is stored as a secret too.
set -euo pipefail

NAME="pawpixel"; REGION="ap-southeast-1"; REPO=""
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="$2"; shift 2 ;;
    --region) REGION="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    *) echo "unknown option $1"; exit 2 ;;
  esac
done
: "${SUPABASE_ACCESS_TOKEN:?Set SUPABASE_ACCESS_TOKEN (https://supabase.com/dashboard/account/tokens)}"
command -v supabase >/dev/null || { echo "Install the Supabase CLI first: https://supabase.com/docs/guides/cli"; exit 1; }
cd "$(dirname "$0")/.."

api() { curl -sS -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" -H "Content-Type: application/json" "$@"; }
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }

echo "== Organisation"
ORG=$(api https://api.supabase.com/v1/organizations | json "d[0]['id']")
echo "   $ORG"

echo "== Project '$NAME' in $REGION"
REF=$(api https://api.supabase.com/v1/projects | json "next((p['id'] for p in d if p['name']=='$NAME'), '')")
if [ -z "$REF" ]; then
  DB_PASS=$(python3 -c "import secrets; print(secrets.token_urlsafe(24))")
  REF=$(api -X POST https://api.supabase.com/v1/projects \
    -d "{\"name\":\"$NAME\",\"organization_id\":\"$ORG\",\"region\":\"$REGION\",\"plan\":\"free\",\"db_pass\":\"$DB_PASS\"}" | json "d['id']")
  echo "   created $REF (database password saved to .supabase-db-password, git-ignored)"
  printf '%s' "$DB_PASS" > .supabase-db-password
else
  echo "   exists: $REF"
  DB_PASS=$(cat .supabase-db-password 2>/dev/null || true)
  [ -n "$DB_PASS" ] || { echo "   The project already exists but .supabase-db-password is missing: reset the database password in the dashboard and save it there."; exit 1; }
fi

echo "== Waiting for the project to come up"
for i in $(seq 1 60); do
  STATUS=$(api "https://api.supabase.com/v1/projects/$REF" | json "d.get('status','')")
  [ "$STATUS" = "ACTIVE_HEALTHY" ] && break
  sleep 10
done
[ "$STATUS" = "ACTIVE_HEALTHY" ] || { echo "   still $STATUS after 10 minutes; run the script again later"; exit 1; }

URL="https://$REF.supabase.co"
KEYS=$(api "https://api.supabase.com/v1/projects/$REF/api-keys")
ANON=$(echo "$KEYS" | json "next(k['api_key'] for k in d if k['name']=='anon')")
SERVICE=$(echo "$KEYS" | json "next(k['api_key'] for k in d if k['name']=='service_role')")

echo "== Applying the migrations (supabase/migrations)"
supabase link --project-ref "$REF" --password "$DB_PASS" >/dev/null
supabase db push --password "$DB_PASS"

echo "== Test account for debug builds"
TEST_EMAIL="tester@pawpixel.app"
TEST_PASSWORD=$(python3 -c "import secrets; print(secrets.token_urlsafe(12))")
EXISTING=$(curl -sS -H "apikey: $SERVICE" -H "Authorization: Bearer $SERVICE" "$URL/auth/v1/admin/users?per_page=1000" \
  | json "next((u['id'] for u in d.get('users',[]) if u.get('email')=='$TEST_EMAIL'), '')")
if [ -n "$EXISTING" ]; then
  curl -sS -o /dev/null -X PUT -H "apikey: $SERVICE" -H "Authorization: Bearer $SERVICE" -H "Content-Type: application/json" \
    "$URL/auth/v1/admin/users/$EXISTING" -d "{\"password\":\"$TEST_PASSWORD\"}"
else
  curl -sS -o /dev/null -X POST -H "apikey: $SERVICE" -H "Authorization: Bearer $SERVICE" -H "Content-Type: application/json" \
    "$URL/auth/v1/admin/users" -d "{\"email\":\"$TEST_EMAIL\",\"password\":\"$TEST_PASSWORD\",\"email_confirm\":true}"
fi
echo "   $TEST_EMAIL (password in pawpixel.properties)"

echo "== Local build settings (pawpixel.properties, git-ignored)"
cat > pawpixel.properties <<EOF
PAWPIXEL_SUPABASE_URL=$URL
PAWPIXEL_SUPABASE_ANON_KEY=$ANON
PAWPIXEL_GOOGLE_WEB_CLIENT_ID=
PAWPIXEL_TILE_URL=
PAWPIXEL_TILE_ATTRIBUTION=
PAWPIXEL_TILE_KEY=${PAWPIXEL_TILE_KEY:-}
PAWPIXEL_TEST_EMAIL=$TEST_EMAIL
PAWPIXEL_TEST_PASSWORD=$TEST_PASSWORD
EOF

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo "== GitHub secrets${REPO:+ for $REPO}"
  R=${REPO:+--repo $REPO}
  gh secret set PAWPIXEL_SUPABASE_URL $R --body "$URL"
  gh secret set PAWPIXEL_SUPABASE_ANON_KEY $R --body "$ANON"
  gh secret set PAWPIXEL_TEST_EMAIL $R --body "$TEST_EMAIL"
  gh secret set PAWPIXEL_TEST_PASSWORD $R --body "$TEST_PASSWORD"
  [ -n "${PAWPIXEL_TILE_KEY:-}" ] && gh secret set PAWPIXEL_TILE_KEY $R --body "$PAWPIXEL_TILE_KEY"
  echo "   set. The next CI run's debug APK has the map on (signed in with the test account)."
else
  echo "== GitHub secrets: gh isn't signed in. Add these in the repo's Settings → Secrets → Actions:"
  echo "   PAWPIXEL_SUPABASE_URL, PAWPIXEL_SUPABASE_ANON_KEY, PAWPIXEL_TEST_EMAIL, PAWPIXEL_TEST_PASSWORD (values in pawpixel.properties)"
fi

cat <<EOF

Done.
  Server:        $URL
  Dashboard:     https://supabase.com/dashboard/project/$REF
  Walks to approve: Table editor → gatherings → set approved = true (docs/MAP_SETUP.md, section 6)

Next (by hand): Google sign-in for store builds, docs/MAP_SETUP.md section 2. The debug keystore's SHA-1
is in that section. Map tiles (section 4) are optional: without them the map draws a plain grid.
EOF
