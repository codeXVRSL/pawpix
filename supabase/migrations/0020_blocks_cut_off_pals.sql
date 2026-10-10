-- A block cuts off a pal too: someone you blocked (or who blocked you) on the map no longer sees
-- your pixel pets in Pals, can't send your pets treats or see your moments, and their earlier
-- treats leave your inbox. And the moment functions are for signed-in owners only.

-- "Pals" now means pals with no block either way, so every pal feature that asks are_pals
-- (send_treat, pals_moments, and whatever comes next) respects blocks without its own check.
create or replace function public.are_pals(a uuid, b uuid) returns boolean language sql stable as $$
  select exists (select 1 from public.pals where user_a = least(a, b) and user_b = greatest(a, b))
     and not exists (select 1 from public.blocks where (blocker_id = a and blocked_id = b) or (blocker_id = b and blocked_id = a));
$$;

-- These two read the pal and treat rows directly, so they filter blocks themselves (as in 0012 otherwise).
create or replace function public.pals_list()
returns table (pal_id uuid, pet_id text, name text, species text, ears text, look text, since timestamptz)
language sql stable security definer set search_path = public as $$
  select case when p.user_a = auth.uid() then p.user_b else p.user_a end, pp.local_id, pp.name, pp.species, pp.ears, pp.look, p.created_at
  from pals p
  left join pal_pets pp on pp.owner_id = case when p.user_a = auth.uid() then p.user_b else p.user_a end
  where auth.uid() in (p.user_a, p.user_b)
    and not exists (select 1 from blocks b where (b.blocker_id = p.user_a and b.blocked_id = p.user_b) or (b.blocker_id = p.user_b and b.blocked_id = p.user_a))
  order by p.created_at, pp.name;
$$;

create or replace function public.treats_inbox()
returns table (id uuid, to_pet text, from_pet text, kind text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select t.id, t.to_pet, t.from_pet, t.kind, t.created_at from pal_treats t
  where t.to_user = auth.uid() and t.created_at > now() - interval '7 days'
    and not exists (select 1 from blocks b where (b.blocker_id = t.to_user and b.blocked_id = t.from_user) or (b.blocker_id = t.from_user and b.blocked_id = t.to_user))
  order by t.created_at desc limit 50;
$$;

revoke all on function public.set_moment(text, text, text), public.clear_moment(), public.pals_moments() from public, anon;
grant execute on function public.set_moment(text, text, text), public.clear_moment(), public.pals_moments() to authenticated;
