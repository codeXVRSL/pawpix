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
# An owner's area can move three times a day (0017): a puppet account can't sweep a city for lone owners.
moves = [call("POST", "/rest/v1/map_presence", {"owner_id": e["id"], "cell_id": f"g1000:{9400 + i}:27600", "cell_lat": 13.7, "cell_lng": 123.2 + i / 100}, e["token"], prefer="resolution=merge-duplicates")[0] for i in range(5)]
check("the fourth change of area in a day is refused", all(m in (200, 201, 204) for m in moves[:3]) and all(m >= 400 for m in moves[3:]), moves)

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
s, r = call("POST", "/rest/v1/rsvps", {"gathering_id": gid, "user_id": a["id"]}, token=a["token"])
check("an RSVP can't be inserted directly, only through rsvp() (0017)", s >= 400, (s, r))
s, r = call("POST", "/rest/v1/gatherings", {"host_id": a["id"], "title": "Sneaky walk", "starts_at": "2099-01-01T08:00:00Z", "cell_id": "x",
                                             "area_label": "", "venue_name": "", "venue_lat": 0, "venue_lng": 0, "capacity": 2, "approved": False}, token=a["token"])
check("a walk can't be inserted directly, only proposed through host_walk (0016)", s >= 400, (s, r))
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

# Who's coming, as pixel pets (0013): the RSVPed owners' map pets, never the owners.
s, going = rpc(c, "gathering_pets", {"p_id": gid})
check("a walk shows the pixel pets of the owners going, with no owner ids",
      s == 200 and len(going) >= 1 and all(set(x) == {"pet_id", "name", "species", "ears", "look", "mine"} for x in going), (s, going))
s, anon_going = call("POST", "/rest/v1/rpc/gathering_pets", {"p_id": gid})
check("signed-out callers see nobody", s in (401, 403) or anon_going == [], (s, anon_going))

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

# Lost and Found (0010): anyone signed in can raise an alert, everyone nearby sees it, sightings
# reach the owner, the share page is public, and "found" closes it.
PHOTO = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP" + "A" * 40 + "=="
lost = {"p_name": "Kape", "p_species": "DOG", "p_ears": "FLOPPY", "p_look": LOOK, "p_description": "Brown, red collar, answers to Kape",
        "p_lat": NAGA[1] + 0.004, "p_lng": NAGA[2] - 0.002, "p_photos": [PHOTO]}
s, lid = rpc(d, "report_lost", lost)
check("a signed-in owner (even one not on the map) can raise a lost alert", s == 200 and isinstance(lid, str), (s, lid))
s, rows = call("POST", "/rest/v1/rpc/report_lost", lost)
check("a signed-out caller can't", s in (401, 403) or not isinstance(rows, str), (s, rows))
s, near = rpc(a, "lost_nearby", {"p_lat": NAGA[1], "p_lng": NAGA[2]})
check("owners nearby see the alert, nearest first, without photos or owner",
      s == 200 and [x["id"] for x in near] == [lid] and near[0]["photo_count"] == 1 and near[0]["mine"] is False
      and "photos" not in near[0] and "owner_id" not in near[0] and near[0]["distance_km"] < 1, (s, near))
s, far = rpc(a, "lost_nearby", {"p_lat": 10.0, "p_lng": 120.0})
check("far away it doesn't show", s == 200 and far == [], (s, far))
s, det = rpc(a, "lost_details", {"p_id": lid})
check("the details carry the photos", s == 200 and len(det) == 1 and det[0]["photos"] == [PHOTO] and det[0]["mine"] is False, (s, det))
s, pub = call("POST", "/rest/v1/rpc/lost_pet_public", {"p_id": lid})
check("the share link's page reads the alert without signing in, owner left out",
      s == 200 and len(pub) == 1 and pub[0]["name"] == "Kape" and pub[0]["found"] is False and "owner_id" not in pub[0] and "id" not in pub[0], (s, pub))
