-- A moment: one photo a day for your pals, and nobody else.
--
-- An owner shares one small photo of a pet with a caption. Only their pals can fetch it, it
-- replaces the previous one, and it's gone after two days. No likes, no comments, no history:
-- a glimpse, the way you'd show a friend your phone.

create table public.pal_moments (
  owner_id   uuid primary key references auth.users on delete cascade,
  pet_name   text not null check (char_length(pet_name) between 1 and 24),
  caption    text not null default '' check (char_length(caption) <= 80),
  photo      text not null check (char_length(photo) between 1 and 90000),   -- base64 JPEG, about 64 KB at most
  updated_at timestamptz not null default now(),
  set_day    date not null default current_date,  -- how many times it was set today (a stuck app can't flood)
  sets_today int not null default 1
);

alter table public.pal_moments enable row level security;

-- Share today's moment (replacing yesterday's). At most 10 a day, so a stuck app can't flood.
create or replace function public.set_moment(p_pet_name text, p_caption text, p_photo text) returns void
language plpgsql security definer set search_path = public as $$
declare n text; c text; today_sets int;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if exists (select 1 from map_profiles where user_id = auth.uid() and banned) then
    raise exception 'not allowed' using errcode = 'insufficient_privilege';
  end if;
  if p_photo is null or char_length(p_photo) = 0 or char_length(p_photo) > 90000 or p_photo !~ '^[A-Za-z0-9+/=]+$' then
    raise exception 'the photo must be a small JPEG' using errcode = 'check_violation';
  end if;
  n := left(btrim(coalesce(p_pet_name, 'A pet')), 24);
  if n = '' or pawpixel_name_blocked(n) then n := 'A pet'; end if;
  c := left(btrim(coalesce(p_caption, '')), 80);
  if pawpixel_name_blocked(c) then c := ''; end if;
  select case when set_day = current_date then sets_today else 0 end into today_sets from pal_moments where owner_id = auth.uid();
  if coalesce(today_sets, 0) >= 10 then
    raise exception 'that is plenty of moments for today' using errcode = 'check_violation';
  end if;
  insert into pal_moments (owner_id, pet_name, caption, photo, updated_at, set_day, sets_today) values (auth.uid(), n, c, p_photo, now(), current_date, 1)
  on conflict (owner_id) do update set pet_name = excluded.pet_name, caption = excluded.caption, photo = excluded.photo, updated_at = now(),
    sets_today = case when pal_moments.set_day = current_date then pal_moments.sets_today + 1 else 1 end, set_day = current_date;
  -- Old moments are swept here, so the table never keeps a dead 64 KB row for long.
  delete from pal_moments where updated_at < now() - interval '3 days';
end $$;

create or replace function public.clear_moment() returns void
language sql security definer set search_path = public as $$
  delete from pal_moments where owner_id = auth.uid();
$$;

-- Your pals' moments from the last two days (and your own, so the app can show what's up), newest first.
create or replace function public.pals_moments()
returns table (pal_id uuid, pet_name text, caption text, photo text, updated_at timestamptz)
language sql stable security definer set search_path = public as $$
  select m.owner_id, m.pet_name, m.caption, m.photo, m.updated_at
  from pal_moments m
  where m.updated_at > now() - interval '2 days'
    and (m.owner_id = auth.uid() or are_pals(auth.uid(), m.owner_id))
    and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = m.owner_id) or (b.blocker_id = m.owner_id and b.blocked_id = auth.uid()))
  order by m.updated_at desc
  limit 25;
$$;

