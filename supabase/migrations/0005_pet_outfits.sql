-- Family sharing: the outfit a shared pet wears (earned with days of care in the app).
alter table public.household_pets add column accessory text
  check (accessory is null or accessory in ('BANDANA', 'FLOWER', 'BOW_TIE', 'PARTY_HAT', 'SUNGLASSES', 'CROWN'));
