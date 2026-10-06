-- Who's coming to a walk, as pixel pets: the pets (name, look) of owners who said they're going.
-- Never the owners, never where they live; only pets already shown on the map, and only to
-- signed-in owners looking at an approved walk.
create or replace function public.gathering_pets(p_id uuid)
returns table (pet_id uuid, name text, species text, ears text, look text, mine boolean)
language sql stable security definer set search_path = public as $$
  select mp.id, mp.name, mp.species, mp.ears, mp.look, mp.owner_id = auth.uid()
  from rsvps r
  join gatherings g on g.id = r.gathering_id and g.approved
  join map_pets mp on mp.owner_id = r.user_id
  join map_profiles prof on prof.user_id = r.user_id and not prof.banned
  where r.gathering_id = p_id and auth.uid() is not null
    and not exists (select 1 from blocks b where (b.blocker_id = auth.uid() and b.blocked_id = r.user_id)
                                             or (b.blocker_id = r.user_id and b.blocked_id = auth.uid()))
  order by mp.owner_id = auth.uid() desc, mp.name
  limit 60;
$$;
revoke all on function public.gathering_pets from public, anon;
grant execute on function public.gathering_pets to authenticated;
