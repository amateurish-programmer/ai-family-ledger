-- Synthetic transaction: legacy single/batch RPC preservation, CAS and permissions.
begin;
insert into auth.users(id,email) values
 ('92000000-0000-4000-8000-000000000001','gift-owner@example.invalid'),
 ('92000000-0000-4000-8000-000000000002','gift-other@example.invalid');
insert into public.families(id,name,owner_id) values
 ('93000000-0000-4000-8000-000000000001','Gift fixture','92000000-0000-4000-8000-000000000001');
insert into public.family_members(family_id,user_id) values
 ('93000000-0000-4000-8000-000000000001','92000000-0000-4000-8000-000000000001');
set local role authenticated;
select set_config('request.jwt.claim.sub','92000000-0000-4000-8000-000000000001',true);
do $$
declare
 f uuid := '93000000-0000-4000-8000-000000000001';
 i uuid := '94000000-0000-4000-8000-000000000001';
 p jsonb; legacy jsonb; r jsonb; stored jsonb; rev bigint;
begin
 p := jsonb_build_object('format','family-ledger','version',2,'entries',jsonb_build_array(jsonb_build_object(
 'id',i,'type','EXPENSE','occurredOn','2026-09-07','amountMinor',10000,'currency','CNY',
 'categoryL1','Test','categoryL2','','account','Test','member','Test','recordedBy','Test',
 'merchant','','project','','note','','updatedAt',1,'deletedAt',null,'origin',null,
 'isGift',true,'counterparty','Test friend')));
 r := public.put_ledger_entry(f,i,p::text,0);
 if r->>'ok' <> 'true' or r->>'revision' <> '1' then raise exception 'initial write failed'; end if;
 legacy := jsonb_set(p,'{entries,0}',(p->'entries'->0)-'isGift'-'counterparty');
 legacy := jsonb_set(legacy,'{entries,0,note}','"legacy edit"');
 r := public.put_ledger_entry(f,i,legacy::text,1);
 select payload::jsonb,revision into stored,rev from public.ledger_entries where family_id=f and id=i;
 if rev <> 2 or r->>'revision' <> '2' or stored#>>'{entries,0,isGift}' <> 'true'
   or stored#>>'{entries,0,counterparty}' <> 'Test friend' or stored#>>'{entries,0,note}' <> 'legacy edit'
   or stored#>>'{entries,0,updatedAt}' <> '1' then raise exception 'legacy preservation failed'; end if;
 r := public.put_ledger_entry(f,i,legacy::text,1);
 if r->>'ok' <> 'false' or r->>'revision' <> '2' then raise exception 'stale CAS accepted'; end if;
 r := public.put_ledger_entries(f,jsonb_build_array(jsonb_build_object('id',i,'payload',legacy::text,'expected_revision',2)));
 select payload::jsonb,revision into stored,rev from public.ledger_entries where family_id=f and id=i;
 if rev <> 3 or stored#>>'{entries,0,isGift}' <> 'true' then raise exception 'batch preservation failed'; end if;
 p := jsonb_set(p,'{entries,0,isGift}','false');
 p := jsonb_set(p,'{entries,0,counterparty}','""');
 r := public.put_ledger_entry(f,i,p::text,3);
 select payload::jsonb,revision into stored,rev from public.ledger_entries where family_id=f and id=i;
 if rev <> 4 or stored#>>'{entries,0,isGift}' <> 'false' or stored#>>'{entries,0,counterparty}' <> '' then
   raise exception 'explicit clearing failed'; end if;
 begin
   perform public.put_ledger_entry(f,i,jsonb_set(p,'{entries,0,isGift}','"true"')::text,4);
   raise exception 'invalid gift accepted';
 exception when raise_exception then if sqlerrm <> 'invalid_gift_flag' then raise; end if; end;
 begin
   perform public.put_ledger_entry(f,i,jsonb_set(p,'{entries,0,counterparty}',to_jsonb(repeat('x',101)))::text,4);
   raise exception 'long counterparty accepted';
 exception when raise_exception then if sqlerrm <> 'invalid_counterparty' then raise; end if; end;
 if (select revision from public.ledger_entries where family_id=f and id=i) <> 4 then raise exception 'invalid payload advanced revision'; end if;
 begin
   update public.ledger_entries set payload=legacy::text where family_id=f and id=i;
   raise exception 'direct update accepted';
 exception when insufficient_privilege then null; end;
 perform set_config('request.jwt.claim.sub','92000000-0000-4000-8000-000000000002',true);
 begin
   perform public.put_ledger_entry(f,i,p::text,4);
   raise exception 'foreign member write accepted';
 exception when insufficient_privilege then null; end;
end;
$$;
set local role anon;
do $$ begin
 begin
   perform public.put_ledger_entry('93000000-0000-4000-8000-000000000001','94000000-0000-4000-8000-000000000001','{}',0);
   raise exception 'anonymous write accepted';
 exception when insufficient_privilege then null; end;
end $$;
rollback;
