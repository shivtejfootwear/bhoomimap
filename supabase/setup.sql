-- BhoomiMap database setup (run in Supabase > SQL Editor, step by step)

-- STEP 1: Public listing info (everyone sees only 'live' rows)
create table public.listings (
  id uuid primary key default gen_random_uuid(),
  title text not null default '',
  kind text not null default 'plot' check (kind in ('plot','farm')),
  place text not null,
  district text not null default 'Solapur',
  area_sqft numeric not null,
  facing text,
  road_width_ft numeric,
  verified boolean not null default false,
  boundary jsonb not null,
  status text not null default 'draft' check (status in ('draft','live','sold')),
  created_at timestamptz not null default now()
);
alter table public.listings enable row level security;
create policy "public sees live listings" on public.listings
  for select using (status = 'live');

-- STEP 2: Locked details (4 sides + photos) and private deal data
create table public.listing_details (
  listing_id uuid primary key references public.listings(id) on delete cascade,
  front_ft numeric, back_ft numeric, left_ft numeric, right_ft numeric,
  owner_photos text[] not null default '{}',
  team_photos text[] not null default '{}'
);
alter table public.listing_details enable row level security;

create table public.listing_private (
  listing_id uuid primary key references public.listings(id) on delete cascade,
  owner_name text, owner_phone text,
  asking_price numeric, minimum_price numeric
);
alter table public.listing_private enable row level security;
-- (no policy on listing_private = the app can never read it; only team tools later)

-- STEP 3: Buyer interest (leads) and unlocks
create table public.leads (
  id uuid primary key default gen_random_uuid(),
  listing_id uuid not null references public.listings(id) on delete cascade,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  buyer_name text not null,
  buyer_phone text not null,
  status text not null default 'new',
  created_at timestamptz not null default now(),
  unique (listing_id, user_id)
);
alter table public.leads enable row level security;
create policy "buyer adds own lead" on public.leads
  for insert to authenticated with check (user_id = auth.uid() and status = 'new');
create policy "buyer sees own leads" on public.leads
  for select to authenticated using (user_id = auth.uid());

create table public.unlocks (
  listing_id uuid not null references public.listings(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  unlocked_until timestamptz not null,
  primary key (listing_id, user_id)
);
alter table public.unlocks enable row level security;
create policy "buyer sees own unlocks" on public.unlocks
  for select to authenticated using (user_id = auth.uid());

create policy "details only after unlock" on public.listing_details
  for select to authenticated using (
    exists (
      select 1 from public.unlocks u
      where u.listing_id = listing_details.listing_id
        and u.user_id = auth.uid()
        and u.unlocked_until > now()
    )
  );

-- STEP 4: Saved plots and account delete requests
create table public.saved (
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  listing_id uuid not null references public.listings(id) on delete cascade,
  primary key (user_id, listing_id)
);
alter table public.saved enable row level security;
create policy "own saved" on public.saved
  for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create table public.account_deletion_requests (
  user_id uuid primary key default auth.uid(),
  requested_at timestamptz not null default now()
);
alter table public.account_deletion_requests enable row level security;
create policy "request own deletion" on public.account_deletion_requests
  for insert to authenticated with check (user_id = auth.uid());

create table public.support_requests (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  message text not null check (char_length(message) between 5 and 1000),
  status text not null default 'open',
  created_at timestamptz not null default now()
);
alter table public.support_requests enable row level security;
create policy "user sends own report" on public.support_requests
  for insert to authenticated with check (user_id = auth.uid() and status = 'open');

-- STEP 5 (test only): one sample plot in Solapur city
with p as (
  insert into public.listings (title, kind, place, area_sqft, facing, road_width_ft, verified, boundary, status)
  values ('Sample Plot (Test)', 'plot', 'Solapur City', 1250, 'East', 20, true,
          '[[17.67200,75.92000],[17.67200,75.92011],[17.67192,75.92011],[17.67192,75.92000]]', 'live')
  returning id
)
insert into public.listing_details (listing_id, front_ft, back_ft, left_ft, right_ft)
select id, 40, 42, 30, 31 from p;

-- ===== New features (Nov 2026): Top Agents, Alerts, Sell Land, test data =====

-- STEP 6: Partner agents (everyone sees active agents; only name + area shown)
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
