-- Hardening after a security review.

-- 1. A ban survives leaving the map: a banned profile can't be deleted by its owner (and so can't be
--    re-created clean). leave_map() keeps the profile and clears presence and pets instead.
create or replace function public.keep_banned_on_delete() returns trigger language plpgsql as $$
begin
  if old.banned and coalesce(current_setting('request.jwt.claims', true)::json ->> 'role', '') = 'authenticated' then
    raise exception 'not allowed';
  end if;
  return old;
end $$;
drop trigger if exists map_profiles_keep_banned_on_delete on public.map_profiles;
create trigger map_profiles_keep_banned_on_delete before delete on public.map_profiles for each row execute function public.keep_banned_on_delete();

create or replace function public.leave_map()
returns void language plpgsql security definer set search_path = public as $$
begin
  delete from map_presence where owner_id = auth.uid();
  delete from map_pets where owner_id = auth.uid();
  if exists (select 1 from map_profiles where user_id = auth.uid() and banned) then return; end if;
  delete from map_profiles where user_id = auth.uid();
end $$;

-- 2. An owner's area can change three times a day: enough for a move or a trip, not enough to scan a
--    city for lone owners with a puppet account.
alter table public.map_presence add column if not exists moves_day date not null default current_date;
alter table public.map_presence add column if not exists moves int not null default 0;
create or replace function public.limit_presence_moves() returns trigger language plpgsql as $$
begin
  if new.cell_id is distinct from old.cell_id then
    if old.moves_day = current_date and old.moves >= 3 then
      raise exception 'you can change your area three times a day' using errcode = 'check_violation';
    end if;
    new.moves := case when old.moves_day = current_date then old.moves + 1 else 1 end;
    new.moves_day := current_date;
  else
    new.moves := old.moves; new.moves_day := old.moves_day;
  end if;
  return new;
end $$;
drop trigger if exists map_presence_limit_moves on public.map_presence;
create trigger map_presence_limit_moves before update on public.map_presence for each row execute function public.limit_presence_moves();

-- 3. RSVPs only through rsvp(): the direct write path skipped its capacity, timing and ban checks.
drop policy if exists own_rsvp on public.rsvps;
create policy own_rsvp_read on public.rsvps for select using (user_id = auth.uid());
revoke insert, update, delete on public.rsvps from authenticated;

-- 4. Pal codes: wrong guesses are limited (10 an hour), a code can be replaced, someone who unpalled
--    you can't be re-added with their old code, and a treat's sender name goes through the word filter.
create table if not exists public.pal_add_attempts (
  user_id uuid not null references auth.users on delete cascade,
  at      timestamptz not null default now()
);
create index if not exists pal_add_attempts_user on public.pal_add_attempts (user_id, at);
alter table public.pal_add_attempts enable row level security;

create table if not exists public.pal_removals (
  remover uuid not null references auth.users on delete cascade,
  removed uuid not null references auth.users on delete cascade,
  at      timestamptz not null default now(),
  primary key (remover, removed)
);
alter table public.pal_removals enable row level security;