s, sid = rpc(a, "report_sighting", {"p_lost_id": lid, "p_lat": NAGA[1] + 0.005, "p_lng": NAGA[2], "p_note": "Saw him near the plaza, call 0917..."})
check("a sighting can be reported", s == 200 and isinstance(sid, str), (s, sid))
s, r = rpc(d, "report_sighting", {"p_lost_id": lid, "p_lat": NAGA[1], "p_lng": NAGA[2]})
check("the owner can't report a sighting on their own alert (0015)", s >= 400, (s, r))
s, seen = rpc(d, "lost_sightings_for", {"p_lost_id": lid})
check("the owner sees the sightings (where, when, the note), never who",
      s == 200 and len(seen) == 1 and seen[0]["note"].startswith("Saw him") and "reporter_id" not in seen[0], (s, seen))
s, other = rpc(a, "lost_sightings_for", {"p_lost_id": lid})
check("nobody else can read them", s == 200 and other == [], (s, other))
s, r = rpc(d, "report_lost", dict(lost, p_photos=[PHOTO] * 4))
check("at most 3 photos", s >= 400, (s, r))
s, r = rpc(d, "report_lost", dict(lost, p_name="G4g0"))
s2, r2 = rpc(d, "lost_details", {"p_id": r})
check("alert names pass the word filter", s == 200 and s2 == 200 and r2[0]["name"] == "A dog", (s, r, r2))
rpc(d, "cancel_lost", {"p_id": r})
s, _ = rpc(a, "mark_found", {"p_id": lid})
s2, near = rpc(a, "lost_nearby", {"p_lat": NAGA[1], "p_lng": NAGA[2]})
check("only the owner can close an alert", near and near[0]["id"] == lid, (s, near))
s, _ = rpc(d, "mark_found", {"p_id": lid})
s2, near = rpc(a, "lost_nearby", {"p_lat": NAGA[1], "p_lng": NAGA[2]})
s3, pub = call("POST", "/rest/v1/rpc/lost_pet_public", {"p_id": lid})
check("safe home: the alert leaves the map and the share page says found",
      s in (200, 204) and near == [] and s3 == 200 and pub[0]["found"] is True, (s, near, pub))
s, mine = call("GET", "/rest/v1/my_lost_pets?select=*", token=d["token"])
check("the owner keeps their own alerts, with the sighting count", s == 200 and len(mine) == 1 and mine[0]["sightings"] == 1 and mine[0]["found_at"], (s, mine))
s, r = rpc(a, "report_sighting", {"p_lost_id": lid, "p_lat": NAGA[1], "p_lng": NAGA[2]})
check("a closed alert takes no more sightings", s >= 400, (s, r))
s, rows = call("GET", "/rest/v1/lost_pets?select=*", token=a["token"])
s2, rows2 = call("GET", "/rest/v1/lost_sightings?select=*", token=d["token"])
check("the tables themselves are closed", rows in ([], None) and rows2 in ([], None) or s >= 400 and s2 >= 400, (s, rows, s2, rows2))

# Pet ID card (0011): the owner makes a card, anyone with the link reads it and can message the owner.
s, cid = rpc(d, "upsert_pet_card", {"p_local_id": "pet-1", "p_name": "Kape", "p_species": "DOG", "p_ears": "FLOPPY", "p_look": LOOK, "p_note": "Friendly. On heart medication.", "p_microchip": "981020012345678"})
check("an owner makes an ID card for a pet", s == 200 and isinstance(cid, str), (s, cid))
s, again = rpc(d, "upsert_pet_card", {"p_local_id": "pet-1", "p_name": "Kape", "p_species": "DOG", "p_ears": "FLOPPY", "p_look": LOOK, "p_note": "Friendly!"})
check("making it again updates the same card", s == 200 and again == cid, (s, again))
s, pub = call("POST", "/rest/v1/rpc/pet_card_public", {"p_id": cid})
check("the card page reads without signing in, owner left out",
      s == 200 and len(pub) == 1 and pub[0]["name"] == "Kape" and pub[0]["note"] == "Friendly!" and "owner_id" not in pub[0], (s, pub))
