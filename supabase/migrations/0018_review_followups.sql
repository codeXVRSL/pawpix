-- Follow-ups to 0017, after a review of the migrations.

-- 1. Pal codes are only guessed through add_pal_tracked (which counts misses); add_pal itself is
--    internal (add_pal_tracked calls it as the function owner).
revoke execute on function public.add_pal(text) from public, anon, authenticated;

-- 2. A banned owner can still delete their account: the ban guard only stops a direct delete of the
--    profile, not the cascade from auth.users (which runs inside another trigger).
create or replace function public.keep_banned_on_delete() returns trigger language plpgsql as $$
begin
  if pg_trigger_depth() > 1 then return old; end if; -- a cascade (account deletion): always allowed
  if old.banned and coalesce(current_setting('request.jwt.claims', true)::json ->> 'role', '') = 'authenticated' then
    raise exception 'not allowed';
  end if;
  return old;
end $$;

-- 3. Area changes are counted per owner per day in their own table, so deleting and re-inserting
--    the presence row doesn't reset the count. The first placement of the day and three moves are
--    allowed. Only owners are limited (the service role and moderators aren't).
create table if not exists public.presence_moves (
  owner_id uuid primary key references auth.users on delete cascade,
  day      date not null default current_date,
  moves    int not null default 0
);
alter table public.presence_moves enable row level security;

drop trigger if exists map_presence_limit_moves on public.map_presence;
create or replace function public.limit_presence_moves() returns trigger
language plpgsql security definer set search_path = public as $$
declare n int;
begin
  if coalesce(current_setting('request.jwt.claims', true)::json ->> 'role', '') <> 'authenticated' then return new; end if;
  if tg_op = 'UPDATE' and new.cell_id is not distinct from old.cell_id then return new; end if;
  -- An upsert fires the insert trigger and then the update trigger: count it once, in the update.
  if tg_op = 'INSERT' and exists (select 1 from map_presence where owner_id = new.owner_id) then return new; end if;
  insert into presence_moves (owner_id, day, moves) values (new.owner_id, current_date, 0)
  on conflict (owner_id) do update set moves = case when presence_moves.day = current_date then presence_moves.moves else 0 end, day = current_date;
  select moves into n from presence_moves where owner_id = new.owner_id;
  if n >= 4 then
    raise exception 'you can change your area three times a day' using errcode = 'check_violation';
  end if;
  update presence_moves set moves = moves + 1 where owner_id = new.owner_id;
  return new;
end $$;
create trigger map_presence_limit_moves before insert or update on public.map_presence for each row execute function public.limit_presence_moves();

-- 4. A host sees (and can cancel) walks still waiting for approval after their time has passed;
--    otherwise three stale proposals would block hosting for good.
create or replace view public.my_walks as
  select g.id, g.title, g.starts_at, g.cell_id, g.area_label, g.venue_name, g.capacity, g.approved, g.details,
         (select count(*) from rsvps r where r.gathering_id = g.id)::int as going
  from gatherings g where g.host_id = auth.uid() and (g.starts_at > now() - interval '3 hours' or not g.approved)
  order by g.starts_at;

-- 5. Lost alerts: an alert over 60 days old has closed itself, so it neither blocks a new one nor takes sightings.
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
  -- Alerts older than 60 days have closed themselves (nobody sees them): they don't count.
  if (select count(*) from lost_pets where owner_id = auth.uid() and found_at is null and created_at > now() - interval '60 days') >= 3 then
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

create or replace function public.report_sighting(p_lost_id uuid, p_lat double precision, p_lng double precision, p_note text default null, p_photo text default null)
returns uuid
language plpgsql security definer set search_path = public as $$
declare new_id uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if not exists (select 1 from lost_pets l where l.id = p_lost_id and l.found_at is null and l.owner_id <> auth.uid() and l.created_at > now() - interval '60 days') then
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

-- 6. Finder messages on a Pet ID card are never refused for volume (a prankster could otherwise lock
--    out the real finder): the newest 100 per card are kept, older ones go.
create or replace function public.pet_card_message(p_id uuid, p_text text, p_contact text default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from pet_cards where id = p_id) then raise exception 'no such card' using errcode = 'check_violation'; end if;
  if btrim(coalesce(p_text, '')) = '' then raise exception 'empty message' using errcode = 'check_violation'; end if;
  insert into pet_card_messages (card_id, text, contact) values (p_id, left(btrim(p_text), 500), nullif(left(btrim(p_contact), 120), ''));
  delete from pet_card_messages where card_id = p_id and id not in (
    select id from pet_card_messages where card_id = p_id order by created_at desc limit 100
  );
end $$;

-- 7. A card's local id is stored cut to 40 characters: removing matches the same cut.
create or replace function public.remove_pet_card(p_local_id text) returns void
language sql security definer set search_path = public as $$
  delete from pet_cards where owner_id = auth.uid() and local_id = left(p_local_id, 40);
$$;
