-- Apply to a dedicated Supabase project. No production data or credentials are included.
begin;
create schema if not exists ledger_private;
revoke all on schema ledger_private from public, anon, authenticated;
revoke create on schema public from public, anon, authenticated;
create extension if not exists pgcrypto with schema extensions;

create table public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(btrim(name)) between 1 and 80),
  owner_id uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);
create table public.family_members (
  family_id uuid not null references public.families(id),
  user_id uuid not null references auth.users(id),
  joined_at timestamptz not null default now(),
  primary key (family_id, user_id),
  unique (user_id) -- One authenticated account belongs to one family in this release.
);
create table ledger_private.invites (
  token_hash text primary key,
  family_id uuid not null references public.families(id),
  created_by uuid not null references auth.users(id),
  expires_at timestamptz not null,
  consumed_by uuid references auth.users(id),
  consumed_at timestamptz
);
create table public.ledger_entries (
  family_id uuid not null references public.families(id),
  id uuid not null,
  payload text not null check (octet_length(payload) <= 786432),
  revision bigint not null check (revision > 0),
  actor uuid not null references auth.users(id),
  updated_at timestamptz not null default now(),
  primary key (family_id, id)
);
create table ledger_private.ai_usage (
  bucket text primary key,
  minute_at timestamptz not null,
  minute_count integer not null,
  day_at timestamptz not null,
  day_count integer not null
);
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.ledger_entries enable row level security;
alter table ledger_private.invites enable row level security;
alter table ledger_private.ai_usage enable row level security;
revoke all on public.families, public.family_members, public.ledger_entries from public, anon, authenticated;
revoke all on ledger_private.invites, ledger_private.ai_usage from public, anon, authenticated;
grant select on public.families, public.family_members, public.ledger_entries to authenticated;

