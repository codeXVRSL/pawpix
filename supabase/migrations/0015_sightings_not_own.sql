-- A sighting can't be reported on your own alert (the count would be yours to inflate).
create or replace function public.report_sighting(p_lost_id uuid, p_lat double precision, p_lng double precision, p_note text default null, p_photo text default null)
returns uuid
language plpgsql security definer set search_path = public as $$
declare new_id uuid;
begin
  if auth.uid() is null then raise exception 'sign in first' using errcode = 'insufficient_privilege'; end if;
  if not exists (select 1 from lost_pets l where l.id = p_lost_id and l.found_at is null and l.owner_id <> auth.uid()) then
    raise exception 'this alert is closed' using errcode = 'check_violation';
  end if;
  if (select count(*) from lost_sightings where reporter_id = auth.uid() and created_at > now() - interval '1 day') >= 20 then
    raise exception 'too many sightings today' using errcode = 'check_violation';
  end if;
  if p_photo is not null and (char_length(p_photo) > 160000 or p_photo !~ '^[A-Za-z0-9+/=]*$') then
    raise exception 'photo too big' using errcode = 'check_violation';
  end if;
  insert into lost_sightings (lost_id, reporter_id, lat, lng, note, photo)
  values (p_lost_id, auth.uid(), p_lat, p_lng, nullif(left(btrim(p_note), 300), ''), p_photo)
  returning id into new_id;
  return new_id;
end $$;
