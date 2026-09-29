-- PawPixel map, pilot release: pixel pets only, block/report by pet, RSVP with capacity,
-- moderator bans, and in-app account deletion (required by Google Play and the App Store).
--
-- Pets on the map are never photos. The app uploads a short "look code" (up to three fur colours
-- and where they sit on the face, see PetLook.encode) and every phone redraws the pixel pet from it.

-- ---------- Pets: look codes instead of sprite/photo files ----------
alter table public.map_pets drop column if exists sprite_path;
alter table public.map_pets drop column if exists photo_path;
alter table public.map_pets add column look text not null default ''
  check (char_length(look) <= 200 and look ~ '^[A-Za-z0-9;,.]*$');
alter table public.map_pets add column ears text check (ears is null or ears in ('POINTY', 'FLOPPY'));
-- The app's own id for the pet, so re-joining updates instead of duplicating.
alter table public.map_pets add column local_id text check (local_id is null or char_length(local_id) <= 40);
alter table public.map_pets add constraint map_pets_owner_local unique (owner_id, local_id);

-- At most 5 pets per owner on the map.
create or replace function public.limit_map_pets() returns trigger language plpgsql as $$
begin
  if (select count(*) from public.map_pets where owner_id = new.owner_id) >= 5 then
    raise exception 'too many pets' using errcode = 'check_violation';
  end if;
  return new;
end $$;
create trigger map_pets_limit before insert on public.map_pets for each row execute function public.limit_map_pets();

-- Owners are never shown to others, so a display name isn't needed.
alter table public.map_profiles alter column display_name set default 'Owner';

-- ---------- Moderation ----------
-- A moderator (the founder, in the Supabase dashboard) can ban an owner: their pets vanish.
alter table public.map_profiles add column banned boolean not null default false;
-- Owners can't unban themselves: updates to their own profile may not touch `banned`.
create or replace function public.keep_banned() returns trigger language plpgsql as $$
begin
  if new.banned is distinct from old.banned and coalesce(current_setting('request.jwt.claims', true)::json ->> 'role', '') = 'authenticated' then
    raise exception 'not allowed';
  end if;
  return new;
end $$;
create trigger map_profiles_keep_banned before update on public.map_profiles for each row execute function public.keep_banned();

-- Owners visible to the caller: fresh presence, not banned, no block in either direction.
create or replace function public.visible_owners()
returns table (owner_id uuid, cell_id text, cell_lat double precision, cell_lng double precision)
language sql stable security definer set search_path = public as $$
  select p.owner_id, p.cell_id, p.cell_lat, p.cell_lng
  from map_presence p join map_profiles prof on prof.user_id = p.owner_id
  where p.updated_at > now() - interval '14 days'
    and not prof.banned
    and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = p.owner_id)
                                           or (b.blocker_id = p.owner_id and b.blocked_id = auth.uid()));
$$;
revoke all on function public.visible_owners from public, anon, authenticated;

-- ---------- Read paths, rebuilt on visible_owners ----------
drop function if exists public.nearby_cells(double precision, double precision, double precision);
create function public.nearby_cells(p_cell_lat double precision, p_cell_lng double precision, p_radius_km double precision default 10)
returns table (cell_id text, cell_lat double precision, cell_lng double precision, pets int)
language sql stable security definer set search_path = public as $$
  select v.cell_id, min(v.cell_lat), min(v.cell_lng), count(distinct pet.id)::int
  from visible_owners() v join map_pets pet on pet.owner_id = v.owner_id
  where 2 * 6371 * asin(sqrt(power(sin(radians(v.cell_lat - p_cell_lat) / 2), 2)
        + cos(radians(p_cell_lat)) * cos(radians(v.cell_lat)) * power(sin(radians(v.cell_lng - p_cell_lng) / 2), 2))) <= least(p_radius_km, 25)
  group by v.cell_id
  having count(distinct v.owner_id) >= 3;   -- k-anonymity
$$;

