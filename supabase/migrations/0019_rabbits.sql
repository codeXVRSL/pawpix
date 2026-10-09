-- Rabbits: a third species. The species checks accept 'RABBIT', and the pets pals see keep it
-- (older apps read an unknown species as 'OTHER', so they still draw something).

alter table public.map_pets drop constraint if exists map_pets_species_check;
alter table public.map_pets add constraint map_pets_species_check check (species in ('DOG', 'CAT', 'RABBIT', 'OTHER'));
alter table public.household_pets drop constraint if exists household_pets_species_check;
alter table public.household_pets add constraint household_pets_species_check check (species in ('DOG', 'CAT', 'RABBIT', 'OTHER'));
alter table public.lost_pets drop constraint if exists lost_pets_species_check;
alter table public.lost_pets add constraint lost_pets_species_check check (species in ('DOG', 'CAT', 'RABBIT', 'OTHER'));
alter table public.pet_cards drop constraint if exists pet_cards_species_check;
alter table public.pet_cards add constraint pet_cards_species_check check (species in ('DOG', 'CAT', 'RABBIT', 'OTHER'));
alter table public.pal_pets drop constraint if exists pal_pets_species_check;
alter table public.pal_pets add constraint pal_pets_species_check check (species in ('DOG', 'CAT', 'RABBIT', 'OTHER'));

-- As in 0012, with 'RABBIT' kept.
create or replace function public.set_pal_pets(p_pets jsonb) returns void
language plpgsql security definer set search_path = public as $$
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  delete from pal_pets where owner_id = auth.uid();
  insert into pal_pets (owner_id, local_id, name, species, ears, look)
  select auth.uid(), left(p->>'local_id', 40), left(btrim(coalesce(p->>'name', 'Pet')), 24),
         case when p->>'species' in ('DOG', 'CAT', 'RABBIT') then p->>'species' else 'OTHER' end,
         case when p->>'ears' in ('POINTY', 'FLOPPY') then p->>'ears' else null end, coalesce(p->>'look', '')
  from jsonb_array_elements(coalesce(p_pets, '[]'::jsonb)) p
  where coalesce(p->>'local_id', '') <> '' and coalesce(p->>'look', '') ~ '^[A-Za-z0-9;,.]*$'
  limit 10;
  update pal_pets set name = case species when 'DOG' then 'A dog' when 'CAT' then 'A cat' else 'A pet' end
  where owner_id = auth.uid() and pawpixel_name_blocked(name);
end $$;
