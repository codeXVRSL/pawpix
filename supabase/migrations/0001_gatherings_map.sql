-- PawPixel: pet gatherings map (Phase 3 — Naga pilot). NOT used by the MVP app yet.
--
-- Privacy design (see docs/MAP_SAFETY.md):
--  * Exact coordinates never leave the phone. The app snaps location to a ~1 km grid cell
--    (core/LocationGrid.kt) and uploads only the cell id + cell centre.
--  * A cell is only visible when at least K (=3) opted-in owners share it, so a lone owner
--    can't be singled out.
--  * No distance-sorted user lists. Users see cells ("5 pets nearby") and public events.
--  * Gatherings happen at public venues only; the exact venue is revealed after RSVP.
--  * 18+ only for the map. The app stores just an "is adult" confirmation, not a birthdate.


-- One row per owner who opted in. Deleting the auth user removes everything (cascade).
create table public.map_profiles (
  user_id        uuid primary key references auth.users on delete cascade,
  display_name   text not null check (char_length(display_name) between 1 and 30),
  confirmed_adult boolean not null check (confirmed_adult),
  consented_at   timestamptz not null default now(),   -- Data Privacy Act: explicit, specific consent
  created_at     timestamptz not null default now()
);

create table public.map_pets (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null references public.map_profiles(user_id) on delete cascade,
  name        text not null check (char_length(name) between 1 and 24),
  species     text not null check (species in ('DOG', 'CAT', 'OTHER')),
  sprite_path text,          -- pixel sprite in storage; the real photo is optional and owner-chosen
  photo_path  text,          -- uploaded with EXIF/GPS stripped on-device
  created_at  timestamptz not null default now()
);

-- Presence: only a grid cell, refreshed when the owner opens the map. Expires after 14 days.
create table public.map_presence (
  owner_id    uuid primary key references public.map_profiles(user_id) on delete cascade,
  cell_id     text not null check (cell_id ~ '^g[0-9]+:-?[0-9]+:-?[0-9]+$'),
  cell_lat    double precision not null check (cell_lat between -90 and 90),
  cell_lng    double precision not null check (cell_lng between -180 and 180),
  updated_at  timestamptz not null default now()
);
create index map_presence_cell on public.map_presence(cell_id);

create table public.blocks (
  blocker_id uuid not null references auth.users on delete cascade,
  blocked_id uuid not null references auth.users on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id)
);

create table public.reports (
  id          uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references auth.users on delete cascade,
  target_user uuid references auth.users on delete set null,
  target_pet  uuid references public.map_pets on delete set null,
  target_event uuid,
  reason      text not null check (reason in ('spam','harassment','unsafe','fake','child_safety','other')),
  details     text check (char_length(details) <= 1000),
  status      text not null default 'open' check (status in ('open','actioned','dismissed')),
  created_at  timestamptz not null default now()
);

-- Public gatherings (parks, pet-friendly malls, cafés). Created by the founder/hosts in the pilot.
create table public.gatherings (
  id           uuid primary key default gen_random_uuid(),
  host_id      uuid not null references public.map_profiles(user_id) on delete cascade,
  title        text not null check (char_length(title) between 3 and 80),
  starts_at    timestamptz not null,
  cell_id      text not null,             -- shown before RSVP
  area_label   text not null,             -- e.g. "Plaza Rizal area"
  venue_name   text not null,             -- revealed after RSVP
  venue_lat    double precision not null,
  venue_lng    double precision not null,
  capacity     int not null default 30 check (capacity between 2 and 200),
  approved     boolean not null default false,  -- moderator approves venue is public
  created_at   timestamptz not null default now()
);

create table public.rsvps (
  gathering_id uuid not null references public.gatherings on delete cascade,
  user_id      uuid not null references public.map_profiles(user_id) on delete cascade,
  created_at   timestamptz not null default now(),
  primary key (gathering_id, user_id)
);

-- ---------- Row level security: nothing is readable directly except your own rows ----------
alter table public.map_profiles enable row level security;
alter table public.map_pets     enable row level security;
alter table public.map_presence enable row level security;
alter table public.blocks       enable row level security;
alter table public.reports      enable row level security;
alter table public.gatherings   enable row level security;
alter table public.rsvps        enable row level security;