drop function if exists public.pets_in_cell(text);
-- Pets in a cell, in random order, never with owner ids. Only for cells with 3+ visible owners.
create function public.pets_in_cell(p_cell_id text)
returns table (pet_id uuid, name text, species text, ears text, look text, mine boolean)
language sql stable security definer set search_path = public as $$
  with here as (select v.owner_id from visible_owners() v where v.cell_id = p_cell_id)
  select pet.id, pet.name, pet.species, pet.ears, pet.look, pet.owner_id = auth.uid()
  from here h join map_pets pet on pet.owner_id = h.owner_id
  where (select count(*) from here) >= 3
  order by random()
  limit 50;
$$;

-- ---------- Block and report by pet (owner ids are never exposed) ----------
create function public.block_pet_owner(p_pet_id uuid) returns void
language sql security definer set search_path = public as $$
  insert into blocks (blocker_id, blocked_id)
  select auth.uid(), pet.owner_id from map_pets pet
  where pet.id = p_pet_id and pet.owner_id <> auth.uid()
  on conflict do nothing;
$$;

create function public.report_pet(p_pet_id uuid, p_reason text, p_details text default null) returns void
language sql security definer set search_path = public as $$
  insert into reports (reporter_id, target_user, target_pet, reason, details)
  select auth.uid(), pet.owner_id, pet.id, p_reason, left(p_details, 1000) from map_pets pet
  where pet.id = p_pet_id and pet.owner_id <> auth.uid();
$$;

-- ---------- Gatherings: RSVP with capacity; the list says whether you're going ----------
drop view if exists public.gatherings_public;
create view public.gatherings_public as
  select g.id, g.title, g.starts_at, g.cell_id, g.area_label, g.capacity,
         (select count(*) from rsvps r where r.gathering_id = g.id)::int as going,
         exists (select 1 from rsvps r where r.gathering_id = g.id and r.user_id = auth.uid()) as i_am_going
  from gatherings g where g.approved and g.starts_at > now() - interval '3 hours'
  order by g.starts_at;

-- Going (true) or not going (false). Returns how many are going. Needs a map profile.
create function public.rsvp(p_id uuid, p_going boolean) returns int
language plpgsql security definer set search_path = public as $$
declare g gatherings;
begin
  if not exists (select 1 from map_profiles where user_id = auth.uid() and not banned) then
    raise exception 'join the map first' using errcode = 'insufficient_privilege';
  end if;
  select * into g from gatherings where id = p_id and approved and starts_at > now() - interval '1 hour';
  if not found then raise exception 'no such gathering' using errcode = 'no_data_found'; end if;
  if p_going then
    if (select count(*) from rsvps where gathering_id = p_id) >= g.capacity
       and not exists (select 1 from rsvps where gathering_id = p_id and user_id = auth.uid()) then
      raise exception 'gathering is full' using errcode = 'check_violation';
    end if;
    insert into rsvps (gathering_id, user_id) values (p_id, auth.uid()) on conflict do nothing;
  else
    delete from rsvps where gathering_id = p_id and user_id = auth.uid();
  end if;
  return (select count(*) from rsvps where gathering_id = p_id);
end $$;

-- ---------- Leaving and deleting ----------
-- Deletes the sign-in account itself; everything else cascades from auth.users.
create function public.delete_account() returns void
language sql security definer set search_path = public, auth as $$
  delete from auth.users where id = auth.uid();
$$;

revoke all on function public.nearby_cells, public.pets_in_cell, public.block_pet_owner, public.report_pet,
  public.rsvp, public.delete_account from public, anon;
grant execute on function public.nearby_cells, public.pets_in_cell, public.block_pet_owner, public.report_pet,
  public.rsvp, public.delete_account to authenticated;
revoke all on public.gatherings_public from public, anon;
grant select on public.gatherings_public to authenticated;
-- Your own pets and presence you manage directly (row level security keeps them yours).
grant select, insert, update, delete on public.map_profiles, public.map_pets, public.map_presence to authenticated;
