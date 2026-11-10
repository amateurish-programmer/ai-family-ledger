begin;

-- A role is a display label. Membership and writes always use the Auth account ID.
create table ledger_private.member_avatars (
  family_id uuid not null,
  user_id uuid not null,
  role_name text not null,
  avatar text not null,
  photo text,
  primary key (family_id, user_id),
  foreign key (family_id, user_id) references public.family_members(family_id, user_id) on delete cascade
);
alter table ledger_private.member_avatars enable row level security;
revoke all on ledger_private.member_avatars from public, anon, authenticated;
grant select on ledger_private.member_avatars to authenticated;
create policy family_avatar_read on ledger_private.member_avatars for select to authenticated
  using (ledger_private.is_member(family_id));

create function public.get_family_member_avatars(p_family uuid) returns jsonb
language plpgsql stable security invoker set search_path = '' as $$
begin
  if not ledger_private.is_member(p_family) then
    raise exception 'family_required' using errcode = '42501';
  end if;
  return (select coalesce(jsonb_agg(jsonb_build_object(
    'user_id', a.user_id, 'role', a.role_name, 'avatar', a.avatar, 'photo', a.photo
  ) order by a.user_id), '[]'::jsonb)
  from ledger_private.member_avatars a
  where a.family_id = p_family);
end;
$$;

create function ledger_private.set_my_member_avatar(p_family uuid, p_role text, p_avatar text, p_photo text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_uid uuid := auth.uid();
  v_role text := btrim(p_role);
  v_photo text := nullif(p_photo, '');
  v_bytes bytea;
begin
  if v_uid is null then raise exception 'authentication_required' using errcode = '42501'; end if;
  perform 1 from public.family_members m where m.family_id = p_family and m.user_id = v_uid for key share;
  if not found then raise exception 'family_required' using errcode = '42501'; end if;

  if v_role is null or length(v_role) not between 1 and 20 or p_role ~ '[[:cntrl:]]' then
    raise exception 'invalid_member_role' using errcode = '22023';
  end if;
  if p_avatar is null or p_avatar not in ('person', 'man', 'woman', 'child', 'elder', 'cat', 'photo') then
    raise exception 'invalid_member_avatar' using errcode = '22023';
  end if;
  if p_avatar = 'photo' then
    -- Bound the work before decoding. The canonical encoding check rejects whitespace, data URIs
    -- and invalid padding. Check the decoded size since 65536 and 65537 bytes encode to the same length.
    if v_photo is null or octet_length(v_photo) > 87384 then
      raise exception 'invalid_avatar_photo' using errcode = '22023';
    end if;
    begin
      v_bytes := decode(v_photo, 'base64');
    exception when invalid_parameter_value then
      raise exception 'invalid_avatar_photo' using errcode = '22023';
    end;
    if octet_length(v_bytes) not between 6 and 65536
       or substring(v_bytes from 1 for 3) <> decode('ffd8ff', 'hex')
       or substring(v_bytes from octet_length(v_bytes) - 1 for 2) <> decode('ffd9', 'hex')
       or replace(encode(v_bytes, 'base64'), chr(10), '') <> v_photo then
      raise exception 'invalid_avatar_photo' using errcode = '22023';
    end if;
  elsif v_photo is not null then
    raise exception 'invalid_avatar_photo' using errcode = '22023';
  end if;

  insert into ledger_private.member_avatars(family_id, user_id, role_name, avatar, photo)
    values(p_family, v_uid, v_role, p_avatar, v_photo)
    on conflict (family_id, user_id) do update
      set role_name = excluded.role_name, avatar = excluded.avatar, photo = excluded.photo;
  return jsonb_build_object('user_id', v_uid, 'role', v_role, 'avatar', p_avatar, 'photo', v_photo);
end;
$$;

create function public.set_my_member_avatar(p_family uuid, p_role text, p_avatar text, p_photo text) returns jsonb
language sql security invoker set search_path = '' as $$
  select ledger_private.set_my_member_avatar(p_family, p_role, p_avatar, p_photo);
$$;
revoke all on function public.get_family_member_avatars(uuid), public.set_my_member_avatar(uuid, text, text, text),
  ledger_private.set_my_member_avatar(uuid, text, text, text) from public, anon, authenticated;
grant execute on function public.get_family_member_avatars(uuid), public.set_my_member_avatar(uuid, text, text, text),
  ledger_private.set_my_member_avatar(uuid, text, text, text) to authenticated;
commit;
