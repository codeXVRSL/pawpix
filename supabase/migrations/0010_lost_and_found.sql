-- PawPixel Lost and Found: the one time an owner needs the neighbourhood.
--
-- An owner whose pet is missing raises an alert from the pet's page: the pet's pixel look, the
-- best album photos (the owner chooses them; this is the only place PawPixel ever uploads a
-- photo), a note, and where the pet was last seen. Everyone signed in sees active alerts within
-- a radius of where they are, with the photos, and can report a sighting (a spot, a note, maybe a
-- photo). The owner sees the sightings and closes the alert with "Safe home". A public, read-only
-- copy of the alert (lost_pet_public) backs the share link, so a finder without the app can see it.
--
-- Privacy: the last-seen spot is where the pet was lost, not the owner's home, and the owner
-- chooses it. Owners are never named; contact goes through the app (sightings), never a phone
-- number on a flyer. Alerts close themselves after 60 days. Photos are small JPEGs the app
-- re-encodes (EXIF, and so GPS, dropped) and sends as base64 text: at most 3, 120 KB each.

create table public.lost_pets (
  id            uuid primary key default gen_random_uuid(),
  owner_id      uuid not null references auth.users on delete cascade,
  name          text not null check (char_length(name) between 1 and 24),
  species       text not null check (species in ('DOG', 'CAT', 'OTHER')),
  ears          text check (ears is null or ears in ('POINTY', 'FLOPPY')),
  look          text not null default '' check (char_length(look) <= 400 and look ~ '^[A-Za-z0-9;,.]*$'),
  description   text check (description is null or char_length(description) <= 300),
  photos        text[] not null default '{}' check (cardinality(photos) <= 3),
  last_seen_lat double precision not null check (last_seen_lat between -90 and 90),
  last_seen_lng double precision not null check (last_seen_lng between -180 and 180),
  last_seen_at  timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  found_at      timestamptz
);
create index lost_pets_active on public.lost_pets (created_at) where found_at is null;

create table public.lost_sightings (
  id          uuid primary key default gen_random_uuid(),
  lost_id     uuid not null references public.lost_pets on delete cascade,
  reporter_id uuid not null references auth.users on delete cascade,
  lat         double precision not null check (lat between -90 and 90),
  lng         double precision not null check (lng between -180 and 180),
  note        text check (note is null or char_length(note) <= 300),
  photo       text check (photo is null or char_length(photo) <= 160000),
  created_at  timestamptz not null default now()
);
create index lost_sightings_by_alert on public.lost_sightings (lost_id, created_at);

alter table public.lost_pets enable row level security;
alter table public.lost_sightings enable row level security;
-- No direct table access: every read and write goes through the functions below.

-- Photos travel as base64 JPEG text; the limit keeps the row small and the share page fast.
create or replace function public.pawpixel_photos_ok(p text[]) returns boolean language sql immutable as $$
  select p is null or (cardinality(p) <= 3 and not exists (
    select 1 from unnest(p) x where char_length(x) > 160000 or x !~ '^[A-Za-z0-9+/=]*$'));
$$;

-- Raise an alert. Any signed-in owner who isn't banned from the map; at most 3 open at a time.
-- The name passes the same word filter as map pets.
create or replace function public.report_lost(
  p_name text, p_species text, p_ears text, p_look text, p_description text,
  p_lat double precision, p_lng double precision, p_last_seen_at timestamptz default now(),
  p_photos text[] default '{}'
) returns uuid
language plpgsql security definer set search_path = public as $$
declare new_id uuid; nm text;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if exists (select 1 from map_profiles where user_id = auth.uid() and banned) then
    raise exception 'not allowed' using errcode = 'insufficient_privilege';
  end if;
  if (select count(*) from lost_pets where owner_id = auth.uid() and found_at is null) >= 3 then
    raise exception 'you already have 3 open alerts' using errcode = 'check_violation';
  end if;
  if not pawpixel_photos_ok(p_photos) then raise exception 'photos: at most 3, 120 KB each' using errcode = 'check_violation'; end if;
  nm := left(btrim(coalesce(p_name, '')), 24);
  if nm = '' then nm := 'Pet'; end if;
  if pawpixel_name_blocked(nm) then
    nm := case p_species when 'DOG' then 'A dog' when 'CAT' then 'A cat' else 'A pet' end;
  end if;
  insert into lost_pets (owner_id, name, species, ears, look, description, photos, last_seen_lat, last_seen_lng, last_seen_at)
  values (auth.uid(), nm, coalesce(p_species, 'OTHER'), p_ears, coalesce(p_look, ''), nullif(left(btrim(p_description), 300), ''),
          coalesce(p_photos, '{}'), p_lat, p_lng, least(coalesce(p_last_seen_at, now()), now()))
  returning id into new_id;
  return new_id;
end $$;

-- Open alerts within p_radius_km of a spot (the caller's area), nearest first, without the photos
-- (lost_details has them). Alerts older than 60 days are left out.
create or replace function public.lost_nearby(p_lat double precision, p_lng double precision, p_radius_km double precision default 15)
returns table (
  id uuid, name text, species text, ears text, look text, description text,
  last_seen_lat double precision, last_seen_lng double precision, last_seen_at timestamptz, created_at timestamptz,
  photo_count int, sightings int, mine boolean, distance_km double precision
)
language sql stable security definer set search_path = public as $$
  select l.id, l.name, l.species, l.ears, l.look, l.description, l.last_seen_lat, l.last_seen_lng, l.last_seen_at, l.created_at,
         cardinality(l.photos)::int, (select count(*) from lost_sightings s where s.lost_id = l.id)::int,
         l.owner_id = auth.uid(),
         111.32 * sqrt(power(l.last_seen_lat - p_lat, 2) + power((l.last_seen_lng - p_lng) * cos(radians(p_lat)), 2))
  from lost_pets l
  where auth.uid() is not null and l.found_at is null and l.created_at > now() - interval '60 days'
    and 111.32 * sqrt(power(l.last_seen_lat - p_lat, 2) + power((l.last_seen_lng - p_lng) * cos(radians(p_lat)), 2)) <= least(greatest(coalesce(p_radius_km, 15), 1), 100)
    and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = l.owner_id)
                                             or (b.blocker_id = l.owner_id and b.blocked_id = auth.uid()))
  order by 14
  limit 50;
