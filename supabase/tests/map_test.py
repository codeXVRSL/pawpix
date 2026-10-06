#!/usr/bin/env python3
"""Integration test of the map backend against a running Supabase (local `supabase start`).

    SUPABASE_URL=http://127.0.0.1:54321 ANON_KEY=... SERVICE_KEY=... python3 supabase/tests/map_test.py

Plays several owners through the real Auth + PostgREST APIs and checks every privacy rule in
docs/MAP_SAFETY.md. Standard library only.
"""
import json, os, sys, urllib.request, urllib.error, uuid
from datetime import datetime, timedelta, timezone

URL = os.environ["SUPABASE_URL"].rstrip("/")
ANON = os.environ["ANON_KEY"]
SERVICE = os.environ["SERVICE_KEY"]
LOOK = "1;e08a3a,f4f1ea;" + "0" * 40 + "1" * 24
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


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + ("" if ok else f"  -> {detail}"))
    if not ok: failures.append(name)


def owner(n):
    email = f"owner{n}-{uuid.uuid4().hex[:6]}@test.pawpixel"
    s, u = call("POST", "/auth/v1/admin/users", {"email": email, "password": "test-pass-123", "email_confirm": True}, key=SERVICE)
    assert s in (200, 201), (s, u)
    s, t = call("POST", "/auth/v1/token?grant_type=password", {"email": email, "password": "test-pass-123"})
    assert s == 200, (s, t)
    return {"id": u["id"], "token": t["access_token"]}


def join(o, cell, lat, lng, pets=1):
    s, r = call("POST", "/rest/v1/map_profiles", {"user_id": o["id"], "confirmed_adult": True}, o["token"])
    assert s in (200, 201), (s, r)
    for i in range(pets):
        s, r = call("POST", "/rest/v1/map_pets", {"owner_id": o["id"], "name": f"Pet{i}", "species": "CAT",
                                                   "ears": "POINTY", "look": LOOK, "local_id": f"p{i}"}, o["token"])
        assert s in (200, 201), (s, r)
    s, r = call("POST", "/rest/v1/map_presence", {"owner_id": o["id"], "cell_id": cell, "cell_lat": lat, "cell_lng": lng},
                o["token"], prefer="resolution=merge-duplicates")
    assert s in (200, 201), (s, r)


def rpc(o, fn, args=None):
    return call("POST", f"/rest/v1/rpc/{fn}", args or {}, o["token"])


NAGA, NEAR = ("g1000:9346:27588", 13.6218, 123.1948), ("g1000:9347:27588", 13.631, 123.1948)
a, b, c, d, e = (owner(i) for i in range(5))

