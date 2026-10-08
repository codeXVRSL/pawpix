-- A pet in loving memory stays so on every household phone.
alter table public.household_pets add column if not exists remembered_day bigint;

-- Walks are proposed through host_walk() (its limits: three waiting, an hour to 90 days ahead, a real
-- grid cell). The old policy also let a client insert rows directly; now a host only reads and deletes theirs.
drop policy if exists host_gatherings on public.gatherings;
create policy host_gatherings_read on public.gatherings for select using (host_id = auth.uid());
create policy host_gatherings_delete on public.gatherings for delete using (host_id = auth.uid());
