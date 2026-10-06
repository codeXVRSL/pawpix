-- PawPixel map: the community hosts its own walks, and sees how big it is.
--
-- Until now gatherings were rows the moderator typed into the dashboard. Now any owner on the map
-- can propose a walk from the app ("Host a walk"): it waits for the moderator's approval (a public
-- venue, a sensible time), then everyone sees it like any other gathering. Hosts see their own
-- proposals and can cancel them. Nothing else about the privacy design changes: a walk shows its
-- ~1 km area before RSVP and its exact venue after, exactly like a moderator's.

-- What a host tells people about the walk, and where its area is on the map (a pin for it).
alter table public.gatherings add column if not exists details text check (details is null or char_length(details) <= 300);
alter table public.gatherings add column if not exists cell_lat double precision check (cell_lat is null or cell_lat between -90 and 90);
alter table public.gatherings add column if not exists cell_lng double precision check (cell_lng is null or cell_lng between -180 and 180);

-- Propose a walk. Needs a map profile in good standing. The app sends the venue's exact spot
-- (a public place the host picked on the map) and the ~1 km cell it falls in, like its own area.
-- At most 3 proposals waiting at a time, and the walk must be in the next 90 days.
create or replace function public.host_walk(
  p_title text, p_starts_at timestamptz, p_cell_id text, p_cell_lat double precision, p_cell_lng double precision,
  p_area_label text, p_venue_name text, p_venue_lat double precision, p_venue_lng double precision,
  p_capacity int default 20, p_details text default null
) returns uuid
language plpgsql security definer set search_path = public as $$
declare new_id uuid;
begin
  if not exists (select 1 from map_profiles where user_id = auth.uid() and not banned) then
    raise exception 'join the map first' using errcode = 'insufficient_privilege';
  end if;
  if p_cell_id !~ '^g[0-9]+:-?[0-9]+:-?[0-9]+$' then
    raise exception 'not a grid cell' using errcode = 'check_violation';
  end if;
  if p_starts_at < now() + interval '1 hour' or p_starts_at > now() + interval '90 days' then
    raise exception 'the walk must be between an hour and 90 days from now' using errcode = 'check_violation';
  end if;
  if (select count(*) from gatherings where host_id = auth.uid() and not approved) >= 3 then
    raise exception 'you already have 3 walks waiting for approval' using errcode = 'check_violation';
  end if;
  insert into gatherings (host_id, title, starts_at, cell_id, cell_lat, cell_lng, area_label, venue_name, venue_lat, venue_lng, capacity, details, approved)
  values (auth.uid(), left(btrim(p_title), 80), p_starts_at, p_cell_id, p_cell_lat, p_cell_lng,
          left(btrim(p_area_label), 60), left(btrim(p_venue_name), 80), p_venue_lat, p_venue_lng,
          least(greatest(coalesce(p_capacity, 20), 2), 200), nullif(left(btrim(p_details), 300), ''), false)
  returning id into new_id;
  -- The host is going, of course.
  insert into rsvps (gathering_id, user_id) values (new_id, auth.uid()) on conflict do nothing;
  return new_id;
end $$;

-- A host's own walks, waiting or approved, with how many are going (exact venue included: it's theirs).
create or replace view public.my_walks as
  select g.id, g.title, g.starts_at, g.cell_id, g.area_label, g.venue_name, g.capacity, g.approved, g.details,
         (select count(*) from rsvps r where r.gathering_id = g.id)::int as going
  from gatherings g where g.host_id = auth.uid() and g.starts_at > now() - interval '3 hours'
  order by g.starts_at;

-- Cancelling your own walk (waiting or approved) removes it, and everyone's RSVP with it.
create or replace function public.cancel_walk(p_id uuid) returns void
language sql security definer set search_path = public as $$
  delete from gatherings where id = p_id and host_id = auth.uid();
$$;

-- The public list, now with the host's note, the area's pin and whether you're hosting.
drop view if exists public.gatherings_public;
create view public.gatherings_public as
  select g.id, g.title, g.starts_at, g.cell_id, g.cell_lat, g.cell_lng, g.area_label, g.capacity, g.details,
         (select count(*) from rsvps r where r.gathering_id = g.id)::int as going,
         exists (select 1 from rsvps r where r.gathering_id = g.id and r.user_id = auth.uid()) as i_am_going,
         g.host_id = auth.uid() as i_am_host
  from gatherings g where g.approved and g.starts_at > now() - interval '3 hours'
  order by g.starts_at;

-- How big the community is: whole-pilot totals only (owners with fresh presence who aren't banned,
-- their pets, the areas that have reached 3 owners, and walks coming up). No cell, no name, no id.
create or replace function public.community_stats()
returns table (owners int, pets int, areas int, walks int)
language sql stable security definer set search_path = public as $$
  with fresh as (
    select p.owner_id, p.cell_id from map_presence p join map_profiles prof on prof.user_id = p.owner_id
    where p.updated_at > now() - interval '14 days' and not prof.banned
  )
  select (select count(*) from fresh)::int,
         (select count(*) from map_pets pet where pet.owner_id in (select owner_id from fresh))::int,
         (select count(*) from (select cell_id from fresh group by cell_id having count(*) >= 3) a)::int,
         (select count(*) from gatherings g where g.approved and g.starts_at > now())::int;
$$;

revoke all on function public.host_walk, public.cancel_walk, public.community_stats from public, anon;
grant execute on function public.host_walk, public.cancel_walk, public.community_stats to authenticated;
revoke all on public.gatherings_public, public.my_walks from public, anon;
grant select on public.gatherings_public, public.my_walks to authenticated;
