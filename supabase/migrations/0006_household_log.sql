-- Households, round 2: an append-only care log that phones sync incrementally, last-write-wins
-- edits, and the person who started the household can stop sharing for everyone.
--
-- - Care records are never deleted by a member: Undo marks one undone (undone_at), so the undo
--   reaches every phone with the same "what changed since my last sync" query as a new record.
--   Only the member who logged a record can undo it. Old records are still trimmed (400 per task).
-- - changed_ms is the server's clock (ms) when a record was added or undone: phones ask for
--   records changed since their last sync (with a short look-back, so a slow commit isn't missed).
-- - edited_at_ms on pets and tasks is the phone's time of the edit, for "the later edit wins" when
--   two phones changed the same task offline. A phone whose clock runs ahead can't win forever:
--   the server caps it at its own time.

create or replace function public.household_now_ms() returns bigint language sql volatile as $$
  select (extract(epoch from clock_timestamp()) * 1000)::bigint
$$;

-- ---------- The care log ----------
alter table public.household_completions
  add column undone_at  timestamptz,
  add column undone_by  uuid references auth.users on delete set null,
  add column changed_ms bigint not null default public.household_now_ms();
create index household_completions_changed on public.household_completions (household_id, changed_ms);

-- New records: the author is whoever is signed in, and a time in the future (a phone with a wrong
-- clock) is brought back to now.
create or replace function public.household_completion_author() returns trigger language plpgsql as $$
begin
  new.done_by := auth.uid();
  new.at_ms := least(new.at_ms, public.household_now_ms());
  new.undone_at := null;
  new.undone_by := null;
  new.changed_ms := public.household_now_ms();
  return new;
end $$;

-- Undo is the only change allowed, it happens once, and the server stamps who and when.
create or replace function public.household_completion_undo() returns trigger language plpgsql as $$
begin
  if new.done_by is distinct from old.done_by or new.undone_by is distinct from old.undone_by then
    return new; -- an account was deleted (FK set null): members can't change these columns
  end if;
  if old.undone_at is not null then
    return old; -- already undone: nothing changes (a second undo from another sync is harmless)
  end if;
  new := old;
  new.undone_at := now();
  new.undone_by := auth.uid();
  new.changed_ms := public.household_now_ms();
  return new;
end $$;
create trigger household_completions_undo before update on public.household_completions
  for each row execute function public.household_completion_undo();

-- Trimming the oldest record runs as the database owner: members themselves can't delete records.
create or replace function public.household_completions_limit() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if exists (select 1 from household_completions where household_id = new.household_id and id = new.id) then
    return new; -- a duplicate: ON CONFLICT ignores it
  end if;
  if (select count(*) from household_completions where household_id = new.household_id and task_id = new.task_id) >= 400 then
    delete from household_completions where household_id = new.household_id and id = (
      select id from household_completions where household_id = new.household_id and task_id = new.task_id order by at_ms, id limit 1);
  end if;
  return new;
end $$;

drop policy family_records_undo on public.household_completions;
create policy family_records_undo on public.household_completions for update
  using (public.is_household_member(household_id) and done_by = auth.uid())
  with check (public.is_household_member(household_id));
revoke delete on public.household_completions from authenticated;
grant update (undone_at) on public.household_completions to authenticated;

-- ---------- Last edit wins ----------
alter table public.household_pets add column edited_at_ms bigint not null default 0;
alter table public.household_tasks add column edited_at_ms bigint not null default 0;

create or replace function public.household_stamp() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' and new.updated_by is null and old.updated_by is not null then
    return new; -- the author's account was deleted (FK set null): keep it null
  end if;
  new.updated_at := now();
  new.updated_by := auth.uid();
  new.edited_at_ms := least(coalesce(new.edited_at_ms, 0), public.household_now_ms());
  return new;
end $$;

-- ---------- Stop sharing for everyone ----------
-- The person who started the household deletes it: pets, tasks, records, invites and memberships.
-- Every phone keeps its own copy of the pets, no longer shared.
create or replace function public.delete_household() returns void
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  select household_id into h from household_members where user_id = auth.uid();
  if h is null or not exists (select 1 from households where id = h and owner_id = auth.uid()) then
    raise exception 'only the person who started the household can stop sharing for everyone';
  end if;
  delete from households where id = h;
end $$;

revoke all on function public.delete_household, public.household_now_ms from public, anon;
grant execute on function public.delete_household to authenticated;
grant execute on function public.household_now_ms to authenticated;
