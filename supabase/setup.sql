-- =====================================================================
-- BhoomiMap database setup v2  (Supabase > SQL Editor > paste > Run)
-- WARNING: run this ONLY at first setup. It deletes old BhoomiMap tables.
-- =====================================================================

create extension if not exists pgcrypto with schema extensions;
create extension if not exists supabase_vault;

drop table if exists public.audit_log, public.deals, public.site_tasks, public.number_views,
  public.tickets, public.support_requests, public.account_deletion_requests, public.saved,
  public.unlocks, public.leads, public.listing_private, public.listing_details,
  public.alerts, public.sell_requests, public.agents, public.listings,
  public.team_devices, public.team_members cascade;

-- ---------- Encryption key (kept in Supabase Vault) ----------
do $$
begin
  if not exists (select 1 from vault.secrets where name = 'bhoomimap_key') then
    perform vault.create_secret(encode(extensions.gen_random_bytes(32), 'hex'), 'bhoomimap_key');
  end if;
end $$;

create or replace function public.bm_key() returns text
language sql stable security definer set search_path = public as $$
  select decrypted_secret from vault.decrypted_secrets where name = 'bhoomimap_key'
$$;
revoke execute on function public.bm_key() from public, anon, authenticated;

-- ---------- Team ----------
create table public.team_members (
  user_id uuid primary key references auth.users(id) on delete cascade,
  name text not null,
  role text not null check (role in ('field','senior','ceo')),
  call_phone text,
  active boolean not null default true,
  code_accepted_at timestamptz,
  created_at timestamptz not null default now()
);
alter table public.team_members enable row level security;

create table public.team_devices (
  device_id text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  label text,
  approved boolean not null default false,
  created_at timestamptz not null default now()
);
alter table public.team_devices enable row level security;

create or replace function public.req_device() returns text language sql stable as $$
  select coalesce(nullif(current_setting('request.headers', true), '')::json->>'x-device-id', '')
$$;

-- role of the caller: only if active, 2-step login done and on an approved phone
create or replace function public.team_role() returns text
language sql stable security definer set search_path = public as $$
  select m.role from public.team_members m
  where m.user_id = auth.uid() and m.active
    and coalesce(auth.jwt()->>'aal', '') = 'aal2'
    and exists (select 1 from public.team_devices d
                where d.user_id = m.user_id and d.approved and d.device_id = public.req_device())
$$;
create or replace function public.is_team() returns boolean language sql stable as $$
  select public.team_role() is not null $$;
create or replace function public.is_senior() returns boolean language sql stable as $$
  select coalesce(public.team_role() in ('senior','ceo'), false) $$;
create or replace function public.is_ceo() returns boolean language sql stable as $$
  select coalesce(public.team_role() = 'ceo', false) $$;
-- for photo storage (no device header there)
create or replace function public.team_active() returns boolean
language sql stable security definer set search_path = public as $$
  select exists (select 1 from public.team_members where user_id = auth.uid() and active)
     and coalesce(auth.jwt()->>'aal', '') = 'aal2'
$$;

create policy "member sees own row" on public.team_members for select to authenticated using (user_id = auth.uid());
create policy "senior sees team" on public.team_members for select to authenticated using (public.is_senior());
create policy "ceo updates team" on public.team_members for update to authenticated using (public.is_ceo()) with check (public.is_ceo());
create policy "own devices" on public.team_devices for select to authenticated using (user_id = auth.uid() or public.is_ceo());
create policy "ceo approves devices" on public.team_devices for update to authenticated using (public.is_ceo()) with check (public.is_ceo());
create policy "ceo removes devices" on public.team_devices for delete to authenticated using (public.is_ceo());

-- ---------- Audit log ----------
create table public.audit_log (
  id bigint generated always as identity primary key,
  at timestamptz not null default now(),
  user_id uuid default auth.uid(),
  who text,
  action text not null,
  target text
);
alter table public.audit_log enable row level security;
create policy "ceo reads audit" on public.audit_log for select to authenticated using (public.is_ceo());

