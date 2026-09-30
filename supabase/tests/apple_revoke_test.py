#!/usr/bin/env python3
"""End-to-end test of the apple-revoke Edge Function on a local Supabase, WITHOUT the Apple secrets
(CI, and any server where Sign in with Apple revocation isn't set up yet).

    SUPABASE_URL=http://127.0.0.1:54321 ANON_KEY=... SERVICE_KEY=... python3 supabase/tests/apple_revoke_test.py

The function is served by `supabase start` (edge runtime) or `supabase functions serve`. Checks that
without the secrets nothing breaks: the code hand-off stores nothing, deleting through the function
still deletes the account and everything with it, and the function logs that revocation was skipped.
Apple itself is never called here; handler_test.ts (Deno) covers the Apple requests with the secrets.
Standard library only.
"""
import json, os, subprocess, sys, time, urllib.request, urllib.error, uuid

URL = os.environ["SUPABASE_URL"].rstrip("/")
ANON = os.environ["ANON_KEY"]
SERVICE = os.environ["SERVICE_KEY"]
# Where the function's console output goes: the edge runtime container of `supabase start`.
EDGE_CONTAINER = os.environ.get("EDGE_CONTAINER", "supabase_edge_runtime_pawpixel")
FN = "/functions/v1/apple-revoke"
failures = []


def call(method, path, body=None, token=None, key=ANON, raw_body=None):
    headers = {"apikey": key, "Content-Type": "application/json"}
    if token is not False: headers["Authorization"] = f"Bearer {token or key}"
    data = raw_body if raw_body is not None else (None if body is None else json.dumps(body).encode())
    req = urllib.request.Request(URL + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: return e.code, json.loads(raw)
        except ValueError: return e.code, raw


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  -> {detail}"))
    if not ok: failures.append(name)


def owner(provider):
    """A test account. provider="apple" marks it as a Sign in with Apple account, as GoTrue does."""
    email = f"{provider}-{uuid.uuid4().hex[:6]}@test.pawpixel"
    s, u = call("POST", "/auth/v1/admin/users", {"email": email, "password": "test-pass-123", "email_confirm": True}, key=SERVICE)
    assert s in (200, 201), (s, u)
    if provider != "email":
        s, u2 = call("PUT", f"/auth/v1/admin/users/{u['id']}", {"app_metadata": {"provider": provider, "providers": [provider]}}, key=SERVICE)
        assert s == 200, (s, u2)
    s, t = call("POST", "/auth/v1/token?grant_type=password", {"email": email, "password": "test-pass-123"})
    assert s == 200, (s, t)
    s, me = call("GET", "/auth/v1/user", token=t["access_token"])
    assert s == 200 and provider in (me.get("app_metadata", {}).get("providers") or [me.get("app_metadata", {}).get("provider")]), (s, me)
    return {"id": u["id"], "token": t["access_token"]}


def fn(o, body, raw_body=None):
    return call("POST", FN, body, token=o["token"] if o else False, raw_body=raw_body)


# The edge runtime can take a while to start the function the first time.
deadline, s, r = time.time() + 120, 0, None
while time.time() < deadline:
    s, r = call("POST", FN, {"action": "ping"})
    if s not in (404, 502, 503, 504): break
    time.sleep(3)
check("the apple-revoke function is served", s not in (404, 502, 504), (s, r))

a = owner("apple")
g = owner("email")

# Refusals, before anything else happens.
s, r = fn(None, {"action": "delete_account"})
check("no session: refused", s == 401, (s, r))
s, r = call("POST", FN, {"action": "delete_account"}, token="not-a-session")
check("a forged session: refused", s == 401, (s, r))
s, r = fn(a, None, raw_body=b"not json")
check("a body that isn't JSON: refused", s == 400, (s, r))
s, r = fn(a, {"action": "steal"})
check("an unknown action: refused", s == 400, (s, r))
s, r = fn(a, {"action": "revoke"})
check("owners can't revoke without deleting (support only)", s == 400, (s, r))
s, r = fn(g, {"action": "store", "code": "c"})
check("a non-Apple account can't hand over an Apple code", s == 400, (s, r))
s, r = fn(a, {"action": "store"})
check("the code hand-off needs a code", s == 400, (s, r))
s, r = call("POST", FN, {"action": "revoke", "user_id": a["id"]}, key=SERVICE)
check("support revocation says it isn't set up", s == 503, (s, r))

# Without the secrets: the hand-off stores nothing, and says so.
s, r = fn(a, {"action": "store", "code": "c0de-from-apple"})
check("without Apple secrets the code hand-off succeeds but stores nothing", s == 200 and r == {"stored": False, "reason": "not configured"}, (s, r))
s, rows = call("GET", f"/rest/v1/apple_tokens?user_id=eq.{a['id']}&select=user_id", key=SERVICE)
check("no Apple token row was written", s == 200 and rows == [], (s, rows))

# An Apple token left over from when the secrets were set (the function kept it): still server-only,
# and it goes with the account.
s, _ = call("POST", "/rest/v1/apple_tokens", {"user_id": a["id"], "refresh_token": "apple-secret"}, key=SERVICE)
check("the service role can keep an Apple token", s in (200, 201), s)
s, rows = call("GET", "/rest/v1/apple_tokens?select=*", token=a["token"])
check("the owner can't read it", s >= 400 or rows == [], (s, rows))

# A map profile, so we can see everything goes with the account.
s, r = call("POST", "/rest/v1/map_profiles", {"user_id": a["id"], "confirmed_adult": True}, token=a["token"])
check("the Apple owner joins the map", s in (200, 201), (s, r))

s, r = fn(a, {"action": "delete_account"})
check("deleting through the function works without Apple secrets",
      s == 200 and r.get("deleted") is True and r.get("revoked") is False and r.get("revocation") == "skipped: not configured", (s, r))
s, u = call("GET", f"/auth/v1/admin/users/{a['id']}", key=SERVICE)
check("the account is gone", s == 404, (s, u))
s, rows = call("GET", f"/rest/v1/map_profiles?user_id=eq.{a['id']}&select=user_id", key=SERVICE)
s2, rows2 = call("GET", f"/rest/v1/apple_tokens?user_id=eq.{a['id']}&select=user_id", key=SERVICE)
check("its map profile and Apple token went with it", rows == [] and rows2 == [], (s, rows, s2, rows2))
s, r = call("POST", "/rest/v1/rpc/delete_account", {}, token=a["token"])
check("deleting again (the app's fallback) is harmless", s in (200, 204, 401, 403), (s, r))

s, r = fn(g, {"action": "delete_account"})
check("a Google/email account can be deleted through the function too", s == 200 and r.get("deleted") is True, (s, r))
s, u = call("GET", f"/auth/v1/admin/users/{g['id']}", key=SERVICE)
check("that account is gone", s == 404, (s, u))

time.sleep(2)  # the runtime's log is written asynchronously
try:
    out = subprocess.run(["docker", "logs", EDGE_CONTAINER], capture_output=True, text=True, errors="replace", timeout=60)
    text = out.stdout + out.stderr
except (OSError, subprocess.SubprocessError) as e:
    text = f"no log: {e}"
check("the function logged that revocation was skipped", f"deleting {a['id']} without revoking at Apple" in text, text[-3000:])
check("the function never logged a token", "apple-secret" not in text, "token in the log")

print(f"\n{len(failures)} failed" if failures else "\nall apple-revoke checks passed")
sys.exit(1 if failures else 0)
