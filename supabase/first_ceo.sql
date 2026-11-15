-- =====================================================================
-- Make yourself CEO (run AFTER setup.sql). Change the email first!
-- Step A: Supabase > Authentication > Users > Add user > your CEO email + password
-- Step B: run Part 1 below (change the email).
-- Step C: open the BhoomiMap Team app, login, finish the 6-digit code setup.
--         It will say "Waiting for CEO approval".
-- Step D: run Part 2 below (same email). Then tap Refresh in the app.
-- =====================================================================

-- Part 1
insert into public.team_members (user_id, name, role)
select id, 'CEO', 'ceo' from auth.users where email = 'CHANGE-ME@gmail.com'
on conflict (user_id) do update set role = 'ceo', active = true;

-- Part 2
update public.team_devices set approved = true
where user_id = (select id from auth.users where email = 'CHANGE-ME@gmail.com');