join(a, *NAGA); join(b, *NAGA)
s, cells = rpc(e, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("an area with 2 owners stays hidden", s == 200 and cells == [], (s, cells))
s, pets = rpc(e, "pets_in_cell", {"p_cell_id": NAGA[0]})
check("its pets stay hidden too", s == 200 and pets == [], (s, pets))

join(c, *NAGA, pets=2)
s, cells = rpc(e, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("with 3 owners the area appears, counting pets", s == 200 and len(cells) == 1 and cells[0]["pets"] == 4, (s, cells))
s, pets = rpc(e, "pets_in_cell", {"p_cell_id": NAGA[0]})
check("pets come back with look codes", s == 200 and len(pets) == 4 and all(p["look"] == LOOK for p in pets), (s, pets))
check("no owner ids or photos are exposed", s == 200 and set(pets[0]) == {"pet_id", "name", "species", "ears", "look", "mine"}, pets[0] if pets else pets)

s, rows = call("GET", "/rest/v1/map_pets?select=*", token=e["token"])
check("row security: you can't list other owners' pets", s == 200 and rows == [], (s, rows))
s, rows = call("GET", "/rest/v1/map_presence?select=*", token=e["token"])
check("row security: you can't read anyone's area", s == 200 and rows == [], (s, rows))
s, rows = call("POST", "/rest/v1/rpc/nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("signed-out callers get nothing", s in (401, 403) or rows == [], (s, rows))

s, _ = rpc(e, "block_pet_owner", {"p_pet_id": next(p["pet_id"] for p in pets if p["name"] == "Pet1")})  # c's second pet
s2, cells = rpc(e, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("blocking an owner drops the area below 3, so it hides", s in (200, 204) and cells == [], (s, s2, cells))
s, cells = rpc(c, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("blocks work both ways only for the pair (others still see it)", s == 200 and len(cells) == 1, (s, cells))

s, _ = rpc(d, "report_pet", {"p_pet_id": pets[0]["pet_id"], "p_reason": "spam", "p_details": "test"})
s2, reports = call("GET", "/rest/v1/reports?select=reason,target_user", key=SERVICE)
check("reports reach the moderator", s in (200, 204) and s2 == 200 and len(reports) == 1 and reports[0]["reason"] == "spam", (s, s2, reports))
s, rows = call("GET", "/rest/v1/reports?select=*", token=d["token"])
check("reporters can't read reports back", s == 200 and rows == [], (s, rows))

join(e, "g1000:9000:27000", 10.0, 120.0)  # e joins far away, so only capacity can stop e's RSVP below

# Gatherings (created and approved by the moderator in the pilot)
s, g = call("POST", "/rest/v1/gatherings", {"host_id": a["id"], "title": "Sunday pet walk", "starts_at": "2099-01-01T08:00:00Z",
                                             "cell_id": NAGA[0], "area_label": "Plaza Rizal area", "venue_name": "Plaza Rizal fountain",
                                             "venue_lat": 13.6238, "venue_lng": 123.1851, "capacity": 2, "approved": True},
            key=SERVICE, prefer="return=representation")
gid = g[0]["id"]
s, rows = call("GET", "/rest/v1/gatherings_public?select=*", token=d["token"])
check("gatherings list shows no venue", s == 200 and len(rows) == 1 and "venue_name" not in rows[0] and rows[0]["i_am_going"] is False, (s, rows))
s, rows = call("GET", "/rest/v1/gatherings?select=*", token=d["token"])
check("the raw gatherings table isn't readable", s == 200 and rows == [], (s, rows))
s, v = rpc(b, "gathering_details", {"p_id": gid})
check("venue hidden before RSVP", s == 200 and v == [], (s, v))
s, n = rpc(b, "rsvp", {"p_id": gid, "p_going": True})
s2, v = rpc(b, "gathering_details", {"p_id": gid})
check("RSVP reveals the venue", s == 200 and n == 1 and v and v[0]["venue_name"] == "Plaza Rizal fountain", (s, n, v))
s, n = rpc(c, "rsvp", {"p_id": gid, "p_going": True})
s2, n2 = rpc(e, "rsvp", {"p_id": gid, "p_going": True})
check("capacity is enforced", s == 200 and n == 2 and s2 >= 400, (s, n, s2, n2))
s, n = rpc(d, "rsvp", {"p_id": gid, "p_going": True})
check("you must join the map to RSVP", s >= 400, (s, n))
s, n = rpc(b, "rsvp", {"p_id": gid, "p_going": False})
check("you can cancel", s == 200 and n == 1, (s, n))

# The community hosts its own walks (0008). b is on the map; d never joined.
SOON = (datetime.now(timezone.utc) + timedelta(days=7)).strftime("%Y-%m-%dT%H:%M:%SZ")
walk = {"p_title": "  Sunset walk at the Plaza  ", "p_starts_at": SOON, "p_cell_id": NAGA[0],
        "p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2], "p_area_label": "Plaza Rizal area", "p_venue_name": "Plaza Rizal fountain",
        "p_venue_lat": 13.6238, "p_venue_lng": 123.1851, "p_capacity": 10, "p_details": "Bring water"}
s, wid = rpc(b, "host_walk", walk)
check("an owner on the map can propose a walk", s == 200 and isinstance(wid, str), (s, wid))
s, r = rpc(d, "host_walk", walk)
check("you must join the map to host", s >= 400, (s, r))
s, rows = call("GET", "/rest/v1/gatherings_public?select=id", token=c["token"])
check("a proposed walk stays hidden until approved", s == 200 and wid not in [x["id"] for x in rows], (s, rows))
s, mine = call("GET", "/rest/v1/my_walks?select=*", token=b["token"])
check("the host sees it waiting, with the venue, trimmed title, and themselves going",
      s == 200 and len(mine) == 1 and mine[0]["approved"] is False and mine[0]["venue_name"] == "Plaza Rizal fountain"
      and mine[0]["title"] == "Sunset walk at the Plaza" and mine[0]["going"] == 1, (s, mine))
s, other = call("GET", "/rest/v1/my_walks?select=*", token=c["token"])
check("nobody else sees it in their walks", s == 200 and other == [], (s, other))
s, r = rpc(b, "host_walk", dict(walk, p_starts_at="2000-01-01T09:00:00Z"))
s2, r2 = rpc(b, "host_walk", dict(walk, p_starts_at="2099-01-01T09:00:00Z"))
check("a walk in the past, or more than 90 days out, is refused", s >= 400 and s2 >= 400, (s, r, s2, r2))
for i in range(2): rpc(b, "host_walk", dict(walk, p_title=f"Walk {i}"))
s, r = rpc(b, "host_walk", dict(walk, p_title="Walk 3"))
check("at most 3 walks waiting per host", s >= 400 and "3 walks" in json.dumps(r), (s, r))
call("PATCH", f"/rest/v1/gatherings?id=eq.{wid}", {"approved": True}, key=SERVICE)
s, rows = call("GET", "/rest/v1/gatherings_public?select=*", token=c["token"])
pub = next((x for x in rows if x["id"] == wid), None)
check("once approved, everyone sees it with the note and area pin, but no venue",
      s == 200 and pub is not None and pub["details"] == "Bring water" and pub["cell_lat"] == NAGA[1] and "venue_name" not in pub
      and pub["i_am_host"] is False, (s, pub))
s, rows = call("GET", "/rest/v1/gatherings_public?select=id,i_am_host", token=b["token"])
check("the host is marked as host", s == 200 and next(x for x in rows if x["id"] == wid)["i_am_host"] is True, (s, rows))
s, v = rpc(b, "gathering_details", {"p_id": wid})
check("the host sees the venue without an RSVP", s == 200 and v and v[0]["venue_name"] == "Plaza Rizal fountain", (s, v))
s, stats = rpc(c, "community_stats")
check("community totals count owners, pets, areas and walks, nothing else",
      s == 200 and stats and set(stats[0]) == {"owners", "pets", "areas", "walks"} and stats[0]["owners"] >= 3 and stats[0]["walks"] >= 2, (s, stats))
s, _ = rpc(c, "cancel_walk", {"p_id": wid})
s2, rows = call("GET", f"/rest/v1/gatherings?id=eq.{wid}&select=id", key=SERVICE)
check("only the host can cancel a walk", len(rows) == 1, (s, rows))
s, _ = rpc(b, "cancel_walk", {"p_id": wid})
s2, rows = call("GET", f"/rest/v1/gatherings?id=eq.{wid}&select=id", key=SERVICE)
check("the host can cancel it", s in (200, 204) and rows == [], (s, rows))
for row in call("GET", f"/rest/v1/gatherings?host_id=eq.{b['id']}&select=id", key=SERVICE)[1]:
    call("DELETE", f"/rest/v1/gatherings?id=eq.{row['id']}", key=SERVICE)

# Moderation and self-service
call("PATCH", f"/rest/v1/map_profiles?user_id=eq.{b['id']}", {"banned": True}, key=SERVICE)
s, cells = rpc(d, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("a banned owner's pets disappear (area drops below 3)", s == 200 and cells == [], (s, cells))
s, _ = call("PATCH", f"/rest/v1/map_profiles?user_id=eq.{b['id']}", {"banned": False}, b["token"])
s2, rows = call("GET", f"/rest/v1/map_profiles?user_id=eq.{b['id']}&select=banned", key=SERVICE)
check("a banned owner can't unban themselves", s >= 400 and rows[0]["banned"] is True, (s, rows))

for bad, want in (("G4g0", "A dog"), ("tang!na mo", "A dog"), ("Grape", "Grape"), ("Petite", "Petite")):
    s, r = call("POST", "/rest/v1/map_pets", {"owner_id": a["id"], "name": bad, "species": "DOG", "ears": "FLOPPY", "look": LOOK},
                a["token"], prefer="return=representation")
    got = r[0]["name"] if s in (200, 201) and r else None
    check(f"pet names are filtered on the server ({bad!r} -> {want!r})", got == want, (s, r))

s, r = call("POST", "/rest/v1/map_pets", {"owner_id": a["id"], "name": "x", "species": "DOG", "look": "<script>"}, a["token"])
check("look codes are validated", s >= 400, (s, r))
s, r = call("POST", "/rest/v1/map_presence", {"owner_id": a["id"], "cell_id": "13.6218,123.1948", "cell_lat": 13.62, "cell_lng": 123.19},
            a["token"], prefer="resolution=merge-duplicates")
check("presence must be a grid cell id, not coordinates", s >= 400, (s, r))

s, _ = rpc(a, "leave_map")
s2, rows = call("GET", f"/rest/v1/map_presence?owner_id=eq.{a['id']}&select=owner_id", key=SERVICE)
check("leaving the map removes your area", s in (200, 204) and rows == [], (s, rows))
s, _ = rpc(c, "delete_account")
s2, u = call("GET", f"/auth/v1/admin/users/{c['id']}", key=SERVICE)
s3, rows = call("GET", f"/rest/v1/map_pets?owner_id=eq.{c['id']}&select=id", key=SERVICE)
check("deleting the account removes it and everything with it", s in (200, 204) and s2 == 404 and rows == [], (s, s2, rows))

print(f"\n{len(failures)} failed" if failures else "\nall map backend checks passed")
sys.exit(1 if failures else 0)
