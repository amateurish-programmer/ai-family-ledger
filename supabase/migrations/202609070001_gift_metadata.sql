-- V1.2: retain explicit gift metadata when older clients omit optional fields.
-- The existing membership check, actor assignment and CAS remain the only write API.
begin;
create function ledger_private.preserve_gift_metadata() returns trigger
language plpgsql set search_path = '' as $$
declare v_root jsonb; v_row jsonb; v_old jsonb; v_key text;
begin
  v_root := new.payload::jsonb;
  v_row := v_root->'entries'->0;
  if tg_op = 'UPDATE' then
    v_old := old.payload::jsonb->'entries'->0;
    foreach v_key in array array['isGift', 'counterparty'] loop
      if not (v_row ? v_key) and v_old ? v_key then
        v_row := v_row || jsonb_build_object(v_key, v_old->v_key);
      end if;
    end loop;
  end if;
  if v_row ? 'isGift' and jsonb_typeof(v_row->'isGift') is distinct from 'boolean' then
    raise exception 'invalid_gift_flag';
  end if;
  if v_row ? 'counterparty' and (jsonb_typeof(v_row->'counterparty') is distinct from 'string'
      or length(v_row->>'counterparty') > 100) then
    raise exception 'invalid_counterparty';
  end if;
  -- Do not reserialize unchanged payloads: sync hashes are based on serialized text.
  if v_row is distinct from v_root->'entries'->0 then
    new.payload := jsonb_set(v_root, '{entries,0}', v_row)::text;
  end if;
  perform ledger_private.validate_payload(new.id, new.payload);
  return new;
end;
$$;
revoke all on function ledger_private.preserve_gift_metadata() from public, anon, authenticated;
create trigger preserve_gift_metadata before insert or update of payload on public.ledger_entries
for each row execute function ledger_private.preserve_gift_metadata();
commit;