$$;

-- One alert with its photos, for anyone signed in (an open alert) or its owner (any time).
create or replace function public.lost_details(p_id uuid)
returns table (
  id uuid, name text, species text, ears text, look text, description text, photos text[],
  last_seen_lat double precision, last_seen_lng double precision, last_seen_at timestamptz, created_at timestamptz,
  found_at timestamptz, mine boolean
)
language sql stable security definer set search_path = public as $$
  select l.id, l.name, l.species, l.ears, l.look, l.description, l.photos, l.last_seen_lat, l.last_seen_lng, l.last_seen_at,
         l.created_at, l.found_at, l.owner_id = auth.uid()
  from lost_pets l
  where l.id = p_id and auth.uid() is not null and (l.owner_id = auth.uid() or l.found_at is null);
$$;

-- The share link's page: no sign-in, no owner, no sightings. Found pets show as found (the page
-- can say so) for 30 days, then vanish.
create or replace function public.lost_pet_public(p_id uuid)
returns table (
  name text, species text, ears text, look text, description text, photos text[],
  last_seen_lat double precision, last_seen_lng double precision, last_seen_at timestamptz, found boolean
)
language sql stable security definer set search_path = public as $$
  select l.name, l.species, l.ears, l.look, l.description, l.photos, l.last_seen_lat, l.last_seen_lng, l.last_seen_at, l.found_at is not null
  from lost_pets l
  where l.id = p_id and coalesce(l.found_at, now()) > now() - interval '30 days' and l.created_at > now() - interval '60 days';
$$;

-- "I saw them": a spot, a note (how to reach you, if you like), maybe a photo. Signed in, not the
-- owner's own alert, at most 20 sightings per reporter per day.
create or replace function public.report_sighting(p_lost_id uuid, p_lat double precision, p_lng double precision, p_note text default null, p_photo text default null)
returns uuid
language plpgsql security definer set search_path = public as $$
declare new_id uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if not exists (select 1 from lost_pets l where l.id = p_lost_id and l.found_at is null) then
    raise exception 'this alert is closed' using errcode = 'check_violation';
  end if;
  if (select count(*) from lost_sightings where reporter_id = auth.uid() and created_at > now() - interval '1 day') >= 20 then
    raise exception 'too many sightings today' using errcode = 'check_violation';
  end if;
  if p_photo is not null and (char_length(p_photo) > 160000 or p_photo !~ '^[A-Za-z0-9+/=]*$') then
    raise exception 'photo too big' using errcode = 'check_violation';
  end if;
  insert into lost_sightings (lost_id, reporter_id, lat, lng, note, photo)
  values (p_lost_id, auth.uid(), p_lat, p_lng, nullif(left(btrim(p_note), 300), ''), p_photo)
  returning id into new_id;
  return new_id;
end $$;

-- The owner's view of the sightings on their alert: where, when, the note and photo; never who.
create or replace function public.lost_sightings_for(p_lost_id uuid)
returns table (id uuid, lat double precision, lng double precision, note text, photo text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select s.id, s.lat, s.lng, s.note, s.photo, s.created_at
  from lost_sightings s join lost_pets l on l.id = s.lost_id
  where s.lost_id = p_lost_id and l.owner_id = auth.uid()
  order by s.created_at desc limit 100;
$$;

-- Safe home: the alert closes; the share page says "found" for a month.
create or replace function public.mark_found(p_id uuid) returns void
language sql security definer set search_path = public as $$
  update lost_pets set found_at = now() where id = p_id and owner_id = auth.uid() and found_at is null;
$$;

-- Remove an alert and its sightings entirely (raised by mistake).
create or replace function public.cancel_lost(p_id uuid) returns void
language sql security definer set search_path = public as $$
  delete from lost_pets where id = p_id and owner_id = auth.uid();
$$;

-- The owner's own alerts, open or found, newest first.
create or replace view public.my_lost_pets as
  select l.id, l.name, l.species, l.created_at, l.found_at, l.last_seen_at,
         (select count(*) from lost_sightings s where s.lost_id = l.id)::int as sightings
  from lost_pets l where l.owner_id = auth.uid() order by l.created_at desc;

-- Deleting the account takes the alerts and sightings with it (cascade from auth.users), and
-- leave_map doesn't touch them: a lost pet matters more than being on the map.

revoke all on function public.report_lost, public.lost_nearby, public.lost_details, public.report_sighting,
  public.lost_sightings_for, public.mark_found, public.cancel_lost, public.pawpixel_photos_ok from public, anon;
grant execute on function public.report_lost, public.lost_nearby, public.lost_details, public.report_sighting,
  public.lost_sightings_for, public.mark_found, public.cancel_lost to authenticated;
revoke all on function public.lost_pet_public from public;
grant execute on function public.lost_pet_public to anon, authenticated;
revoke all on public.my_lost_pets from public, anon;
grant select on public.my_lost_pets to authenticated;