s, _ = call("POST", "/rest/v1/rpc/pet_card_message", {"p_id": cid, "p_text": "Found Kape at the plaza, he's with me", "p_contact": "0917 555 0123"})
s2, msgs = rpc(d, "pet_card_messages_for", {"p_id": cid})
check("a finder's message reaches the owner", s in (200, 204) and s2 == 200 and len(msgs) == 1 and msgs[0]["contact"] == "0917 555 0123", (s, s2, msgs))
s, other = rpc(a, "pet_card_messages_for", {"p_id": cid})
check("nobody else reads them", s == 200 and other == [], (s, other))
s, r = call("POST", "/rest/v1/rpc/pet_card_message", {"p_id": cid, "p_text": "   "})
check("an empty message is refused", s >= 400, (s, r))
s, mine = call("GET", "/rest/v1/my_pet_cards?select=*", token=d["token"])
check("the owner lists their cards with the message count", s == 200 and len(mine) == 1 and mine[0]["messages"] == 1 and mine[0]["id"] == cid, (s, mine))
s, _ = rpc(d, "remove_pet_card", {"p_local_id": "pet-1"})
s2, pub = call("POST", "/rest/v1/rpc/pet_card_public", {"p_id": cid})
check("removing the card takes the page down", s in (200, 204) and pub == [], (s, pub))

# Pals (0012): a code, a circle of at most 20, each other's pixel pets, treats.
s, code = rpc(a, "my_pal_code")
s2, code2 = rpc(a, "my_pal_code")
check("an owner gets one six-character pal code", s == 200 and isinstance(code, str) and len(code) == 6 and code2 == code, (s, code, code2))
s, _ = rpc(a, "set_pal_pets", {"p_pets": [{"local_id": "p1", "name": "Mochi", "species": "CAT", "ears": "POINTY", "look": LOOK}, {"local_id": "p2", "name": "G4g0", "species": "DOG", "ears": "FLOPPY", "look": LOOK}]})
check("an owner sets the pets their pals see", s in (200, 204), s)
s, who = rpc(d, "add_pal", {"p_code": " " + code.lower() + " "})
check("a friend adds them by code (any case, spaces ignored)", s == 200 and who == a["id"], (s, who))
s, r = rpc(a, "add_pal", {"p_code": code})
check("your own code is refused", s >= 400, (s, r))
s, r = rpc(d, "add_pal", {"p_code": "ZZZZZZ"})
check("an unknown code is refused", s >= 400, (s, r))
s, pals = rpc(d, "pals_list")
check("pals see each other's pixel pets, names filtered, never a location",
      s == 200 and len(pals) == 2 and {p["name"] for p in pals} == {"Mochi", "A dog"} and all(p["pal_id"] == a["id"] for p in pals) and "cell_id" not in pals[0], (s, pals))
s, pals_a = rpc(a, "pals_list")
check("the other side sees the pal too (with no pets yet)", s == 200 and len(pals_a) == 1 and pals_a[0]["pal_id"] == d["id"] and pals_a[0]["pet_id"] is None, (s, pals_a))
s, _ = rpc(d, "send_treat", {"p_to_user": a["id"], "p_to_pet": "p1", "p_from_pet": "Kape", "p_kind": "ball"})
s2, inbox = rpc(a, "treats_inbox")
check("a treat reaches the pal's inbox", s in (200, 204) and s2 == 200 and len(inbox) == 1 and inbox[0]["to_pet"] == "p1" and inbox[0]["from_pet"] == "Kape" and inbox[0]["kind"] == "ball", (s, s2, inbox))
s, r = rpc(e, "send_treat", {"p_to_user": a["id"], "p_to_pet": "p1", "p_from_pet": "Pet0"})
check("only pals can send treats", s >= 400, (s, r))
# Moments (0014): one small photo a day for pals only, gone after two days.
PHOTO = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP" + "A" * 200 + "=="
s, _ = rpc(a, "set_moment", {"p_pet_name": "Mochi", "p_caption": "Sunday nap", "p_photo": PHOTO})
s2, moments = rpc(d, "pals_moments")
check("a pal sees the moment with the pet's name and caption", s in (200, 204) and s2 == 200 and len(moments) == 1 and moments[0]["pal_id"] == a["id"]
      and moments[0]["pet_name"] == "Mochi" and moments[0]["caption"] == "Sunday nap" and moments[0]["photo"] == PHOTO, (s, s2, moments))
