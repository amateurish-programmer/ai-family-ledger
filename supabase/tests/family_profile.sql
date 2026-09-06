-- Run with a database owner in an isolated Supabase test database after all migrations.
-- Synthetic identities only; no mail or external Auth requests. The entire fixture is rolled back.
begin;
insert into auth.users(id, email) values
  ('90000000-0000-4000-8000-000000000001', 'family-owner@example.invalid'),
  ('90000000-0000-4000-8000-000000000002', 'family-member@example.invalid'),
  ('90000000-0000-4000-8000-000000000003', 'other-owner@example.invalid');
insert into public.families(id, name, owner_id) values
  ('91000000-0000-4000-8000-000000000001', 'Synthetic family', '90000000-0000-4000-8000-000000000001'),
  ('91000000-0000-4000-8000-000000000002', 'Other family', '90000000-0000-4000-8000-000000000003');
insert into public.family_members(family_id, user_id) values
  ('91000000-0000-4000-8000-000000000001', '90000000-0000-4000-8000-000000000001'),
  ('91000000-0000-4000-8000-000000000001', '90000000-0000-4000-8000-000000000002'),
  ('91000000-0000-4000-8000-000000000002', '90000000-0000-4000-8000-000000000003');

set local role authenticated;
select set_config('request.jwt.claim.sub', '90000000-0000-4000-8000-000000000001', true);
do $$
declare v jsonb;
begin
  v := public.get_my_family();
  if v->>'icon' <> 'home' or v->>'is_owner' <> 'true' then raise exception 'owner/default metadata incorrect'; end if;
  v := public.update_family_profile(' Updated synthetic family ', 'heart');
  if v->>'name' <> 'Updated synthetic family' or v->>'icon' <> 'heart' then raise exception 'update failed'; end if;
  if public.update_family_profile('Updated synthetic family', 'heart') <> v then raise exception 'retry not idempotent'; end if;
  begin
    perform public.update_family_profile(' ', 'home');
    raise exception 'blank name accepted';
  exception when raise_exception then if sqlerrm <> 'invalid_family_name' then raise; end if; end;
  begin
    perform public.update_family_profile('valid', 'arbitrary-url');
    raise exception 'unknown icon accepted';
  exception when raise_exception then if sqlerrm <> 'invalid_family_icon' then raise; end if; end;
  begin
    perform public.update_family_profile(repeat('x', 81), 'home');
    raise exception 'long name accepted';
  exception when raise_exception then if sqlerrm <> 'invalid_family_name' then raise; end if; end;
end;
$$;

select set_config('request.jwt.claim.sub', '90000000-0000-4000-8000-000000000002', true);
do $$
declare v jsonb;
begin
  v := public.get_my_family();
  if v->>'icon' <> 'heart' or v->>'name' <> 'Updated synthetic family' or v->>'is_owner' <> 'false' then
    raise exception 'shared metadata/member role incorrect';
  end if;
  begin
    perform public.update_family_profile('forbidden', 'sun');
    raise exception 'member update accepted';
  exception when insufficient_privilege then if sqlerrm <> 'owner_required' then raise; end if; end;
  begin
    update public.families set name = 'forbidden';
    raise exception 'direct table update accepted';
  exception when insufficient_privilege then null; end;
end;
$$;

select set_config('request.jwt.claim.sub', '90000000-0000-4000-8000-000000000003', true);
do $$
begin
  perform public.update_family_profile('Other changed', 'tree');
  if exists(select 1 from public.families where id = '91000000-0000-4000-8000-000000000001') then
    raise exception 'foreign family visible';
  end if;
end;
$$;

reset role;
do $$
begin
  if (select name from public.families where id = '91000000-0000-4000-8000-000000000001') <> 'Updated synthetic family' then
    raise exception 'foreign or member update changed first family';
  end if;
end;
$$;
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
do $$
begin
  begin
    perform public.update_family_profile('forbidden', 'home');
    raise exception 'anonymous update accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform public.get_my_family();
    raise exception 'anonymous profile read accepted';
  exception when insufficient_privilege then null; end;
end;
$$;
rollback;