create policy own_profile on public.map_profiles for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy own_pets    on public.map_pets     for all using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy own_presence on public.map_presence for all using (owner_id = auth.uid()) with check (owner_id = auth.uid());
create policy own_blocks  on public.blocks       for all using (blocker_id = auth.uid()) with check (blocker_id = auth.uid());
create policy file_report on public.reports      for insert with check (reporter_id = auth.uid());
create policy own_rsvp    on public.rsvps        for all using (user_id = auth.uid()) with check (user_id = auth.uid());
-- No direct read policy on gatherings: venue columns must stay hidden. Clients read the
-- `gatherings_public` view (runs with the owner's rights, exposes no venue) and call
-- gathering_details() after RSVP.
create policy host_gatherings on public.gatherings for all using (host_id = auth.uid()) with check (host_id = auth.uid() and not approved);

-- ---------- Read paths (security definer functions enforce the privacy rules) ----------

-- Cells near a given cell with at least k owners, excluding blocked users. No user ids returned.
create or replace function public.nearby_cells(p_cell_lat double precision, p_cell_lng double precision, p_radius_km double precision default 10)
returns table (cell_id text, cell_lat double precision, cell_lng double precision, pets int)
language sql stable security definer set search_path = public as $$
  with visible as (
    select p.owner_id, p.cell_id, p.cell_lat, p.cell_lng
    from map_presence p
    where p.updated_at > now() - interval '14 days'
      and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = p.owner_id)
                                             or (b.blocker_id = p.owner_id and b.blocked_id = auth.uid()))
  )
  select v.cell_id, min(v.cell_lat), min(v.cell_lng), count(distinct pet.id)::int
  from visible v join map_pets pet on pet.owner_id = v.owner_id
  where 2 * 6371 * asin(sqrt(power(sin(radians(v.cell_lat - p_cell_lat) / 2), 2)
        + cos(radians(p_cell_lat)) * cos(radians(v.cell_lat)) * power(sin(radians(v.cell_lng - p_cell_lng) / 2), 2))) <= least(p_radius_km, 25)
  group by v.cell_id
  having count(distinct v.owner_id) >= 3;   -- k-anonymity
$$;

-- Pets in a cell (names, sprites, optional photos) — only for cells that pass the k threshold.
-- Order is random, never by distance, so it leaks nothing about who is closest.
create or replace function public.pets_in_cell(p_cell_id text)
returns table (pet_id uuid, name text, species text, sprite_path text, photo_path text)
language sql stable security definer set search_path = public as $$
  with visible as (
    select p.owner_id from map_presence p
    where p.cell_id = p_cell_id
      and p.updated_at > now() - interval '14 days'
      and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = p.owner_id)
                                             or (b.blocker_id = p.owner_id and b.blocked_id = auth.uid()))
  )
  select pet.id, pet.name, pet.species, pet.sprite_path, pet.photo_path
  from visible v join map_pets pet on pet.owner_id = v.owner_id
  where (select count(*) from visible) >= 3   -- same k-anonymity rule as nearby_cells
  order by random()
  limit 50;
$$;

create view public.gatherings_public as
  select id, title, starts_at, cell_id, area_label, capacity,
         (select count(*) from rsvps r where r.gathering_id = g.id)::int as going
  from gatherings g where approved and starts_at > now() - interval '3 hours';

-- Exact venue only for people who RSVP'd (or the host).
create or replace function public.gathering_details(p_id uuid)
returns table (venue_name text, venue_lat double precision, venue_lng double precision)
language sql stable security definer set search_path = public as $$
  select g.venue_name, g.venue_lat, g.venue_lng from gatherings g
  where g.id = p_id and g.approved
    and (g.host_id = auth.uid() or exists (select 1 from rsvps r where r.gathering_id = p_id and r.user_id = auth.uid()));
$$;

-- Leaving the map removes presence, pets and profile in one call (account deletion also cascades).
create or replace function public.leave_map()
returns void language sql security definer set search_path = public as $$
  delete from map_profiles where user_id = auth.uid();
$$;

revoke all on function public.nearby_cells, public.pets_in_cell, public.gathering_details, public.leave_map from public, anon;
grant execute on function public.nearby_cells, public.pets_in_cell, public.gathering_details, public.leave_map to authenticated;

revoke all on public.gatherings_public from public, anon;
grant select on public.gatherings_public to authenticated;