create or replace function public.audit(p_action text, p_target text) returns void
language sql security definer set search_path = public as $$
  insert into public.audit_log(user_id, who, action, target)
  values (auth.uid(), coalesce((select name from public.team_members where user_id = auth.uid()), 'user'), p_action, p_target)
$$;
revoke execute on function public.audit(text, text) from public, anon, authenticated;

-- ---------- Listings ----------
create table public.listings (
  id uuid primary key default gen_random_uuid(),
  title text not null default '' check (title !~ '[0-9]{10}'),
  kind text not null default 'plot' check (kind in ('plot','farm')),
  place text not null check (place !~ '[0-9]{10}'),
  district text not null default 'Solapur',
  area_sqft numeric not null check (area_sqft > 0),
  facing text,
  road_width_ft numeric,
  listed_by text not null default 'owner' check (listed_by in ('owner','agent')),
  verified boolean not null default false,
  boundary jsonb not null,
  status text not null default 'draft' check (status in ('draft','live','sold','removed')),
  expires_at timestamptz not null default now() + interval '90 days',
  created_by uuid default auth.uid(),
  created_at timestamptz not null default now()
);
alter table public.listings enable row level security;
create policy "public sees live listings" on public.listings for select using (status = 'live' and expires_at > now());
create policy "team sees listings" on public.listings for select to authenticated using (public.is_team());
create policy "senior adds listings" on public.listings for insert to authenticated with check (public.is_senior());
create policy "senior updates listings" on public.listings for update to authenticated using (public.is_senior()) with check (public.is_senior());

create table public.listing_details (
  listing_id uuid primary key references public.listings(id) on delete cascade,
  front_ft numeric, back_ft numeric, left_ft numeric, right_ft numeric,
  owner_photos text[] not null default '{}',
  team_photos text[] not null default '{}',
  checks jsonb not null default '{}'
);
alter table public.listing_details enable row level security;
create policy "team sees details" on public.listing_details for select to authenticated using (public.is_team());

-- private deal data: encrypted, no direct access at all (only through functions below)
create table public.listing_private (
  listing_id uuid primary key references public.listings(id) on delete cascade,
  owner_name text,
  owner_phone_enc bytea,
  asking_price_enc bytea,
  minimum_price_enc bytea
);
alter table public.listing_private enable row level security;

create or replace function public.set_private(p_listing uuid, p_owner_name text, p_owner_phone text, p_asking numeric, p_min numeric)
returns void language plpgsql security definer set search_path = public, extensions as $$
begin
  if not public.is_senior() then raise exception 'Not allowed'; end if;
  insert into public.listing_private(listing_id, owner_name, owner_phone_enc, asking_price_enc, minimum_price_enc)
  values (p_listing, p_owner_name,
          pgp_sym_encrypt(coalesce(p_owner_phone, ''), public.bm_key()),
          pgp_sym_encrypt(coalesce(p_asking, 0)::text, public.bm_key()),
          pgp_sym_encrypt(coalesce(p_min, 0)::text, public.bm_key()))
  on conflict (listing_id) do update set owner_name = excluded.owner_name,
    owner_phone_enc = excluded.owner_phone_enc, asking_price_enc = excluded.asking_price_enc,
    minimum_price_enc = excluded.minimum_price_enc;
  perform public.audit('Saved owner & price', p_listing::text);
end $$;

create or replace function public.get_private(p_listing uuid) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare r json;
begin
  if not public.is_senior() then raise exception 'Not allowed'; end if;
  select json_build_object(
    'owner_name', owner_name,
    'owner_phone', pgp_sym_decrypt(owner_phone_enc, public.bm_key()),
    'asking_price', pgp_sym_decrypt(asking_price_enc, public.bm_key())::numeric,
    'minimum_price', pgp_sym_decrypt(minimum_price_enc, public.bm_key())::numeric)
  into r from public.listing_private where listing_id = p_listing;
  perform public.audit('Viewed owner & price', p_listing::text);
  return r;
end $$;

