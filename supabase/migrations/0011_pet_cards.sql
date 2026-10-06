-- PawPixel Pet ID card: "scan to reach my owner".
--
-- An owner makes a card for a pet: a page (web/card.html#<id>) with the pet's pixel twin, name, a
-- note for whoever finds them ("friendly, on medication, vet is...") and a form that sends the
-- owner a message through PawPixel. The app draws the page's address as a QR code to print for
-- the collar tag. Nothing on the page names the owner; the finder's message reaches the owner
-- in the app, and the finder leaves a contact line if they want a call back.

create table public.pet_cards (
  id         uuid primary key default gen_random_uuid(),
  owner_id   uuid not null references auth.users on delete cascade,
  local_id   text not null check (char_length(local_id) <= 40),
  name       text not null check (char_length(name) between 1 and 24),
  species    text not null check (species in ('DOG', 'CAT', 'OTHER')),
  ears       text check (ears is null or ears in ('POINTY', 'FLOPPY')),
  look       text not null default '' check (char_length(look) <= 400 and look ~ '^[A-Za-z0-9;,.]*$'),
  note       text check (note is null or char_length(note) <= 200),
  microchip  text check (microchip is null or char_length(microchip) <= 40),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (owner_id, local_id)
);

create table public.pet_card_messages (
  id         uuid primary key default gen_random_uuid(),
  card_id    uuid not null references public.pet_cards on delete cascade,
  text       text not null check (char_length(text) between 1 and 500),
  contact    text check (contact is null or char_length(contact) <= 120),
  created_at timestamptz not null default now()
);
create index pet_card_messages_by_card on public.pet_card_messages (card_id, created_at);

alter table public.pet_cards enable row level security;
alter table public.pet_card_messages enable row level security;

-- Make or update the card for one of your pets (keyed by the app's own pet id). At most 10 cards.
create or replace function public.upsert_pet_card(
  p_local_id text, p_name text, p_species text, p_ears text, p_look text, p_note text default null, p_microchip text default null
) returns uuid
language plpgsql security definer set search_path = public as $$
declare card_id uuid; nm text;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if exists (select 1 from map_profiles where user_id = auth.uid() and banned) then
    raise exception 'not allowed' using errcode = 'insufficient_privilege';
  end if;
  nm := left(btrim(coalesce(p_name, '')), 24);
  if nm = '' then nm := 'Pet'; end if;
  if pawpixel_name_blocked(nm) then
    nm := case p_species when 'DOG' then 'A dog' when 'CAT' then 'A cat' else 'A pet' end;
  end if;
  select id into card_id from pet_cards where owner_id = auth.uid() and local_id = p_local_id;
  if card_id is null then
    if (select count(*) from pet_cards where owner_id = auth.uid()) >= 10 then
      raise exception 'at most 10 cards' using errcode = 'check_violation';
    end if;
    insert into pet_cards (owner_id, local_id, name, species, ears, look, note, microchip)
    values (auth.uid(), left(p_local_id, 40), nm, coalesce(p_species, 'OTHER'), p_ears, coalesce(p_look, ''),
            nullif(left(btrim(p_note), 200), ''), nullif(left(btrim(p_microchip), 40), ''))
    returning id into card_id;
  else
    update pet_cards set name = nm, species = coalesce(p_species, 'OTHER'), ears = p_ears, look = coalesce(p_look, ''),
      note = nullif(left(btrim(p_note), 200), ''), microchip = nullif(left(btrim(p_microchip), 40), ''), updated_at = now()
    where id = card_id;
  end if;
  return card_id;
end $$;

create or replace function public.remove_pet_card(p_local_id text) returns void
language sql security definer set search_path = public as $$
  delete from pet_cards where owner_id = auth.uid() and local_id = p_local_id;
$$;

-- The card page: anyone with the link (the QR on the tag). No owner, no messages.
create or replace function public.pet_card_public(p_id uuid)
returns table (name text, species text, ears text, look text, note text, microchip text)
language sql stable security definer set search_path = public as $$
  select c.name, c.species, c.ears, c.look, c.note, c.microchip from pet_cards c where c.id = p_id;
$$;

-- "I found this pet": a message for the owner, from anyone, at most 20 a day per card.
create or replace function public.pet_card_message(p_id uuid, p_text text, p_contact text default null) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from pet_cards where id = p_id) then raise exception 'no such card' using errcode = 'check_violation'; end if;
  if (select count(*) from pet_card_messages where card_id = p_id and created_at > now() - interval '1 day') >= 20 then
    raise exception 'too many messages today' using errcode = 'check_violation';
  end if;
  if btrim(coalesce(p_text, '')) = '' then raise exception 'empty message' using errcode = 'check_violation'; end if;
  insert into pet_card_messages (card_id, text, contact) values (p_id, left(btrim(p_text), 500), nullif(left(btrim(p_contact), 120), ''));
end $$;

-- The owner's messages for one card, newest first.
create or replace function public.pet_card_messages_for(p_id uuid)
returns table (id uuid, text text, contact text, created_at timestamptz)
language sql stable security definer set search_path = public as $$
  select m.id, m.text, m.contact, m.created_at from pet_card_messages m join pet_cards c on c.id = m.card_id
  where m.card_id = p_id and c.owner_id = auth.uid() order by m.created_at desc limit 100;
$$;

-- The owner's cards (to find a card's id again on a new phone).
create or replace view public.my_pet_cards as
  select c.id, c.local_id, c.name, c.note, c.microchip, c.updated_at,
         (select count(*) from pet_card_messages m where m.card_id = c.id)::int as messages
  from pet_cards c where c.owner_id = auth.uid();

revoke all on function public.upsert_pet_card, public.remove_pet_card, public.pet_card_messages_for from public, anon;
grant execute on function public.upsert_pet_card, public.remove_pet_card, public.pet_card_messages_for to authenticated;
revoke all on function public.pet_card_public, public.pet_card_message from public;
grant execute on function public.pet_card_public, public.pet_card_message to anon, authenticated;
revoke all on public.my_pet_cards from public, anon;
grant select on public.my_pet_cards to authenticated;
