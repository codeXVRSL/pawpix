-- PawPixel Pals: a small circle, not a feed.
--
-- An owner shares a short code; a friend enters it and the two are pals (at most 20). Pals see
-- each other's pixel pets (name, species, ears, look code: never a photo, never a location), the
-- pets visit each other's rooms, and a pal can send a pet a treat, which shows in the owner's
-- room as a speech bubble. No followers, no likes, no public anything; either side can unpal.

create table public.pal_codes (
  user_id    uuid primary key references auth.users on delete cascade,
  code       text not null unique check (code ~ '^[A-Z2-9]{6}$'),
  created_at timestamptz not null default now()
);

create table public.pals (
  user_a     uuid not null references auth.users on delete cascade,
  user_b     uuid not null references auth.users on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_a, user_b),
  check (user_a < user_b)
);

-- The pixel pets a user shows their pals (the app keeps these in step with the pets on the phone).
create table public.pal_pets (
  owner_id   uuid not null references auth.users on delete cascade,
  local_id   text not null check (char_length(local_id) <= 40),
  name       text not null check (char_length(name) between 1 and 24),
  species    text not null check (species in ('DOG', 'CAT', 'OTHER')),
  ears       text check (ears is null or ears in ('POINTY', 'FLOPPY')),
  look       text not null default '' check (char_length(look) <= 400 and look ~ '^[A-Za-z0-9;,.]*$'),
  updated_at timestamptz not null default now(),
  primary key (owner_id, local_id)
);

create table public.pal_treats (
  id         uuid primary key default gen_random_uuid(),
  from_user  uuid not null references auth.users on delete cascade,
  to_user    uuid not null references auth.users on delete cascade,
  to_pet     text not null,            -- the pet's local_id on its owner's phone
  from_pet   text not null,            -- the sender's pet's name, as shown in the bubble
  kind       text not null check (kind in ('treat', 'pat', 'ball')),
  created_at timestamptz not null default now()
);
create index pal_treats_inbox on public.pal_treats (to_user, created_at);

alter table public.pal_codes enable row level security;
alter table public.pals enable row level security;
alter table public.pal_pets enable row level security;
alter table public.pal_treats enable row level security;

create or replace function public.are_pals(a uuid, b uuid) returns boolean language sql stable as $$
  select exists (select 1 from public.pals where (user_a = least(a, b) and user_b = greatest(a, b)));
$$;

-- Your code, made once. Six letters and digits without the look-alikes.
create or replace function public.my_pal_code() returns text
language plpgsql security definer set search_path = public as $$
declare alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'; c text; bytes bytea; existing text;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  select code into existing from pal_codes where user_id = auth.uid();
  if existing is not null then return existing; end if;
  loop
    bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    c := '';
    for i in 0..5 loop c := c || substr(alphabet, (get_byte(bytes, i) % length(alphabet)) + 1, 1); end loop;
    exit when not exists (select 1 from pal_codes where code = c);
  end loop;
  insert into pal_codes (user_id, code) values (auth.uid(), c);
  return c;
end $$;

-- Add a pal by their code. At most 20 pals; not yourself, not someone you've blocked or who blocked you, not a banned owner.
create or replace function public.add_pal(p_code text) returns uuid
language plpgsql security definer set search_path = public as $$
declare other uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  select user_id into other from pal_codes where code = upper(btrim(p_code));
  if other is null then raise exception 'no pal with that code' using errcode = 'check_violation'; end if;
  if other = auth.uid() then raise exception 'that is your own code' using errcode = 'check_violation'; end if;
  if exists (select 1 from map_profiles where user_id in (auth.uid(), other) and banned) then
    raise exception 'not allowed' using errcode = 'insufficient_privilege';
  end if;
  if exists (select 1 from blocks where (blocker_id = auth.uid() and blocked_id = other) or (blocker_id = other and blocked_id = auth.uid())) then
    raise exception 'no pal with that code' using errcode = 'check_violation';
  end if;
  if (select count(*) from pals where auth.uid() in (user_a, user_b)) >= 20 then
    raise exception 'you already have 20 pals' using errcode = 'check_violation';
  end if;
  if (select count(*) from pals where other in (user_a, user_b)) >= 20 then
    raise exception 'they already have 20 pals' using errcode = 'check_violation';
  end if;
  insert into pals (user_a, user_b) values (least(auth.uid(), other), greatest(auth.uid(), other)) on conflict do nothing;
  return other;