-- ---------- Buyer interest (leads) ----------
create table public.leads (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  buyer_name text not null,
  buyer_phone text not null check (buyer_phone ~ '^[6-9][0-9]{9}$'),
  status text not null default 'new',
  assigned_to uuid references auth.users(id),
  call_attempts int not null default 0,
  stop_calls boolean not null default false,
  agent_id uuid,
  created_at timestamptz not null default now(),
  unique (listing_id, user_id)
);
alter table public.leads enable row level security;
-- the phone number column can never be read directly
revoke select, update on public.leads from anon, authenticated;
grant select (id, listing_id, user_id, buyer_name, status, assigned_to, call_attempts, stop_calls, agent_id, created_at)
  on public.leads to authenticated;
grant update (status) on public.leads to authenticated;
create policy "buyer adds own lead" on public.leads for insert to authenticated
  with check (user_id = auth.uid() and status = 'new' and assigned_to is null and call_attempts = 0 and stop_calls = false);
create policy "buyer sees own leads" on public.leads for select to authenticated using (user_id = auth.uid());
create policy "senior sees leads" on public.leads for select to authenticated using (public.is_senior());
create policy "senior updates leads" on public.leads for update to authenticated using (public.is_senior()) with check (public.is_senior());

create table public.number_views (
  id bigint generated always as identity primary key,
  lead_id uuid not null references public.leads(id) on delete cascade,
  viewer_name text not null,
  at timestamptz not null default now()
);
alter table public.number_views enable row level security;
create policy "buyer sees who saw number" on public.number_views for select to authenticated
  using (exists (select 1 from public.leads l where l.id = lead_id and l.user_id = auth.uid()));

