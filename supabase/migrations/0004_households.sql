-- Family sharing: people who care for the same pets see each other's Done taps.
--
-- A household is private: only its members can read or change its pets, tasks and care records
-- (row level security below). Members join with an invite code from someone already in it.
-- Pets travel as name, species, ears and a pixel look code: never a photo. One household per account.

create table public.households (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 40),
  -- The person who can remove members and cancel invites (passes on if they leave).
  owner_id    uuid references auth.users on delete set null,
  created_at  timestamptz not null default now()
);

create table public.household_members (
  household_id uuid not null references public.households on delete cascade,
  user_id      uuid not null unique references auth.users on delete cascade, -- one household per account
  display_name text not null check (char_length(display_name) between 1 and 24),
  joined_at    timestamptz not null default now(),
  primary key (household_id, user_id)
);

create table public.household_invites (
  code         text primary key check (code ~ '^[A-HJKMNP-Z2-9]{8}$'),
  household_id uuid not null references public.households on delete cascade,
  created_by   uuid references auth.users on delete set null,
  expires_at   timestamptz not null default now() + interval '7 days'
);

create table public.household_pets (
  household_id  uuid not null references public.households on delete cascade,
  id            text not null check (id ~ '^[a-z0-9]{1,40}$'),
  name          text not null check (char_length(name) between 1 and 24),
  species       text not null check (species in ('DOG', 'CAT', 'OTHER')),
  ears          text check (ears is null or ears in ('POINTY', 'FLOPPY')),
  look          text not null default '' check (char_length(look) <= 200 and look ~ '^[A-Za-z0-9;,.]*$'),
  eyes          jsonb not null default '[]' check (jsonb_typeof(eyes) = 'array' and jsonb_array_length(eyes) <= 2),
  birth_day     bigint,
  created_at_ms bigint not null default 0,
  updated_at    timestamptz not null default now(),
  updated_by    uuid default auth.uid() references auth.users on delete set null,
  primary key (household_id, id)
);

create table public.household_tasks (
  household_id  uuid not null,
  id            text not null check (id ~ '^[a-z0-9]{1,40}$'),
  pet_id        text not null,
  kind          text not null check (kind in ('FEED','WATER','WALK','PLAY','MEDS','GROOM','LITTER','VACCINE','DEWORM','FLEA_TICK','VET')),
  title         text not null check (char_length(title) between 1 and 30),
  slots         int[] not null check (cardinality(slots) between 1 and 4),
  every_days    int not null default 1 check (every_days between 1 and 365),
  anchor_day    bigint not null default 0,
  series        bigint[] not null default '{}' check (cardinality(series) <= 12),
  adaptive      boolean not null default true,
  created_at_ms bigint not null default 0,
  updated_at    timestamptz not null default now(),
  updated_by    uuid default auth.uid() references auth.users on delete set null,
  primary key (household_id, id),
  foreign key (household_id, pet_id) references public.household_pets (household_id, id) on delete cascade
);

create table public.household_completions (
  household_id uuid not null,
  id           text not null check (id ~ '^[a-z0-9]{1,40}$'),
  task_id      text not null,
  at_ms        bigint not null,
  local_minute int not null check (local_minute between 0 and 1439),
  local_day    bigint not null,
  done_by      uuid default auth.uid() references auth.users on delete set null,
  created_at   timestamptz not null default now(),
  primary key (household_id, id),
  foreign key (household_id, task_id) references public.household_tasks (household_id, id) on delete cascade
);
create index household_completions_task on public.household_completions (household_id, task_id, at_ms);

-- Limits, so a household stays a family and a bug can't fill the server. Updates of an existing
-- row (upserts) never count against a limit, and a record sent twice never trims history.
create or replace function public.household_pets_limit() returns trigger language plpgsql as $$
begin
  if not exists (select 1 from public.household_pets where household_id = new.household_id and id = new.id)
     and (select count(*) from public.household_pets where household_id = new.household_id) >= 20 then
    raise exception 'too many pets in this family';
  end if;
  return new;
end $$;

create or replace function public.household_tasks_limit() returns trigger language plpgsql as $$
begin
  if not exists (select 1 from public.household_tasks where household_id = new.household_id and id = new.id)
     and (select count(*) from public.household_tasks where household_id = new.household_id) >= 200 then
    raise exception 'too many care tasks in this family';
  end if;
  return new;
end $$;