create or replace function public.add_pal(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare other uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if (select count(*) from pal_add_attempts where user_id = auth.uid() and at > now() - interval '1 hour') >= 10 then
    raise exception 'too many wrong codes: please wait an hour and try again' using errcode = 'check_violation';
  end if;
  select user_id into other from pal_codes where code = upper(btrim(p_code));
  if other is null or other = auth.uid()
     or exists (select 1 from blocks where (blocker_id = auth.uid() and blocked_id = other) or (blocker_id = other and blocked_id = auth.uid()))
     or exists (select 1 from pal_removals where remover = other and removed = auth.uid()) then
    -- One answer for every miss, and the miss is counted even though we raise: count it in its own row first.
    insert into pal_add_attempts (user_id) values (auth.uid());
    if other = auth.uid() then raise exception 'that is your own code' using errcode = 'check_violation'; end if;
    raise exception 'no pal with that code' using errcode = 'check_violation';
  end if;
  if exists (select 1 from map_profiles where user_id in (auth.uid(), other) and banned) then
    raise exception 'not allowed' using errcode = 'insufficient_privilege';
  end if;
  if (select count(*) from pals where auth.uid() in (user_a, user_b)) >= 20 then
    raise exception 'you already have 20 pals' using errcode = 'check_violation';
  end if;
  if (select count(*) from pals where other in (user_a, user_b)) >= 20 then
    raise exception 'they already have 20 pals' using errcode = 'check_violation';
  end if;
  insert into pals (user_a, user_b) values (least(auth.uid(), other), greatest(auth.uid(), other)) on conflict do nothing;
  delete from pal_removals where remover = auth.uid() and removed = other; -- adding them back forgives
  return other;
end $$;

-- The miss has to be recorded even though add_pal raises: a separate function in its own transaction
-- isn't available to PostgREST, so the attempt row is written by a wrapper the app calls instead.
create or replace function public.add_pal_tracked(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare other uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if (select count(*) from pal_add_attempts where user_id = auth.uid() and at > now() - interval '1 hour') >= 10 then
    raise exception 'too many wrong codes: please wait an hour and try again' using errcode = 'check_violation';
  end if;
  select user_id into other from pal_codes where code = upper(btrim(p_code));
  if other is null
     or exists (select 1 from blocks where (blocker_id = auth.uid() and blocked_id = other) or (blocker_id = other and blocked_id = auth.uid()))
     or exists (select 1 from pal_removals where remover = other and removed = auth.uid()) then
    insert into pal_add_attempts (user_id) values (auth.uid());
    return null; -- returned, not raised, so the record of the miss stays
  end if;
  return add_pal(p_code);
end $$;

create or replace function public.remove_pal(p_user uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  delete from pals where user_a = least(auth.uid(), p_user) and user_b = greatest(auth.uid(), p_user);
  insert into pal_removals (remover, removed) values (auth.uid(), p_user) on conflict (remover, removed) do update set at = now();
end $$;

-- A new code: the old one stops working at once (for anyone it was handed to).
create or replace function public.new_pal_code() returns text
language plpgsql security definer set search_path = public as $$
declare alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; c text; bytes bytea;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  loop
    bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    c := '';
    for i in 0..5 loop c := c || substr(alphabet, (get_byte(bytes, i) % length(alphabet)) + 1, 1); end loop;
    exit when not exists (select 1 from pal_codes where code = c);
  end loop;
  insert into pal_codes (user_id, code) values (auth.uid(), c)
  on conflict (user_id) do update set code = excluded.code, created_at = now();
  return c;
end $$;

create or replace function public.send_treat(p_to_user uuid, p_to_pet text, p_from_pet text, p_kind text default 'treat') returns void
language plpgsql security definer set search_path = public as $$
declare n text;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if not are_pals(auth.uid(), p_to_user) then raise exception 'not pals' using errcode = 'insufficient_privilege'; end if;
  if (select count(*) from pal_treats where from_user = auth.uid() and created_at > now() - interval '1 day') >= 30 then
    raise exception 'that is plenty of treats for today' using errcode = 'check_violation';
  end if;
  n := left(btrim(coalesce(p_from_pet, 'A pal')), 24);
  if n = '' or pawpixel_name_blocked(n) then n := 'A pal'; end if;
  insert into pal_treats (from_user, to_user, to_pet, from_pet, kind)
  values (auth.uid(), p_to_user, left(p_to_pet, 40), n, case when p_kind in ('treat', 'pat', 'ball') then p_kind else 'treat' end);
end $$;

-- 5. Finder messages on a Pet ID card: a burst can't lock the card for the day (10 an hour, 60 a day).
create or replace function public.pet_card_message(p_id uuid, p_text text, p_contact text default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from pet_cards where id = p_id) then raise exception 'no such card' using errcode = 'check_violation'; end if;
  if (select count(*) from pet_card_messages where card_id = p_id and created_at > now() - interval '1 hour') >= 10
     or (select count(*) from pet_card_messages where card_id = p_id and created_at > now() - interval '1 day') >= 60 then
    raise exception 'too many messages right now, please try again later' using errcode = 'check_violation';
  end if;
  if btrim(coalesce(p_text, '')) = '' then raise exception 'empty message' using errcode = 'check_violation'; end if;
  insert into pet_card_messages (card_id, text, contact) values (p_id, left(btrim(p_text), 500), nullif(left(btrim(p_contact), 120), ''));
end $$;

revoke all on function public.new_pal_code, public.add_pal_tracked from public, anon;
grant execute on function public.new_pal_code, public.add_pal_tracked to authenticated;