create function ledger_private.is_member(p_family uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists(select 1 from public.family_members where family_id = p_family and user_id = auth.uid());
$$;
revoke all on function ledger_private.is_member(uuid) from public, anon, authenticated;
grant usage on schema ledger_private to authenticated;
grant execute on function ledger_private.is_member(uuid) to authenticated;
create policy family_read on public.families for select to authenticated using (ledger_private.is_member(id));
create policy member_read on public.family_members for select to authenticated using (ledger_private.is_member(family_id));
create policy entry_read on public.ledger_entries for select to authenticated using (ledger_private.is_member(family_id));

create function public.get_my_family() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('id', f.id, 'name', f.name)
  from public.families f join public.family_members m on m.family_id = f.id where m.user_id = auth.uid();
$$;
create function public.create_family(p_name text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_id uuid; v_existing jsonb;
begin
  if v_uid is null then raise exception 'authentication_required' using errcode = '42501'; end if;
  if p_name is null or length(btrim(p_name)) not between 1 and 80 then raise exception 'invalid_family_name'; end if;
  perform pg_advisory_xact_lock(hashtextextended(v_uid::text, 0));
  v_existing := public.get_my_family();
  if v_existing is not null then return v_existing; end if;
  insert into public.families(name, owner_id) values (btrim(p_name), v_uid) returning id into v_id;
  insert into public.family_members(family_id, user_id) values(v_id, v_uid);
  return jsonb_build_object('id', v_id, 'name', btrim(p_name));
end;
$$;
create function public.create_invite() returns text
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_family uuid; v_token text;
begin
  select f.id into v_family from public.families f where f.owner_id = v_uid
    and exists(select 1 from public.family_members m where m.family_id = f.id and m.user_id = v_uid);
  if v_family is null then raise exception 'owner_required' using errcode = '42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(v_family::text, 1));
  delete from ledger_private.invites where family_id = v_family and (expires_at < now() or consumed_at is not null);
  if (select count(*) from ledger_private.invites where family_id = v_family) >= 10 then raise exception 'invite_limit'; end if;
  v_token := encode(extensions.gen_random_bytes(32), 'hex');
  insert into ledger_private.invites(token_hash, family_id, created_by, expires_at)
    values(encode(extensions.digest(v_token, 'sha256'), 'hex'), v_family, v_uid, now() + interval '24 hours');
  return v_token;
end;
$$;
create function public.join_family(p_code text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_inv ledger_private.invites%rowtype; v_existing jsonb;
begin
  if v_uid is null then raise exception 'authentication_required' using errcode = '42501'; end if;
  perform pg_advisory_xact_lock(hashtextextended(v_uid::text, 0));
  v_existing := public.get_my_family();
  if v_existing is not null then return v_existing; end if;
  if p_code is null or p_code !~ '^[0-9a-f]{64}$' then raise exception 'invalid_invite'; end if;
  select * into v_inv from ledger_private.invites where token_hash = encode(extensions.digest(p_code, 'sha256'), 'hex') for update;
  if not found or v_inv.expires_at <= now() or v_inv.consumed_at is not null then raise exception 'invalid_or_expired_invite'; end if;
  insert into public.family_members(family_id, user_id) values(v_inv.family_id, v_uid);
  update ledger_private.invites set consumed_by = v_uid, consumed_at = now() where token_hash = v_inv.token_hash;
  return public.get_my_family();
end;
$$;

create function ledger_private.validate_payload(p_id uuid, p_payload text) returns void
language plpgsql set search_path = '' as $$
declare v_root jsonb; v_row jsonb; v_key text; v_amount bigint; v_date date;
begin
  if p_payload is null or octet_length(p_payload) > 786432 then raise exception 'invalid_payload_size'; end if;
  v_root := p_payload::jsonb;
  if jsonb_typeof(v_root) is distinct from 'object' or v_root->>'format' is distinct from 'family-ledger'
    or v_root->'version' is distinct from '2'::jsonb or jsonb_typeof(v_root->'entries') is distinct from 'array' then raise exception 'invalid_backup_format'; end if;
  if jsonb_array_length(v_root->'entries') <> 1 then raise exception 'one_entry_required'; end if;
  v_row := v_root->'entries'->0;
  if jsonb_typeof(v_row) is distinct from 'object' then raise exception 'invalid_entry'; end if;
  foreach v_key in array array['id','type','occurredOn','currency','categoryL1','categoryL2','account','member','recordedBy','merchant','project','note'] loop
    if jsonb_typeof(v_row->v_key) is distinct from 'string' then raise exception 'text_field_required'; end if;
  end loop;
  if v_row->>'id' is distinct from p_id::text or v_row->>'type' not in ('EXPENSE','INCOME','BALANCE_ADJUSTMENT')
    or v_row->>'currency' <> 'CNY' then raise exception 'invalid_entry_identity'; end if;
  if jsonb_typeof(v_row->'amountMinor') is distinct from 'number' or (v_row->>'amountMinor') !~ '^-?[0-9]+$' then raise exception 'integer_amount_required'; end if;
  v_amount := (v_row->>'amountMinor')::bigint;
  if v_amount > 99999999999 or v_amount < (case when v_row->>'type' = 'BALANCE_ADJUSTMENT' then -99999999999 else 1 end) then raise exception 'amount_out_of_range'; end if;
  if (v_row->>'occurredOn') !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' then raise exception 'invalid_date'; end if;
  v_date := (v_row->>'occurredOn')::date;
  if to_char(v_date, 'YYYY-MM-DD') <> v_row->>'occurredOn' or extract(year from v_date) not between 1 and 9999 then raise exception 'invalid_date'; end if;
  foreach v_key in array array['categoryL1','account','member','recordedBy'] loop
    if length(btrim(v_row->>v_key)) = 0 then raise exception 'required_field_empty'; end if;
  end loop;
  foreach v_key in array array['categoryL1','categoryL2','account','member','recordedBy','merchant','project'] loop
    if length(v_row->>v_key) > 100 then raise exception 'field_too_long'; end if;
  end loop;
  if length(v_row->>'note') > 2000 then raise exception 'note_too_long'; end if;
  foreach v_key in array array['updatedAt','deletedAt'] loop
    if v_key = 'deletedAt' and v_row->v_key = 'null'::jsonb then continue; end if;
    if jsonb_typeof(v_row->v_key) is distinct from 'number' or (v_row->>v_key) !~ '^[0-9]+$'
      or (v_row->>v_key)::numeric > 9223372036854775807 then raise exception 'invalid_timestamp'; end if;
  end loop;
  if not (v_row ? 'origin') or (v_row->'origin' <> 'null'::jsonb and jsonb_typeof(v_row->'origin') is distinct from 'object') then raise exception 'invalid_origin'; end if;
  if v_row->'origin' <> 'null'::jsonb then
    if jsonb_typeof(v_row->'origin'->'fileHash') is distinct from 'string' or (v_row->'origin'->>'fileHash') !~ '^[0-9a-f]{64}$'
      or jsonb_typeof(v_row->'origin'->'rawFields') is distinct from 'object' then raise exception 'invalid_origin'; end if;
    foreach v_key in array array['fileName','sheet','originalDate','account2','projectCategory'] loop
      if jsonb_typeof(v_row->'origin'->v_key) is distinct from 'string' or length(v_row->'origin'->>v_key) > (case when v_key='fileName' then 255 else 100 end) then raise exception 'invalid_origin_text'; end if;
    end loop;
    foreach v_key in array array['rowNumber','importedAt'] loop
      if jsonb_typeof(v_row->'origin'->v_key) is distinct from 'number' or (v_row->'origin'->>v_key) !~ '^[0-9]+$'
        or (v_row->'origin'->>v_key)::numeric > 9223372036854775807 then raise exception 'invalid_origin_number'; end if;
    end loop;
    if (v_row->'origin'->>'rowNumber')::bigint not between 1 and 10001
      or (select count(*) from jsonb_each(v_row->'origin'->'rawFields')) > 64 then raise exception 'invalid_origin_fields'; end if;
    if exists(select 1 from jsonb_each(v_row->'origin'->'rawFields') x where length(x.key)>100 or jsonb_typeof(x.value)<>'string' or length(x.value #>> '{}')>10000) then raise exception 'invalid_origin_raw_field'; end if;
  end if;
end;
$$;
revoke all on function ledger_private.validate_payload(uuid,text) from public, anon, authenticated;

create function public.put_ledger_entry(p_family uuid, p_id uuid, p_payload text, p_expected_revision bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_row public.ledger_entries%rowtype;
begin
  if not ledger_private.is_member(p_family) then raise exception 'membership_required' using errcode = '42501'; end if;
  if p_expected_revision is null or p_expected_revision < 0 then raise exception 'invalid_revision'; end if;
  perform ledger_private.validate_payload(p_id, p_payload);
  if p_expected_revision = 0 then
    insert into public.ledger_entries(family_id,id,payload,revision,actor)
      values(p_family,p_id,p_payload,1,auth.uid()) on conflict (family_id,id) do nothing returning * into v_row;
  else
    update public.ledger_entries set payload=p_payload, revision=revision+1, actor=auth.uid(), updated_at=now()
      where family_id=p_family and id=p_id and revision=p_expected_revision returning * into v_row;
  end if;
  if found then return jsonb_build_object('ok',true,'revision',v_row.revision); end if;
  select * into v_row from public.ledger_entries where family_id=p_family and id=p_id;
  return jsonb_build_object('ok',false,'revision',v_row.revision,'payload',v_row.payload);
end;
$$;

-- Only the authenticated Edge Function server may consume quota on behalf of a verified user.
create function public.consume_ai_quota(p_user uuid) returns boolean
language plpgsql security definer set search_path = '' as $$
declare v_bucket text; v_minute integer; v_day integer; v_now timestamptz := now(); v_global boolean;
begin
  if not exists(select 1 from public.family_members where user_id=p_user) then return false; end if;
  -- Serialize in one consistent order; rejected requests also consume the global attempt quota.
  foreach v_bucket in array array['global',p_user::text] loop
    v_global := v_bucket = 'global';
    insert into ledger_private.ai_usage(bucket,minute_at,minute_count,day_at,day_count)
      values(v_bucket,date_trunc('minute',v_now),1,date_trunc('day',v_now),1)
      on conflict(bucket) do update set
        minute_count=case when ai_usage.minute_at = date_trunc('minute',v_now) then ai_usage.minute_count+1 else 1 end,
        minute_at=date_trunc('minute',v_now),
        day_count=case when ai_usage.day_at = date_trunc('day',v_now) then ai_usage.day_count+1 else 1 end,
        day_at=date_trunc('day',v_now)
      returning minute_count,day_count into v_minute,v_day;
    if v_minute > (case when v_global then 30 else 5 end) or v_day > (case when v_global then 300 else 30 end) then return false; end if;
  end loop;
  return true;
end;
$$;
revoke all on function public.get_my_family(), public.create_family(text), public.create_invite(), public.join_family(text), public.put_ledger_entry(uuid,uuid,text,bigint), public.consume_ai_quota(uuid) from public, anon, authenticated;
grant execute on function public.get_my_family(), public.create_family(text), public.create_invite(), public.join_family(text), public.put_ledger_entry(uuid,uuid,text,bigint) to authenticated;
grant execute on function public.consume_ai_quota(uuid) to service_role;
commit;