create or replace function public.household_completions_limit() returns trigger language plpgsql as $$
begin
  if exists (select 1 from public.household_completions where household_id = new.household_id and id = new.id) then
    return new; -- a duplicate: ON CONFLICT ignores it
  end if;
  if (select count(*) from public.household_completions where household_id = new.household_id and task_id = new.task_id) >= 400 then
    -- Keep the newest: drop the oldest record of this task.
    delete from public.household_completions where household_id = new.household_id and id = (
      select id from public.household_completions where household_id = new.household_id and task_id = new.task_id order by at_ms, id limit 1);
  end if;
  return new;
end $$;
create trigger household_pets_limit before insert on public.household_pets for each row execute function public.household_pets_limit();
create trigger household_tasks_limit before insert on public.household_tasks for each row execute function public.household_tasks_limit();
create trigger household_completions_limit before insert on public.household_completions for each row execute function public.household_completions_limit();

-- Record who changed what (a client can't claim to be someone else). When an account is deleted,
-- its FK "set null" update must stay null (not be stamped with the deleted id).
create or replace function public.household_stamp() returns trigger language plpgsql as $$
begin
  if tg_op = 'UPDATE' and new.updated_by is null and old.updated_by is not null then
    return new;
  end if;
  new.updated_at := now();
  new.updated_by := auth.uid();
  return new;
end $$;
create trigger household_pets_stamp before insert or update on public.household_pets for each row execute function public.household_stamp();
create trigger household_tasks_stamp before insert or update on public.household_tasks for each row execute function public.household_stamp();

create or replace function public.household_completion_author() returns trigger language plpgsql as $$
begin
  new.done_by := auth.uid();
  return new;
end $$;
create trigger household_completions_author before insert on public.household_completions
  for each row execute function public.household_completion_author();

-- Failed invite codes, to stop anyone guessing them: 10 wrong tries an hour per account.
-- (join_household returns null for a wrong code; the app says "wrong or expired".)
create table public.household_join_attempts (
  user_id uuid not null references auth.users on delete cascade,
  at      timestamptz not null default now()
);
create index household_join_attempts_user on public.household_join_attempts (user_id, at);
alter table public.household_join_attempts enable row level security;
revoke all on public.household_join_attempts from anon, authenticated;

-- ---------- Who may see what ----------
create or replace function public.is_household_member(h uuid) returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from household_members where household_id = h and user_id = auth.uid())
$$;

alter table public.households            enable row level security;
alter table public.household_members     enable row level security;
alter table public.household_invites     enable row level security;
alter table public.household_pets        enable row level security;
alter table public.household_tasks       enable row level security;
alter table public.household_completions enable row level security;

create policy members_read_household on public.households for select using (public.is_household_member(id));
create policy members_rename_household on public.households for update using (public.is_household_member(id)) with check (public.is_household_member(id));
create policy members_see_members on public.household_members for select using (public.is_household_member(household_id));
create policy member_renames_self on public.household_members for update
  using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy family_pets on public.household_pets for all
  using (public.is_household_member(household_id)) with check (public.is_household_member(household_id));
create policy family_tasks on public.household_tasks for all
  using (public.is_household_member(household_id)) with check (public.is_household_member(household_id));
create policy family_records_read on public.household_completions for select using (public.is_household_member(household_id));
create policy family_records_add on public.household_completions for insert with check (public.is_household_member(household_id));
create policy family_records_undo on public.household_completions for delete using (public.is_household_member(household_id));
-- Invites are only used through join_household(); nobody reads the table directly.

grant select, update (name) on public.households to authenticated;
grant select, update (display_name) on public.household_members to authenticated;
grant select, insert, update, delete on public.household_pets, public.household_tasks to authenticated;
grant select, insert, delete on public.household_completions to authenticated;
revoke all on public.household_invites from anon, authenticated;

