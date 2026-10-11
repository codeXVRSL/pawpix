-- The pet's room (which furniture stands where) travels with the pet, so a household sees the same room.
-- A layout code: furniture names, tile positions and a flip flag (see RoomLayout.encode in core).
alter table public.household_pets add column if not exists room text
  check (room is null or (char_length(room) <= 400 and room ~ '^[A-Za-z0-9_;:,]*$'));
