-- Add shared family presentation without changing account binding or ledger permissions.
begin;
alter table public.families add column icon text not null default 'home'
  check (icon in ('home', 'heart', 'tree', 'sun'));

create or replace function public.get_my_family() returns jsonb
language sql stable security definer set search_path = '' as $$
  select jsonb_build_object('id', f.id, 'name', f.name, 'icon', f.icon, 'is_owner', f.owner_id = auth.uid())
  from public.families f join public.family_members m on m.family_id = f.id where m.user_id = auth.uid();
$$;

create function public.update_family_profile(p_name text, p_icon text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_family uuid;
begin
  if v_uid is null then raise exception 'authentication_required' using errcode = '42501'; end if;
  select f.id into v_family from public.families f
    join public.family_members m on m.family_id = f.id and m.user_id = v_uid
    where f.owner_id = v_uid for update of f;
  if v_family is null then raise exception 'owner_required' using errcode = '42501'; end if;
  if p_name is null or length(btrim(p_name)) not between 1 and 80 or p_name ~ '[[:cntrl:]]' then
    raise exception 'invalid_family_name';
  end if;
  if p_icon is null or p_icon not in ('home', 'heart', 'tree', 'sun') then raise exception 'invalid_family_icon'; end if;
  update public.families set name = btrim(p_name), icon = p_icon where id = v_family;
  return public.get_my_family();
end;
$$;
revoke all on function public.get_my_family(), public.update_family_profile(text, text) from public, anon, authenticated;
grant execute on function public.get_my_family(), public.update_family_profile(text, text) to authenticated;
commit;
