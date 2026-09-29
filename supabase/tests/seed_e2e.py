#!/usr/bin/env python3
"""Seeds a local Supabase for the Android end-to-end run of the pet map.

Creates the app's test account, 3 other owners (with pixel pets) in every ~1 km square within
3 km of Naga City (Android fuzzes "approximate" location by up to ~2 km, so the test owner's
square always has company), and one approved gathering. Env: SUPABASE_URL, SERVICE_KEY,
TEST_EMAIL, TEST_PASSWORD.
"""
import json, math, os, urllib.request, uuid
from concurrent.futures import ThreadPoolExecutor

URL = os.environ["SUPABASE_URL"].rstrip("/")
SERVICE = os.environ["SERVICE_KEY"]
NAGA = (13.6218, 123.1948)
NAMES = ["Biscuit", "Tala", "Mochi", "Kape", "Luna", "Bantay", "Choco", "Ube", "Mingming"]
LOOKS = ["1;d9a441;" + "0" * 64, "2;222226,f4f1ea;".replace("2;", "1;") + "0" * 36 + "1" * 28,
         "1;f4f1ea,7e4823;" + "1100000011000000" + "0" * 48, "1;898f9a,f4f1ea;" + "0" * 40 + "1" * 24]


def call(method, path, body=None, prefer=None):
    headers = {"apikey": SERVICE, "Authorization": f"Bearer {SERVICE}", "Content-Type": "application/json"}
    if prefer: headers["Prefer"] = prefer
    req = urllib.request.Request(URL + path, None if body is None else json.dumps(body).encode(), method=method, headers=headers)
    with urllib.request.urlopen(req) as r:
        raw = r.read().decode()
        return json.loads(raw) if raw else None


def snap(lat, lng, km=1.0):
    """Same grid as core/LocationGrid.snap."""
    lat_step = km / 111.32
    row = math.floor((lat + 90.0) / lat_step)
    center_lat = -90.0 + (row + 0.5) * lat_step
    lng_step = lat_step / max(math.cos(math.radians(center_lat)), 0.01)
    col = math.floor((lng + 180.0) / lng_step)
    return f"g{int(km * 1000)}:{row}:{col}", center_lat, -180.0 + (col + 0.5) * lng_step


def user(email, password):
    return call("POST", "/auth/v1/admin/users", {"email": email, "password": password, "email_confirm": True})["id"]


def owner(cell, i):
    uid = user(f"seed-{uuid.uuid4().hex[:8]}@test.pawpixel", "seed-pass-123")
    call("POST", "/rest/v1/map_profiles", {"user_id": uid, "confirmed_adult": True})
    call("POST", "/rest/v1/map_pets", {"owner_id": uid, "name": NAMES[i % len(NAMES)], "species": "DOG" if i % 2 else "CAT",
                                        "ears": "FLOPPY" if i % 2 else "POINTY", "look": LOOKS[i % len(LOOKS)], "local_id": "seed"})
    call("POST", "/rest/v1/map_presence", {"owner_id": uid, "cell_id": cell[0], "cell_lat": cell[1], "cell_lng": cell[2]})
    return uid


user(os.environ["TEST_EMAIL"], os.environ["TEST_PASSWORD"])
# A second person for the family-sharing step (the test signs in as them to join and tap Done).
user("partner@test.pawpixel", "partner-pass-123")
cells = set()
for dr in range(-3, 4):
    for dc in range(-3, 4):
        cells.add(snap(NAGA[0] + dr / 111.32, NAGA[1] + dc / (111.32 * math.cos(math.radians(NAGA[0])))))
jobs = [(c, i) for c in sorted(cells) for i in range(3)]
with ThreadPoolExecutor(8) as pool:
    ids = list(pool.map(lambda job: owner(*job), jobs))
home = snap(*NAGA)
call("POST", "/rest/v1/gatherings", {"host_id": ids[0], "title": "Sunday pet walk at Plaza Rizal", "starts_at": "2099-01-04T00:00:00Z",
                                     "cell_id": home[0], "area_label": "Plaza Rizal area", "venue_name": "Plaza Rizal fountain",
                                     "venue_lat": 13.6238, "venue_lng": 123.1851, "capacity": 30, "approved": True})
print(f"seeded {len(ids)} owners in {len(cells)} areas around Naga, and one gathering")
