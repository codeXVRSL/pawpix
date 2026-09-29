-- Family sharing: people who care for the same pets see each other's Done taps.
--
-- A household is private: only its members can read or change its pets, tasks and care records
-- (row level security below). Members join with an invite code from someone already in it.
-- Pets travel as name, species, ears and a pixel look code: never a photo. One household per account.

create table public.households (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 40),
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

-- Limits, so a household stays a family and a bug can't fill the server.
create or replace function public.household_limits() returns trigger language plpgsql as $$
begin
  if tg_table_name = 'household_pets' and (select count(*) from public.household_pets where household_id = new.household_id) >= 20 then
    raise exception 'too many pets in this family';
  elsif tg_table_name = 'household_tasks' and (select count(*) from public.household_tasks where household_id = new.household_id) >= 200 then
    raise exception 'too many care tasks in this family';
  elsif tg_table_name = 'household_completions'
        and (select count(*) from public.household_completions where household_id = new.household_id and task_id = new.task_id) >= 400 then
    -- Keep the newest: drop the oldest record of this task.
    delete from public.household_completions where ctid in (
      select ctid from public.household_completions where household_id = new.household_id and task_id = new.task_id order by at_ms limit 1);
  end if;
  return new;
end $$;
create trigger household_pets_limit before insert on public.household_pets for each row execute function public.household_limits();
create trigger household_tasks_limit before insert on public.household_tasks for each row execute function public.household_limits();
create trigger household_completions_limit before insert on public.household_completions for each row execute function public.household_limits();

-- Record who changed what (a client can't claim to be someone else).
create or replace function public.household_stamp() returns trigger language plpgsql as $$
begin
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

grant select, update on public.households to authenticated;
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
  insert into households (name) values (coalesce(nullif(trim(p_name), ''), 'Our family')) returning id into h;
  insert into household_members (household_id, user_id, display_name) values (h, auth.uid(), coalesce(nullif(trim(p_display_name), ''), 'Me'));
  return h;
end $$;

create or replace function public.create_invite(p_household uuid) returns text
language plpgsql security definer set search_path = public as $$
declare
  alphabet constant text := 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
  c text;
begin
  if not is_household_member(p_household) then raise exception 'not your family'; end if;
  delete from household_invites where expires_at < now();
  if (select count(*) from household_invites where household_id = p_household) >= 5 then
    delete from household_invites where code in (select code from household_invites where household_id = p_household order by expires_at limit 1);
  end if;
  loop
    c := '';
    for i in 1..8 loop
      c := c || substr(alphabet, 1 + floor(random() * length(alphabet))::int, 1);
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
  select household_id into h from household_invites where code = upper(p_code) and expires_at > now();
  if h is null then raise exception 'that invite code is wrong or has expired'; end if;
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

-- Deleting an account (delete_account in 0002) removes its membership by cascade; clean up a
-- household left with nobody in it.
create or replace function public.household_cleanup() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if not exists (select 1 from household_members where household_id = old.household_id) then
    delete from households where id = old.household_id;
  end if;
  return old;
end $$;
create trigger household_cleanup after delete on public.household_members for each row execute function public.household_cleanup();

revoke all on function public.create_household, public.create_invite, public.join_household, public.leave_household,
  public.is_household_member from public, anon;
grant execute on function public.create_household, public.create_invite, public.join_household, public.leave_household,
  public.is_household_member to authenticated;
