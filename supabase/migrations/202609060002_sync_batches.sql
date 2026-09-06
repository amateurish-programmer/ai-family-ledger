-- V0.8: compact manifests and bounded CAS batches. Existing clients keep their RPC.
begin;

create function public.get_ledger_manifest(p_family uuid, p_after uuid default null)
returns table(id uuid, revision bigint, payload_bytes integer)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not ledger_private.is_member(p_family) then
    raise exception 'membership_required' using errcode = '42501';
  end if;
  return query select e.id, e.revision, octet_length(e.payload)
    from public.ledger_entries e
    where e.family_id = p_family and (p_after is null or e.id > p_after)
    order by e.id limit 500;
end;
$$;

create function public.put_ledger_entries(p_family uuid, p_entries jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_entry jsonb; v_result jsonb; v_results jsonb := '[]'::jsonb;
begin
  if not ledger_private.is_member(p_family) then
    raise exception 'membership_required' using errcode = '42501';
  end if;
  if jsonb_typeof(p_entries) is distinct from 'array' then raise exception 'invalid_batch'; end if;
  if jsonb_array_length(p_entries) not between 1 and 50 or octet_length(p_entries::text) > 6291456 then
    raise exception 'invalid_batch_size';
  end if;
  -- Reject duplicate identities before performing writes; deterministic order avoids inverse locks.
  if (select count(distinct (x->>'id')::uuid) from jsonb_array_elements(p_entries) x) <> jsonb_array_length(p_entries) then
    raise exception 'duplicate_or_missing_id';
  end if;
  for v_entry in select x from jsonb_array_elements(p_entries) x order by (x->>'id')::uuid loop
    if jsonb_typeof(v_entry->'id') is distinct from 'string'
      or jsonb_typeof(v_entry->'payload') is distinct from 'string'
      or jsonb_typeof(v_entry->'expected_revision') is distinct from 'number'
      or (v_entry->>'expected_revision') !~ '^[0-9]+$' then raise exception 'invalid_batch_entry'; end if;
    -- Reuse membership, payload validation, auth.uid actor and CAS from the original RPC.
    -- A normal revision conflict does not abort other entries; invalid input rolls back the batch.
    v_result := public.put_ledger_entry(p_family, (v_entry->>'id')::uuid,
      v_entry->>'payload', (v_entry->>'expected_revision')::bigint);
    v_results := v_results || jsonb_build_array(jsonb_build_object(
      'id', v_entry->>'id', 'ok', v_result->'ok', 'revision', v_result->'revision'));
  end loop;
  return v_results;
end;
$$;

revoke all on function public.get_ledger_manifest(uuid,uuid), public.put_ledger_entries(uuid,jsonb) from public, anon, authenticated;
grant execute on function public.get_ledger_manifest(uuid,uuid), public.put_ledger_entries(uuid,jsonb) to authenticated;
commit;