create table public.unlocks (
  listing_id uuid not null references public.listings(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  unlocked_until timestamptz not null,
  primary key (listing_id, user_id)
);
alter table public.unlocks enable row level security;
create policy "buyer sees own unlocks" on public.unlocks for select to authenticated using (user_id = auth.uid() or public.is_senior());

create policy "details only after unlock" on public.listing_details for select to authenticated using (
  exists (select 1 from public.unlocks u where u.listing_id = listing_details.listing_id
          and u.user_id = auth.uid() and u.unlocked_until > now()));

create or replace function public.assign_to_me(p_lead uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not public.is_senior() then raise exception 'Not allowed'; end if;
  update public.leads set assigned_to = auth.uid()
   where id = p_lead and (assigned_to is null or public.is_ceo());
  if not found then raise exception 'Lead is already assigned to someone else'; end if;
  perform public.audit('Took lead', p_lead::text);
end $$;

create or replace function public.lead_phone(p_lead uuid) returns text
language plpgsql security definer set search_path = public as $$
declare l public.leads;
begin
  select * into l from public.leads where id = p_lead;
  if l.id is null then raise exception 'Lead not found'; end if;
  if not ((public.is_senior() and l.assigned_to = auth.uid()) or public.is_ceo()) then
    raise exception 'Only the assigned team member can see this number';
  end if;
  insert into public.number_views(lead_id, viewer_name)
  values (p_lead, coalesce((select name || ' (' || initcap(role) || ' Team)' from public.team_members where user_id = auth.uid()), 'BhoomiMap Team'));
  perform public.audit('Viewed buyer number', p_lead::text);
  return l.buyer_phone;
end $$;

create or replace function public.log_call(p_lead uuid) returns int
language plpgsql security definer set search_path = public as $$
declare l public.leads;
begin
  select * into l from public.leads where id = p_lead;
  if not ((public.is_senior() and l.assigned_to = auth.uid()) or public.is_ceo()) then raise exception 'Not allowed'; end if;
  if l.stop_calls then raise exception 'Buyer has turned on Stop Calls. Do not call.'; end if;
  if l.call_attempts >= 2 then raise exception 'Call limit reached (2 calls). Do not call again.'; end if;
  update public.leads set call_attempts = call_attempts + 1,
    status = case when status = 'new' then 'called' else status end where id = p_lead;
  perform public.audit('Called buyer', p_lead::text);
  return l.call_attempts + 1;
end $$;

create or replace function public.unlock_for_lead(p_lead uuid, p_days int) returns void
language plpgsql security definer set search_path = public as $$
declare l public.leads;
begin
  select * into l from public.leads where id = p_lead;
  if not ((public.is_senior() and l.assigned_to = auth.uid()) or public.is_ceo()) then raise exception 'Not allowed'; end if;
  insert into public.unlocks(listing_id, user_id, unlocked_until)
  values (l.listing_id, l.user_id, now() + make_interval(days => least(greatest(p_days, 1), 30)))
  on conflict (listing_id, user_id) do update set unlocked_until = excluded.unlocked_until;
  update public.leads set status = 'unlocked' where id = p_lead;
  perform public.audit('Unlocked details for buyer', p_lead::text);
end $$;

create or replace function public.set_stop_calls(p_lead uuid, p_stop boolean) returns void
language sql security definer set search_path = public as $$
  update public.leads set stop_calls = p_stop where id = p_lead and user_id = auth.uid()
$$;

create or replace function public.my_caller(p_lead uuid) returns json
language sql stable security definer set search_path = public as $$
  select json_build_object('name', m.name, 'role', m.role, 'call_phone', m.call_phone)
  from public.leads l join public.team_members m on m.user_id = l.assigned_to
  where l.id = p_lead and l.user_id = auth.uid()
$$;

-- ---------- Field team site tasks ----------
create table public.site_tasks (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings(id) on delete cascade,
  assigned_to uuid not null references auth.users(id),
  status text not null default 'open' check (status in ('open','submitted','approved','rejected')),
  note text,
  created_by uuid default auth.uid(),
  created_at timestamptz not null default now(),
  submitted_at timestamptz
);
alter table public.site_tasks enable row level security;
create policy "senior manages tasks" on public.site_tasks for all to authenticated using (public.is_senior()) with check (public.is_senior());
create policy "field sees own tasks" on public.site_tasks for select to authenticated using (assigned_to = auth.uid() and public.is_team());

create or replace function public.task_owner(p_task uuid) returns json
language plpgsql security definer set search_path = public, extensions as $$
declare t public.site_tasks; r json;
begin
  select * into t from public.site_tasks where id = p_task;
  if t.id is null or t.assigned_to <> auth.uid() or t.status <> 'open' or not public.is_team() then
    raise exception 'Not allowed';
  end if;
  select json_build_object('owner_name', owner_name, 'owner_phone', pgp_sym_decrypt(owner_phone_enc, public.bm_key()))
  into r from public.listing_private where listing_id = t.listing_id;
  perform public.audit('Field viewed owner contact', t.listing_id::text);
  return r;
end $$;

create or replace function public.submit_task(p_task uuid, p_front numeric, p_back numeric, p_left numeric, p_right numeric,
  p_road numeric, p_facing text, p_checks jsonb, p_owner_photos text[], p_team_photos text[]) returns void
language plpgsql security definer set search_path = public as $$
declare t public.site_tasks;
begin
  select * into t from public.site_tasks where id = p_task;
  if t.id is null or t.assigned_to <> auth.uid() or t.status <> 'open' or not public.is_team() then
    raise exception 'Not allowed';
  end if;
  insert into public.listing_details(listing_id, front_ft, back_ft, left_ft, right_ft, owner_photos, team_photos, checks)
  values (t.listing_id, p_front, p_back, p_left, p_right, coalesce(p_owner_photos, '{}'), coalesce(p_team_photos, '{}'), coalesce(p_checks, '{}'))
  on conflict (listing_id) do update set front_ft = excluded.front_ft, back_ft = excluded.back_ft,
    left_ft = excluded.left_ft, right_ft = excluded.right_ft,
    owner_photos = listing_details.owner_photos || excluded.owner_photos,
    team_photos = listing_details.team_photos || excluded.team_photos,
    checks = excluded.checks;
  update public.listings set road_width_ft = p_road, facing = p_facing where id = t.listing_id;
  update public.site_tasks set status = 'submitted', submitted_at = now() where id = p_task;
  perform public.audit('Submitted site check', t.listing_id::text);
end $$;

create or replace function public.review_task(p_task uuid, p_approve boolean, p_make_live boolean) returns void
language plpgsql security definer set search_path = public as $$
declare t public.site_tasks;
begin
  if not public.is_senior() then raise exception 'Not allowed'; end if;
  select * into t from public.site_tasks where id = p_task;
  update public.site_tasks set status = case when p_approve then 'approved' else 'rejected' end where id = p_task;
  if p_approve then
    update public.listings set verified = true,
      status = case when p_make_live then 'live' else status end,
      expires_at = case when p_make_live then now() + interval '90 days' else expires_at end
    where id = t.listing_id;
  end if;
  perform public.audit(case when p_approve then 'Approved site check' else 'Rejected site check' end, t.listing_id::text);
end $$;

-- ---------- Deals ----------
create table public.deals (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid references public.listings(id) on delete set null,
  lead_id uuid references public.leads(id) on delete set null,
  sale_price numeric not null,
  commission_total numeric not null,
  agent_share numeric not null default 0,
  company_share numeric not null,
  agent_paid boolean not null default false,
  closed_by uuid default auth.uid(),
  closed_at timestamptz not null default now()
);
alter table public.deals enable row level security;
create policy "senior sees deals" on public.deals for select to authenticated using (public.is_senior());
create policy "ceo updates deals" on public.deals for update to authenticated using (public.is_ceo()) with check (public.is_ceo());

create or replace function public.close_deal(p_lead uuid, p_price numeric) returns json
language plpgsql security definer set search_path = public as $$
declare l public.leads; total numeric; agent numeric;
begin
  select * into l from public.leads where id = p_lead;
  if not ((public.is_senior() and l.assigned_to = auth.uid()) or public.is_ceo()) then raise exception 'Not allowed'; end if;
  if p_price is null or p_price <= 0 then raise exception 'Enter the sale price'; end if;
  total := round(p_price * 0.04);
  agent := case when l.agent_id is not null then round(total * 0.70) else 0 end;
  insert into public.deals(listing_id, lead_id, sale_price, commission_total, agent_share, company_share)
  values (l.listing_id, p_lead, p_price, total, agent, total - agent);
  update public.leads set status = 'closed' where id = p_lead;
  update public.listings set status = 'sold' where id = l.listing_id;
  perform public.audit('Deal done', p_lead::text);
  return json_build_object('commission_total', total, 'agent_share', agent, 'company_share', total - agent);
end $$;

-- ---------- Saved, tickets, account delete ----------
create table public.saved (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  listing_id uuid not null references public.listings(id) on delete cascade,
  primary key (user_id, listing_id)
);
alter table public.saved enable row level security;
create policy "own saved" on public.saved for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create table public.tickets (
  id bigint generated always as identity (start with 1001) primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind text not null default 'problem' check (kind in ('problem','report_listing')),
  listing_id uuid references public.listings(id) on delete set null,
  message text not null check (char_length(message) between 5 and 1000),
  status text not null default 'open' check (status in ('open','solved')),
  reply text,
  created_at timestamptz not null default now(),
  replied_at timestamptz
);
alter table public.tickets enable row level security;
create policy "user opens ticket" on public.tickets for insert to authenticated
  with check (user_id = auth.uid() and status = 'open' and reply is null);
create policy "user sees own tickets" on public.tickets for select to authenticated using (user_id = auth.uid() or public.is_senior());
create policy "senior answers tickets" on public.tickets for update to authenticated using (public.is_senior()) with check (public.is_senior());

create table public.account_deletion_requests (
  user_id uuid primary key default auth.uid(),
  requested_at timestamptz not null default now()
);
alter table public.account_deletion_requests enable row level security;
create policy "request own deletion" on public.account_deletion_requests for insert to authenticated with check (user_id = auth.uid());
create policy "ceo sees deletion requests" on public.account_deletion_requests for select to authenticated using (public.is_ceo());

-- ---------- Team login gate ----------
create or replace function public.register_device(p_device text, p_label text) returns void
language sql security definer set search_path = public as $$
  insert into public.team_devices(device_id, user_id, label)
  select p_device, auth.uid(), p_label
  where exists (select 1 from public.team_members where user_id = auth.uid())
  on conflict (device_id) do nothing
$$;

create or replace function public.my_team_status(p_device text) returns json
language sql stable security definer set search_path = public as $$
  select json_build_object(
    'member', m.user_id is not null,
    'name', m.name, 'role', m.role, 'active', coalesce(m.active, false),
    'code_accepted', m.code_accepted_at is not null,
    'aal', coalesce(auth.jwt()->>'aal', ''),
    'device_approved', exists (select 1 from public.team_devices d
                               where d.user_id = auth.uid() and d.device_id = p_device and d.approved))
  from (select auth.uid() as uid) u left join public.team_members m on m.user_id = u.uid
$$;

create or replace function public.accept_code() returns void
language sql security definer set search_path = public as $$
  update public.team_members set code_accepted_at = now() where user_id = auth.uid()
$$;

create or replace function public.ceo_add_member(p_email text, p_name text, p_role text, p_call_phone text) returns void
language plpgsql security definer set search_path = public as $$
declare uid uuid;
begin
  if not public.is_ceo() then raise exception 'Not allowed'; end if;
  select id into uid from auth.users where lower(email) = lower(trim(p_email));
  if uid is null then raise exception 'No login found. First create this email in Supabase > Authentication > Add user.'; end if;
  insert into public.team_members(user_id, name, role, call_phone) values (uid, p_name, p_role, p_call_phone)
  on conflict (user_id) do update set name = excluded.name, role = excluded.role, call_phone = excluded.call_phone, active = true;
  perform public.audit('Added team member ' || p_name || ' as ' || p_role, uid::text);
end $$;

create or replace function public.ceo_stats() returns json
language plpgsql stable security definer set search_path = public as $$
begin
  if not public.is_ceo() then raise exception 'Not allowed'; end if;
  return json_build_object(
    'live_listings', (select count(*) from public.listings where status = 'live' and expires_at > now()),
    'draft_listings', (select count(*) from public.listings where status = 'draft'),
    'expiring_soon', (select count(*) from public.listings where status = 'live' and expires_at < now() + interval '7 days'),
    'new_leads', (select count(*) from public.leads where status = 'new'),
    'unlocked', (select count(*) from public.leads where status = 'unlocked'),
    'deals', (select count(*) from public.deals),
    'company_income', (select coalesce(sum(company_share), 0) from public.deals),
    'agent_due', (select coalesce(sum(agent_share), 0) from public.deals where not agent_paid),
    'open_tickets', (select count(*) from public.tickets where status = 'open'),
    'overdue_tickets', (select count(*) from public.tickets where status = 'open' and created_at < now() - interval '48 hours'),
    'pending_devices', (select count(*) from public.team_devices where not approved),
    'pending_tasks', (select count(*) from public.site_tasks where status = 'submitted'),
    'new_sell_requests', (select count(*) from public.sell_requests where status = 'new'));
end $$;

-- ---------- Photo storage (private) ----------
insert into storage.buckets (id, name, public) values ('plot-photos', 'plot-photos', false)
on conflict (id) do nothing;
drop policy if exists "bm team upload" on storage.objects;
create policy "bm team upload" on storage.objects for insert to authenticated
  with check (bucket_id = 'plot-photos' and public.team_active());
drop policy if exists "bm read photos" on storage.objects;
create policy "bm read photos" on storage.objects for select to authenticated using (
  bucket_id = 'plot-photos' and (public.team_active() or exists (
    select 1 from public.unlocks u where u.listing_id::text = (storage.foldername(name))[1]
      and u.user_id = auth.uid() and u.unlocked_until > now())));

-- ---------- Partner agents, alerts, sell requests ----------
-- Partner agents (everyone sees active agents; only name + area shown)
create table public.agents (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  area text not null default 'Solapur',
  deals int not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default now()
);
alter table public.agents enable row level security;
create policy "public sees active agents" on public.agents
  for select using (active);
-- (agent phone numbers stay outside this table, in team records only)

-- STEP 7: Land alerts (each user sees and manages only own alerts)
create table public.alerts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  place text not null default '' check (char_length(place) <= 60),
  kind text not null default 'all' check (kind in ('all','plot','farm')),
  min_sqft numeric not null default 0,
  max_sqft numeric not null default 0,
  created_at timestamptz not null default now()
);
alter table public.alerts enable row level security;
create policy "own alerts" on public.alerts
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

-- STEP 8: Sell land requests (owner sends; only team reads)
create table public.sell_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  owner_name text not null check (char_length(owner_name) between 2 and 80),
  phone text not null check (phone ~ '^[6-9][0-9]{9}$'),
  place text not null check (char_length(place) between 2 and 120),
  kind text not null check (kind in ('plot','farm')),
  area_text text not null check (char_length(area_text) <= 40),
  note text not null default '' check (char_length(note) <= 300),
  status text not null default 'new',
  created_at timestamptz not null default now()
);
alter table public.sell_requests enable row level security;
create policy "owner sends own request" on public.sell_requests
  for insert to authenticated with check (user_id = auth.uid() and status = 'new');
