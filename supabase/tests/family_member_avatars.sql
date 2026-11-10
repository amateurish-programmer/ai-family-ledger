-- Run with the family profile suite in isolated PostgreSQL. Synthetic identities, no external Auth.
begin;
insert into auth.users(id, email) values
  ('94000000-0000-4000-8000-000000000001', 'avatar-owner@example.invalid'),
  ('94000000-0000-4000-8000-000000000002', 'avatar-member@example.invalid'),
  ('94000000-0000-4000-8000-000000000003', 'avatar-other@example.invalid'),
  ('94000000-0000-4000-8000-000000000004', 'avatar-unbound@example.invalid');
insert into public.families(id, name, owner_id) values
  ('95000000-0000-4000-8000-000000000001', 'First family', '94000000-0000-4000-8000-000000000001'),
  ('95000000-0000-4000-8000-000000000002', 'Second family', '94000000-0000-4000-8000-000000000003');
insert into public.family_members(family_id, user_id) values
  ('95000000-0000-4000-8000-000000000001', '94000000-0000-4000-8000-000000000001'),
  ('95000000-0000-4000-8000-000000000001', '94000000-0000-4000-8000-000000000002'),
  ('95000000-0000-4000-8000-000000000002', '94000000-0000-4000-8000-000000000003');

-- A real 1x1 black JPEG. Insert an optional JPEG comment to test exact decoded-byte limits.
create function pg_temp.fixture_photo(target_size integer default 160) returns text
language plpgsql as $$
declare
  jpg bytea := decode('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AP7//2Q==', 'base64');
  extra integer := target_size - octet_length(jpg);
begin
  if extra >= 4 then
    jpg := substring(jpg from 1 for 2) || decode('fffe' || lpad(to_hex(extra - 2), 4, '0'), 'hex')
      || decode(repeat('00', extra - 4), 'hex') || substring(jpg from 3);
  end if;
  if octet_length(jpg) <> target_size then raise exception 'invalid fixture length'; end if;
  return replace(encode(jpg, 'base64'), chr(10), '');
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000001', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000001'; own jsonb; bad text; preset text;
begin
  if public.get_family_member_avatars(family) <> '[]'::jsonb then raise exception 'legacy members must start empty'; end if;
  foreach preset in array array['person','man','woman','child','elder','cat'] loop
    own := public.set_my_member_avatar(family, ' 同名 ', preset, '');
    if own is distinct from jsonb_build_object('user_id', auth.uid(), 'role', '同名', 'avatar', preset, 'photo', null) then
      raise exception 'preset/normalized role contract failed';
    end if;
  end loop;
  own := public.set_my_member_avatar(family, '同名', 'photo', pg_temp.fixture_photo(65536));
  if own is distinct from jsonb_build_object('user_id', auth.uid(), 'role', '同名', 'avatar', 'photo', 'photo', pg_temp.fixture_photo(65536))
    or public.set_my_member_avatar(family, '同名', 'photo', pg_temp.fixture_photo(65536)) is distinct from own then
    raise exception 'JPEG round-trip or retry failed';
  end if;
  foreach bad in array array[null, '', '!', 'AAAA', 'data:image/jpeg;base64,' || pg_temp.fixture_photo(),
      '/9j/' || chr(10) || substring(pg_temp.fixture_photo() from 5), 'iVBORw0KGgo=',
      substring(pg_temp.fixture_photo() from 1 for 208), pg_temp.fixture_photo(65537), repeat('A', 90000)] loop
    begin
      perform public.set_my_member_avatar(family, '同名', 'photo', bad);
      raise exception 'invalid photo accepted';
    exception when invalid_parameter_value then
      if sqlerrm <> 'invalid_avatar_photo' then raise; end if;
    end;
  end loop;
  foreach bad in array array[null, ' ', repeat('名', 21), '名' || chr(10)] loop
    begin
      perform public.set_my_member_avatar(family, bad, 'person', null);
      raise exception 'invalid role accepted';
    exception when invalid_parameter_value then
      if sqlerrm <> 'invalid_member_role' then raise; end if;
    end;
  end loop;
  begin
    perform public.set_my_member_avatar(family, '同名', 'unknown', null);
    raise exception 'unknown preset accepted';
  exception when invalid_parameter_value then if sqlerrm <> 'invalid_member_avatar' then raise; end if; end;
  begin
    perform public.set_my_member_avatar(family, '同名', 'cat', pg_temp.fixture_photo());
    raise exception 'preset retained a supplied photo';
  exception when invalid_parameter_value then if sqlerrm <> 'invalid_avatar_photo' then raise; end if; end;
  if public.get_family_member_avatars(family) is distinct from jsonb_build_array(own) then
    raise exception 'rejected write changed previous avatar';
  end if;
