-- Sign in with Apple: the refresh token Apple gives for each account, kept so that deleting the
-- account can revoke it (App Store Review Guideline 5.1.1(v)). Only the apple-revoke Edge Function
-- (supabase/functions/apple-revoke, service role) reads or writes it: the app never sees it.

create table public.apple_tokens (
  user_id uuid primary key references auth.users(id) on delete cascade,
  refresh_token text not null,
  updated_at timestamptz not null default now()
);

-- Row level security with no policies denies every row to the anon and signed-in roles; the
-- grants are removed too, so PostgREST refuses the table outright. The service role bypasses both.
alter table public.apple_tokens enable row level security;
revoke all on public.apple_tokens from public, anon, authenticated;
