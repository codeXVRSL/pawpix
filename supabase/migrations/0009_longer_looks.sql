-- Pixel looks got finer (a 10 x 10 marking map and up to four fur tones, plus free eye and nose
-- colours in the Studio), so a look code can be longer than the 200 characters allowed so far.
alter table public.map_pets drop constraint if exists map_pets_look_check;
alter table public.map_pets add constraint map_pets_look_check check (char_length(look) <= 400 and look ~ '^[A-Za-z0-9;,.]*$');
alter table public.household_pets drop constraint if exists household_pets_look_check;
alter table public.household_pets add constraint household_pets_look_check check (char_length(look) <= 400 and look ~ '^[A-Za-z0-9;,.]*$');