end;
$$;

select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000002', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000001'; own jsonb; profiles jsonb; owner_id uuid := '94000000-0000-4000-8000-000000000001';
begin
  own := public.set_my_member_avatar(family, '同名', 'child', null);
  profiles := public.get_family_member_avatars(family);
  if jsonb_array_length(profiles) <> 2 or not profiles @> jsonb_build_array(own, jsonb_build_object(
      'user_id', owner_id, 'role', '同名', 'avatar', 'photo', 'photo', pg_temp.fixture_photo(65536))) then
    raise exception 'same-family photo sharing or duplicate-label account isolation failed';
  end if;
  if has_table_privilege(current_user, 'ledger_private.member_avatars', 'INSERT, UPDATE, DELETE, TRUNCATE') then
    raise exception 'authenticated has direct avatar writes';
  end if;
  begin
    update ledger_private.member_avatars set avatar = 'man' where user_id = owner_id;
    raise exception 'member changed another avatar directly';
  exception when insufficient_privilege then null; end;
end;
$$;

select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000003', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000002'; own jsonb;
begin
  own := public.set_my_member_avatar(family, '同名', 'person', null);
  if public.get_family_member_avatars(family) is distinct from jsonb_build_array(own)
     or (select count(*) from ledger_private.member_avatars) <> 1 then
    raise exception 'foreign family visible';
  end if;
end;
$$;

select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000001', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000001'; own jsonb; profiles jsonb;
begin
  own := public.set_my_member_avatar(family, '新角色', 'elder', null);
  profiles := public.get_family_member_avatars(family);
  if jsonb_array_length(profiles) <> 2 or own->'photo' is distinct from 'null'::jsonb
     or not profiles @> jsonb_build_array(own) or profiles @> '[{"avatar":"photo"}]'::jsonb then
    raise exception 'renaming the member or removing their photo failed';
  end if;
end;
$$;
select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000004', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000001';
begin
  begin
    perform public.get_family_member_avatars(family);
    raise exception 'unbound account read avatars';
  exception when insufficient_privilege then if sqlerrm <> 'family_required' then raise; end if; end;
  begin
    perform public.set_my_member_avatar(family, '角色', 'cat', null);
    raise exception 'unbound account wrote avatar';
  exception when insufficient_privilege then if sqlerrm <> 'family_required' then raise; end if; end;
end;
$$;

-- The same Auth account moves to another family while a phone still has pending data for the first.
reset role;
delete from public.family_members where user_id = '94000000-0000-4000-8000-000000000002';
insert into public.family_members(family_id, user_id)
  values ('95000000-0000-4000-8000-000000000002', '94000000-0000-4000-8000-000000000002');
set local role authenticated;
select set_config('request.jwt.claim.sub', '94000000-0000-4000-8000-000000000002', true);
do $$
declare
  stale_family uuid := '95000000-0000-4000-8000-000000000001';
  current_family uuid := '95000000-0000-4000-8000-000000000002';
  requested uuid;
  new_avatar jsonb;
begin
  foreach requested in array array[stale_family, null::uuid] loop
    begin
      perform public.get_family_member_avatars(requested);
      raise exception 'stale/null family request read current family avatars';
    exception when insufficient_privilege then if sqlerrm <> 'family_required' then raise; end if; end;
    begin
      perform public.set_my_member_avatar(requested, '旧资料', 'photo', pg_temp.fixture_photo());
      raise exception 'pending photo uploaded across family change';
    exception when insufficient_privilege then if sqlerrm <> 'family_required' then raise; end if; end;
  end loop;
  if jsonb_array_length(public.get_family_member_avatars(current_family)) <> 1
    or public.get_family_member_avatars(current_family) @> jsonb_build_array(jsonb_build_object('user_id', auth.uid())) then
    raise exception 'previous family avatar migrated to new family';
  end if;
  new_avatar := public.set_my_member_avatar(current_family, '新资料', 'cat', null);
  if not public.get_family_member_avatars(current_family) @> jsonb_build_array(new_avatar) then
    raise exception 'correctly rebound member could not update own avatar';
  end if;
end;
$$;
set local role anon;
select set_config('request.jwt.claim.sub', '', true);
do $$
declare family uuid := '95000000-0000-4000-8000-000000000001';
begin
  begin
    perform public.get_family_member_avatars(family);
    raise exception 'anonymous avatar read accepted';
  exception when insufficient_privilege then null; end;
  begin
    perform public.set_my_member_avatar(family, '角色', 'cat', null);
    raise exception 'anonymous avatar write accepted';
  exception when insufficient_privilege then null; end;
end;
$$;
rollback;
