-- Pet names other owners see pass a word filter (App Store 1.2, Google Play UGC policy).
-- The app already filters (core NameFilter.kt); this repeats it on the server so a modified app
-- can't get around it. A blocked name is replaced with "A dog" / "A cat" / "A pet", never rejected,
-- so joining the map still works. Keep the word list in sync with NameFilter.BLOCKED.

create or replace function public.pawpixel_normalize(t text)
returns text language sql immutable as $$
  select regexp_replace(
           regexp_replace(translate(lower(coalesce(t, '')), '01!|34@5$7', 'oiiieaasst'), '[^a-z]', '', 'g'),
           '(.)\1+', '\1', 'g')
$$;

create or replace function public.pawpixel_name_blocked(t text)
returns boolean language sql immutable as $$
  with words(w) as (values
    ('fuck'), ('shit'), ('bitch'), ('cunt'), ('dick'), ('pussy'), ('whore'), ('slut'), ('nigger'), ('nigga'),
    ('faggot'), ('retard'), ('rape'),
    ('putangina'), ('tangina'), ('puta'), ('gago'), ('tarantado'), ('ulol'), ('kupal'), ('pokpok'), ('bayag'),
    ('kantot'), ('tite'), ('puke'), ('pekpek'), ('burat')
  ),
  tokens(tok) as (
    select public.pawpixel_normalize(x) from unnest(regexp_split_to_array(coalesce(t, ''), '[\s._\-,/+&*~]+')) as x
  )
  select exists (
    select 1 from words
    where (length(w) >= 6 and position(public.pawpixel_normalize(w) in public.pawpixel_normalize(t)) > 0)
       or (length(w) < 6 and exists (select 1 from tokens where tok = public.pawpixel_normalize(w)))
  )
$$;

create or replace function public.map_pets_clean_name()
returns trigger language plpgsql as $$
begin
  if public.pawpixel_name_blocked(new.name) then
    new.name := case new.species when 'DOG' then 'A dog' when 'CAT' then 'A cat' else 'A pet' end;
  end if;
  return new;
end $$;

drop trigger if exists map_pets_clean_name on public.map_pets;
create trigger map_pets_clean_name before insert or update of name on public.map_pets
  for each row execute function public.map_pets_clean_name();
