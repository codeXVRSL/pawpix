#!/usr/bin/env python3
"""Integration test of households (shared pets and care) against a running Supabase.

    SUPABASE_URL=http://127.0.0.1:54321 ANON_KEY=... SERVICE_KEY=... python3 supabase/tests/household_test.py

Plays a household (Save, Jamaica), a stranger and a few relatives through the real Auth +
PostgREST APIs: who can see what, invite codes, the member limit, the append-only care log with
undo, leaving, removing, stopping sharing, and account deletion. Standard library only.
"""
import json, os, re, sys, urllib.request, urllib.error, uuid

URL = os.environ["SUPABASE_URL"].rstrip("/")
ANON = os.environ["ANON_KEY"]
SERVICE = os.environ["SERVICE_KEY"]
failures = []


def call(method, path, body=None, token=None, key=ANON, prefer=None):
    headers = {"apikey": key, "Content-Type": "application/json", "Authorization": f"Bearer {token or key}"}
    if prefer: headers["Prefer"] = prefer
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(URL + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try: return e.code, json.loads(raw)
        except ValueError: return e.code, raw


def admin(method, path, body=None, prefer=None):
    return call(method, path, body, key=SERVICE, prefer=prefer)


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  -> {detail}"))
    if not ok: failures.append(name)


def person(name):
    email = f"{name.lower()}-{uuid.uuid4().hex[:6]}@test.pawpixel"
    s, u = admin("POST", "/auth/v1/admin/users", {"email": email, "password": "test-pass-123", "email_confirm": True})
    assert s in (200, 201), (s, u)
    s, t = call("POST", "/auth/v1/token?grant_type=password", {"email": email, "password": "test-pass-123"})
    assert s == 200, (s, t)
    return {"id": u["id"], "token": t["access_token"], "name": name}


def rpc(p, fn, args=None):
    return call("POST", f"/rest/v1/rpc/{fn}", args or {}, p["token"])


def get(p, path):
    s, r = call("GET", path, token=p["token"])
    return r if s == 200 else f"HTTP {s}: {r}"


def rows(p, table, hid, extra=""):
    return get(p, f"/rest/v1/{table}?household_id=eq.{hid}&select=*{extra}")


def invite(p, hid):
    s, code = rpc(p, "create_invite", {"p_household": hid})
    assert s == 200, (s, code)
    return code


def join(p, code):
    return rpc(p, "join_household", {"p_code": code, "p_display_name": p["name"]})


save, jamaica, stranger = person("Save"), person("Jamaica"), person("Stranger")

# ---------- Starting a household ----------
s, hid = rpc(save, "create_household", {"p_name": "Save's household", "p_display_name": "Save"})
check("Save starts a household", s == 200 and isinstance(hid, str), (s, hid))
s, r = rpc(save, "create_household", {"p_name": "Another", "p_display_name": "Save"})
check("one household per account", s >= 400, (s, r))
s, r = call("POST", "/rest/v1/household_members", {"household_id": hid, "user_id": stranger["id"], "display_name": "Sneaky"}, stranger["token"])
check("nobody adds themselves without a code", s >= 400, (s, r))
s, r = rpc(stranger, "create_invite", {"p_household": hid})
check("a stranger can't make invite codes", s >= 400, (s, r))

# ---------- Invite codes ----------
code = invite(save, hid)
check("the invite code is 8 letters/digits without look-alikes", re.fullmatch(r"[A-HJKMNP-Z2-9]{8}", code or "") is not None, code)
s, r = join(jamaica, "ZZZZ2222")
check("a wrong code is refused (no household)", s == 200 and r is None, (s, r))
s, r = admin("PATCH", f"/rest/v1/household_invites?code=eq.{code}", {"expires_at": "2000-01-01T00:00:00Z"})
check("(test) the code expires", s in (200, 204), (s, r))
s, r = join(jamaica, code)
check("an expired code is refused", s == 200 and r is None, (s, r))
code = invite(save, hid)
s, r = join(jamaica, code.lower())
check("a new code works (typed in lower case)", s == 200 and r == hid, (s, r))
s, r = join(jamaica, code)
check("joining again is harmless", s == 200 and r == hid, (s, r))
members = rows(jamaica, "household_members", hid, "&order=joined_at")
check("both see both names", [m["display_name"] for m in members] == ["Save", "Jamaica"], members)

# ---------- Shared pets, tasks and the care log ----------
pet = {"household_id": hid, "id": "chelsea", "name": "Chelsea", "species": "CAT", "ears": "POINTY",
       "look": "1;e08a3a,f4f1ea;" + "0" * 40 + "1" * 24, "eyes": [[0.3, 0.4], [0.7, 0.4]], "edited_at_ms": 1}
s, r = call("POST", "/rest/v1/household_pets", pet, save["token"])
check("Save shares Chelsea", s == 201, (s, r))
task = {"household_id": hid, "id": "feed", "pet_id": "chelsea", "kind": "FEED", "title": "Feed", "slots": [420, 1050]}
s, r = call("POST", "/rest/v1/household_tasks", task, save["token"])
check("and her feeding task", s == 201, (s, r))
s, r = call("POST", "/rest/v1/household_pets", dict(pet, look="1;ffffff;" + "0" * 64, edited_at_ms=9_999_999_999_999), jamaica["token"],
            prefer="resolution=merge-duplicates,return=representation")
check("a clock far ahead can't win every later edit", s in (200, 201) and r[0]["edited_at_ms"] < 9_999_999_999_999, (s, r))
check("Jamaica sees Chelsea's pixel look", rows(jamaica, "household_pets", hid)[0]["look"].startswith("1;ffffff"))

rec = {"household_id": hid, "id": "cjam1", "task_id": "feed", "at_ms": 1_790_000_000_000, "local_minute": 422, "local_day": 20717}
s, r = call("POST", "/rest/v1/household_completions", dict(rec, done_by=save["id"]), jamaica["token"])
check("Jamaica logs a feed", s == 201, (s, r))
log = rows(save, "household_completions", hid)
check("Save sees it, marked as hers (whatever the phone claims)", len(log) == 1 and log[0]["done_by"] == jamaica["id"], log)
s, r = call("POST", "/rest/v1/household_completions?on_conflict=household_id,id", rec, jamaica["token"], prefer="resolution=ignore-duplicates")
check("sending the same record twice keeps one", s in (200, 201) and len(rows(save, "household_completions", hid)) == 1, (s, r))
since = log[0]["changed_ms"]

# ---------- Strangers see and change nothing ----------
for table in ("households", "household_members", "household_pets", "household_tasks", "household_completions"):
    got = get(stranger, f"/rest/v1/{table}?select=*")
    check(f"a stranger sees no {table}", got == [], got)
s, r = call("POST", "/rest/v1/household_completions", dict(rec, id="cx"), stranger["token"])
check("a stranger can't log care", s >= 400, (s, r))
s, r = call("PATCH", f"/rest/v1/household_pets?household_id=eq.{hid}", {"name": "Hacked"}, stranger["token"])
check("a stranger can't rename the pet", rows(save, "household_pets", hid)[0]["name"] == "Chelsea", (s, r))
s, r = call("PATCH", f"/rest/v1/household_completions?household_id=eq.{hid}", {"undone_at": "2026-01-01T00:00:00Z"}, stranger["token"])
check("a stranger can't undo", rows(save, "household_completions", hid)[0]["undone_at"] is None, (s, r))

# ---------- Undo is a soft delete, by the one who logged it ----------
undo = {"undone_at": "2026-01-01T00:00:00Z"}
call("PATCH", f"/rest/v1/household_completions?household_id=eq.{hid}&id=eq.cjam1", undo, save["token"])
check("Save can't undo Jamaica's record", rows(save, "household_completions", hid)[0]["undone_at"] is None)
s, r = call("DELETE", f"/rest/v1/household_completions?household_id=eq.{hid}&id=eq.cjam1", token=jamaica["token"])
check("records can't be deleted (the log is append-only)", s >= 400 and len(rows(save, "household_completions", hid)) == 1, (s, r))
s, r = call("PATCH", f"/rest/v1/household_completions?household_id=eq.{hid}&id=eq.cjam1", undo, jamaica["token"])
check("Jamaica undoes hers", s in (200, 204), (s, r))
row = rows(save, "household_completions", hid)[0]
check("the undo is stamped by the server", row["undone_at"] and not row["undone_at"].startswith("2026-01-01") and row["undone_by"] == jamaica["id"], row)
changed = get(save, f"/rest/v1/household_completions?household_id=eq.{hid}&changed_ms=gt.{since}&select=id,undone_at")
check("the undo shows in 'changed since my last sync'", [c["id"] for c in changed] == ["cjam1"] and changed[0]["undone_at"], changed)
call("PATCH", f"/rest/v1/household_completions?household_id=eq.{hid}&id=eq.cjam1", {"undone_at": None}, jamaica["token"])
check("an undo can't be taken back", rows(save, "household_completions", hid)[0]["undone_at"] is not None)

# ---------- At most 8 people ----------
relatives = [person(f"Relative{i}") for i in range(7)]
for p in relatives[:6]:
    s, r = join(p, code)
    assert s == 200 and r == hid, (p["name"], s, r)
check("up to 8 people join", len(rows(save, "household_members", hid)) == 8)
s, r = join(relatives[6], code)
check("the 9th is told it's full", s >= 400 and "full" in json.dumps(r), (s, r))

# ---------- Removing, leaving ----------
s, r = rpc(jamaica, "remove_member", {"p_user": relatives[0]["id"]})
check("only the starter can remove people", s >= 400, (s, r))
s, r = rpc(save, "remove_member", {"p_user": relatives[0]["id"]})
check("Save removes someone", s in (200, 204), (s, r))
check("who then sees nothing", rows(relatives[0], "household_pets", hid) == [] and get(relatives[0], "/rest/v1/household_members?select=*") == [])
s, r = rpc(relatives[1], "leave_household")
check("someone leaves", s in (200, 204) and rows(relatives[1], "household_completions", hid) == [], (s, r))
check("the household goes on without them", len(rows(save, "household_members", hid)) == 6)

# ---------- Account deletion ----------
s, r = rpc(relatives[2], "delete_account")
check("deleting an account removes its membership", s in (200, 204) and len(rows(save, "household_members", hid)) == 5, (s, r))
solo = person("Solo")
s, solo_h = rpc(solo, "create_household", {"p_name": "Solo", "p_display_name": "Solo"})
rpc(solo, "delete_account")
s, left = admin("GET", f"/rest/v1/households?id=eq.{solo_h}&select=id")
check("the last member's account deletion removes the household", left == [], left)
s, r = rpc(jamaica, "delete_account")
log = rows(save, "household_completions", hid)
check("records of a deleted account stay, author unknown", len(log) == 1 and log[0]["done_by"] is None and log[0]["undone_at"], log)

# ---------- Stopping sharing for everyone ----------
s, r = rpc(relatives[3], "delete_household")
check("only the starter can stop sharing for everyone", s >= 400, (s, r))
s, r = rpc(save, "delete_household")
check("Save stops sharing", s in (200, 204), (s, r))
s, left = admin("GET", f"/rest/v1/households?id=eq.{hid}&select=id")
s2, recs = admin("GET", f"/rest/v1/household_completions?household_id=eq.{hid}&select=id")
check("the household, its pets and records are gone", left == [] and recs == [], (left, recs))
check("members see nothing any more", get(relatives[3], "/rest/v1/household_members?select=*") == [])

print(f"\n{len(failures)} failed" if failures else "\nall household checks passed")
sys.exit(1 if failures else 0)