create policy "owner sees own requests" on public.sell_requests
  for select to authenticated using (user_id = auth.uid());


create policy "ceo manages agents" on public.agents for all to authenticated using (public.is_ceo()) with check (public.is_ceo());
create policy "senior sees sell requests" on public.sell_requests for select to authenticated using (public.is_senior());
create policy "senior updates sell requests" on public.sell_requests for update to authenticated using (public.is_senior()) with check (public.is_senior());

-- STEP 9 (test only): sample lands around Solapur + 2 test agents
with v(title, kind, place, area, facing, road, lat, lon, dl, dn, f, b, lf, rt) as (values
  ('Farm Land (Test)', 'farm', 'Mohol', 108900, 'North', 15, 17.81600, 75.64500, 0.00045, 0.00047, 330, 330, 330, 330),
  ('Farm Land (Test)', 'farm', 'Pandharpur', 239580, 'East', 20, 17.69000, 75.35000, 0.00067, 0.00070, 490, 488, 490, 489),
  ('Plot (Test)', 'plot', 'Barshi', 1500, 'West', 20, 18.23340, 75.69410, 0.000054, 0.000056, 30, 30, 50, 50),
  ('Farm Land (Test)', 'farm', 'Akkalkot', 52272, 'South', 12, 17.53000, 76.20000, 0.000315, 0.00033, 230, 228, 229, 230),
  ('Plot (Test)', 'plot', 'Solapur City', 2000, 'East', 30, 17.64500, 75.93500, 0.000061, 0.000064, 40, 40, 50, 50),
  ('Farm Land (Test)', 'farm', 'Mangalwedha', 21780, 'North', 10, 17.51500, 75.45500, 0.00020, 0.00021, 148, 147, 148, 148)
), ins as (
  insert into public.listings (title, kind, place, area_sqft, facing, road_width_ft, verified, boundary, status)
  select title, kind, place, area, facing, road, true,
    jsonb_build_array(
      jsonb_build_array(lat + dl, lon - dn), jsonb_build_array(lat + dl, lon + dn),
      jsonb_build_array(lat - dl, lon + dn), jsonb_build_array(lat - dl, lon - dn)),
    'live'
  from v
  returning id, place
)
insert into public.listing_details (listing_id, front_ft, back_ft, left_ft, right_ft)
select ins.id, v.f, v.b, v.lf, v.rt from ins join v on v.place = ins.place;

insert into public.agents (name, area, deals) values
  ('Test Agent One (Test)', 'Mohol', 3),
  ('Test Agent Two (Test)', 'Pandharpur', 1);

-- BEFORE LAUNCH: remove all test data
-- delete from public.listings where title like '%(Test)%';
-- delete from public.agents where name like '%(Test)%';