s, _ = rpc(a, "set_moment", {"p_pet_name": "Mochi", "p_caption": "G4g0 says hi", "p_photo": PHOTO})
s2, moments = rpc(d, "pals_moments")
check("a new moment replaces the old one and a blocked caption is dropped", s in (200, 204) and len(moments) == 1 and moments[0]["caption"] == "", (s, moments))
s, r = rpc(a, "set_moment", {"p_pet_name": "Mochi", "p_caption": "", "p_photo": "not base64!"})
check("a moment must be a small base64 JPEG", s >= 400, (s, r))
s, others = rpc(e, "pals_moments")
check("non-pals see no moments", s == 200 and others == [], (s, others))
for _ in range(8): rpc(a, "set_moment", {"p_pet_name": "Mochi", "p_caption": "", "p_photo": PHOTO})
s, r = rpc(a, "set_moment", {"p_pet_name": "Mochi", "p_caption": "", "p_photo": PHOTO})
check("the eleventh moment of the day is refused", s >= 400, (s, r))
s, _ = rpc(a, "clear_moment")
s2, moments = rpc(d, "pals_moments")
check("the owner can take a moment down", s in (200, 204) and moments == [], (s, moments))
s, rows = call("GET", "/rest/v1/pal_moments?select=*", token=d["token"])
check("the moments table itself is closed", rows in ([], None) or s >= 400, (s, rows))

s, _ = rpc(d, "send_treat", {"p_to_user": a["id"], "p_to_pet": "p1", "p_from_pet": "G4g0", "p_kind": "pat"})
s2, inbox = rpc(a, "treats_inbox")
check("a treat's sender name goes through the word filter (0017)", s in (200, 204) and inbox[0]["from_pet"] == "A pal", (s, inbox))
s, _ = rpc(a, "remove_pal", {"p_user": d["id"]})
s2, pals = rpc(d, "pals_list")
check("either side can unpal", s in (200, 204) and pals == [], (s, pals))
s, r = rpc(d, "add_pal_tracked", {"p_code": code})
check("someone who unpalled you can't be re-added with their old code (0017)", s == 200 and r is None, (s, r))
s, code_a2 = rpc(a, "new_pal_code")
s2, r = rpc(e, "add_pal_tracked", {"p_code": code})
check("a replaced code stops working", s == 200 and code_a2 != code and len(code_a2) == 6 and r is None, (s, code_a2, r))
misses = [rpc(e, "add_pal_tracked", {"p_code": f"ZZZZ{i:02d}"})[0] for i in range(10)]
s, r = rpc(e, "add_pal_tracked", {"p_code": code_a2})
check("ten wrong codes in an hour lock guessing, even for a right one", all(m == 200 for m in misses) and s >= 400, (misses, s, r))
s, rows = call("GET", "/rest/v1/pal_pets?select=*", token=d["token"])
check("the pal tables themselves are closed", rows in ([], None) or s >= 400, (s, rows))

# Moderation and self-service
call("PATCH", f"/rest/v1/map_profiles?user_id=eq.{b['id']}", {"banned": True}, key=SERVICE)
s, cells = rpc(d, "nearby_cells", {"p_cell_lat": NAGA[1], "p_cell_lng": NAGA[2]})
check("a banned owner's pets disappear (area drops below 3)", s == 200 and cells == [], (s, cells))
s, _ = call("PATCH", f"/rest/v1/map_profiles?user_id=eq.{b['id']}", {"banned": False}, b["token"])
s2, rows = call("GET", f"/rest/v1/map_profiles?user_id=eq.{b['id']}&select=banned", key=SERVICE)
check("a banned owner can't unban themselves", s >= 400 and rows[0]["banned"] is True, (s, rows))
s, _ = call("DELETE", f"/rest/v1/map_profiles?user_id=eq.{b['id']}", token=b["token"])
rpc(b, "leave_map")
s2, rows = call("GET", f"/rest/v1/map_profiles?user_id=eq.{b['id']}&select=banned", key=SERVICE)
check("a banned owner can't delete the profile and come back clean (0017)", len(rows) == 1 and rows[0]["banned"] is True, (s, rows))
s, _ = call("POST", "/rest/v1/map_profiles", {"user_id": b["id"], "confirmed_adult": True}, b["token"], prefer="resolution=merge-duplicates")
s2, rows = call("GET", f"/rest/v1/map_profiles?user_id=eq.{b['id']}&select=banned", key=SERVICE)
check("re-joining keeps the ban", rows[0]["banned"] is True, (s, rows))

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