-- ---------- Creating, inviting, joining, leaving ----------
create or replace function public.create_household(p_name text, p_display_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  if auth.uid() is null then raise exception 'please sign in'; end if;
  if exists (select 1 from household_members where user_id = auth.uid()) then
    raise exception 'you are already in a family: leave it first';
  end if;
  insert into households (name, owner_id) values (coalesce(nullif(trim(p_name), ''), 'Our family'), auth.uid()) returning id into h;
  insert into household_members (household_id, user_id, display_name) values (h, auth.uid(), coalesce(nullif(trim(p_display_name), ''), 'Me'));
  return h;
end $$;

create or replace function public.create_invite(p_household uuid) returns text
language plpgsql security definer set search_path = public as $$
declare
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  c text;
  bytes bytea;
begin
  if not is_household_member(p_household) then raise exception 'not your family'; end if;
  delete from household_invites where expires_at < now();
  if (select count(*) from household_invites where household_id = p_household) >= 5 then
    delete from household_invites where code in (select code from household_invites where household_id = p_household order by expires_at limit 1);
  end if;
  loop
    -- gen_random_uuid() draws from the operating system's secure random source.
    bytes := decode(replace(gen_random_uuid()::text, '-', ''), 'hex');
    c := '';
    for i in 0..7 loop
      c := c || substr(alphabet, 1 + (get_byte(bytes, i) % length(alphabet)), 1);
    end loop;
    exit when not exists (select 1 from household_invites where code = c);
  end loop;
  insert into household_invites (code, household_id, created_by) values (c, p_household, auth.uid());
  return c;
end $$;

create or replace function public.join_household(p_code text, p_display_name text) returns uuid
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  if auth.uid() is null then raise exception 'please sign in'; end if;
  if (select count(*) from household_join_attempts where user_id = auth.uid() and at > now() - interval '1 hour') >= 10 then
    raise exception 'too many wrong codes: please wait an hour and try again';
  end if;
  select household_id into h from household_invites where code = upper(p_code) and expires_at > now();
  if h is null then
    -- Returned, not raised: an exception would roll back this record of the failed try.
    insert into household_join_attempts (user_id) values (auth.uid());
    return null;
  end if;
  perform 1 from households where id = h for update; -- one join at a time, so the limit of 8 holds
  if exists (select 1 from household_members where user_id = auth.uid() and household_id = h) then return h; end if;
  if exists (select 1 from household_members where user_id = auth.uid()) then
    raise exception 'you are already in a family: leave it first';
  end if;
  if (select count(*) from household_members where household_id = h) >= 8 then raise exception 'this family is full (8 people)'; end if;
  insert into household_members (household_id, user_id, display_name) values (h, auth.uid(), coalesce(nullif(trim(p_display_name), ''), 'Family member'));
  return h;
end $$;

-- Leaving: your records stay with the family (the author becomes unknown only if you delete your
-- account). The last person to leave takes the household with them.
create or replace function public.leave_household() returns void
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  delete from household_members where user_id = auth.uid() returning household_id into h;
  if h is not null and not exists (select 1 from household_members where household_id = h) then
    delete from households where id = h;
  end if;
end $$;

-- The owner removes someone (they keep their own copies of the pets on their phone) and can
-- cancel all open invites, e.g. after a code was shared by mistake.
create or replace function public.remove_member(p_user uuid) returns void
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  select household_id into h from household_members where user_id = auth.uid();
  if h is null or not exists (select 1 from households where id = h and owner_id = auth.uid()) then
    raise exception 'only the person who started the family can remove people';
  end if;
  if p_user = auth.uid() then raise exception 'use Leave to leave the family'; end if;
  delete from household_members where household_id = h and user_id = p_user;
end $$;

create or replace function public.revoke_invites() returns void
language plpgsql security definer set search_path = public as $$
declare h uuid;
begin
  select household_id into h from household_members where user_id = auth.uid();
  if h is null or not exists (select 1 from households where id = h and owner_id = auth.uid()) then
    raise exception 'only the person who started the family can cancel invites';
  end if;
  delete from household_invites where household_id = h;
end $$;

-- Deleting an account (delete_account in 0002) removes its membership by cascade; clean up a
-- household left with nobody in it.
create or replace function public.household_cleanup() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from household_members where household_id = old.household_id) then
    delete from households where id = old.household_id;
  elsif exists (select 1 from households where id = old.household_id and (owner_id = old.user_id or owner_id is null)) then
    -- The owner left: the longest-standing member takes over.
    update households set owner_id = (select user_id from household_members where household_id = old.household_id order by joined_at limit 1)
      where id = old.household_id;
  end if;
  return old;
end $$;
create trigger household_cleanup after delete on public.household_members for each row execute function public.household_cleanup();

revoke all on function public.create_household, public.create_invite, public.join_household, public.leave_household,
  public.remove_member, public.revoke_invites, public.is_household_member from public, anon;
grant execute on function public.create_household, public.create_invite, public.join_household, public.leave_household,
  public.remove_member, public.revoke_invites, public.is_household_member to authenticated;
