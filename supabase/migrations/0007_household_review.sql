-- Households, review fixes.
--
-- The edit stamp (0006) left an edit unstamped when updated_by went from someone to null, meant for
-- the "set null" that follows an account's deletion. But a member could send updated_by: null with
-- an edit themselves: no author, and an edited_at_ms far in the future that wins every later edit.
-- Now only a deleted account's "set null" is left alone; any other edit is stamped by the server.
-- (Runs as the owner to look the account up; it only ever changes the row being written.)
create or replace function public.household_stamp() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if tg_op = 'UPDATE' and new.updated_by is null and old.updated_by is not null
     and not exists (select 1 from auth.users where id = old.updated_by) then
    return new; -- the author's account was deleted (FK set null): keep it null
  end if;
  new.updated_at := now();
  new.updated_by := auth.uid();
  new.edited_at_ms := least(coalesce(new.edited_at_ms, 0), public.household_now_ms());
  return new;
end $$;