end $$;

create or replace function public.remove_pal(p_user uuid) returns void
language sql security definer set search_path = public as $$
  delete from pals where user_a = least(auth.uid(), p_user) and user_b = greatest(auth.uid(), p_user);
$$;

-- Replace the pets your pals see.
create or replace function public.set_pal_pets(p_pets jsonb) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  delete from pal_pets where owner_id = auth.uid();
  insert into pal_pets (owner_id, local_id, name, species, ears, look)
  select auth.uid(), left(p->>'local_id', 40), left(btrim(coalesce(p->>'name', 'Pet')), 24),
         case when p->>'species' in ('DOG', 'CAT') then p->>'species' else 'OTHER' end,
         case when p->>'ears' in ('POINTY', 'FLOPPY') then p->>'ears' else null end, coalesce(p->>'look', '')
  from jsonb_array_elements(coalesce(p_pets, '[]'::jsonb)) p
  where coalesce(p->>'local_id', '') <> '' and coalesce(p->>'look', '') ~ '^[A-Za-z0-9;,.]*$'
  limit 10;
  update pal_pets set name = case species when 'DOG' then 'A dog' when 'CAT' then 'A cat' else 'A pet' end
  where owner_id = auth.uid() and pawpixel_name_blocked(name);
end $$;

-- Your pals and their pets: pal id, their pet (local id, name, species, ears, look), when they last updated.
create or replace function public.pals_list()
returns table (pal_id uuid, pet_id text, name text, species text, ears text, look text, since timestamptz)
language sql stable security definer set search_path = public as $$
  select case when p.user_a = auth.uid() then p.user_b else p.user_a end, pp.local_id, pp.name, pp.species, pp.ears, pp.look, p.created_at
  from pals p
  left join pal_pets pp on pp.owner_id = case when p.user_a = auth.uid() then p.user_b else p.user_a end
  where auth.uid() in (p.user_a, p.user_b)
  order by p.created_at, pp.name;
$$;

-- A treat (or a pat, or a ball) for a pal's pet. At most 30 a day.
create or replace function public.send_treat(p_to_user uuid, p_to_pet text, p_from_pet text, p_kind text default 'treat') returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if not are_pals(auth.uid(), p_to_user) then raise exception 'not pals' using errcode = 'insufficient_privilege'; end if;
  if (select count(*) from pal_treats where from_user = auth.uid() and created_at > now() - interval '1 day') >= 30 then
    raise exception 'that is plenty of treats for today' using errcode = 'check_violation';
  end if;
  insert into pal_treats (from_user, to_user, to_pet, from_pet, kind)
  values (auth.uid(), p_to_user, left(p_to_pet, 40), left(btrim(coalesce(p_from_pet, 'A pal')), 24), case when p_kind in ('treat', 'pat', 'ball') then p_kind else 'treat' end);
end $$;

-- Treats your pets received in the last 7 days, newest first.
create or replace function public.treats_inbox()
returns table (id uuid, to_pet text, from_pet text, kind text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select t.id, t.to_pet, t.from_pet, t.kind, t.created_at from pal_treats t
  where t.to_user = auth.uid() and t.created_at > now() - interval '7 days' order by t.created_at desc limit 50;
$$;

revoke all on function public.my_pal_code, public.add_pal, public.remove_pal, public.set_pal_pets, public.pals_list,
  public.send_treat, public.treats_inbox, public.are_pals from public, anon;
grant execute on function public.my_pal_code, public.add_pal, public.remove_pal, public.set_pal_pets, public.pals_list,
  public.send_treat, public.treats_inbox to authenticated;
