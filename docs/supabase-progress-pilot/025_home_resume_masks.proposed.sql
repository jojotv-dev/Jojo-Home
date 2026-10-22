-- PROPOSITION NON EXECUTEE : accord distinct requis avant application.
-- Projet jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Ajoute le partage des retraits de l'accueil, par profil Home et identite opaque.
-- Aucun titre, URL, intent, position de lecture, compte Auth ou reglage existant n'est importe.
-- Les RPC des parametres et de l'activite IPTV restent inchangées (anciennes APK compatibles).
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_025 = 'YES';
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';

do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_025',true) is distinct from 'YES' then
        raise exception 'HOME_RESUME_MASKS_APPROVAL_REQUIRED';
    end if;
    if to_regclass('jojo_account_settings.resume_masks') is not null
       or to_regprocedure('jojo_account_settings.require_device(uuid,text)') is null
       or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc'
           and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit) then
        raise exception 'HOME_RESUME_MASKS_PREFLIGHT_FAILED';
    end if;
end;
$guard$;

create table jojo_account_settings.resume_mask_heads(
    owner_id uuid primary key references jojo_account_settings.accounts(owner_id) on delete cascade,
    revision bigint not null default 0 check(revision>=0)
);
create table jojo_account_settings.resume_masks(
    owner_id uuid not null references jojo_account_settings.accounts(owner_id) on delete cascade,
    profile uuid not null,
    key text not null check(key ~ '^[0-9a-f]{64}$'),
    -- Positif = retrait explicite ; negatif = reprise explicite ulterieure.
    -- A date egale, le retrait l'emporte ; une observation inchangee n'ecrit jamais ici.
    stamp bigint not null check(stamp<>0 and stamp between -253402300799999 and 253402300799999),
    revision bigint not null check(revision>0),
    last_device uuid not null,
    primary key(owner_id,profile,key),
    unique(owner_id,revision),
    foreign key(owner_id,profile) references jojo_account_settings.profiles(owner_id,id),
    foreign key(owner_id,last_device) references jojo_account_settings.installations(owner_id,id)
);
create index resume_masks_read on jojo_account_settings.resume_masks(owner_id,revision);
alter table jojo_account_settings.resume_mask_heads enable row level security;
alter table jojo_account_settings.resume_mask_heads force row level security;
alter table jojo_account_settings.resume_masks enable row level security;
alter table jojo_account_settings.resume_masks force row level security;
create policy resume_head_select on jojo_account_settings.resume_mask_heads for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy resume_head_insert on jojo_account_settings.resume_mask_heads for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy resume_head_update on jojo_account_settings.resume_mask_heads for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy resume_mask_select on jojo_account_settings.resume_masks for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy resume_mask_insert on jojo_account_settings.resume_masks for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy resume_mask_update on jojo_account_settings.resume_masks for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
revoke all on jojo_account_settings.resume_mask_heads,jojo_account_settings.resume_masks from public,anon,authenticated;
grant select,insert on jojo_account_settings.resume_mask_heads,jojo_account_settings.resume_masks to jojo_account_settings_rpc;
grant update(revision) on jojo_account_settings.resume_mask_heads to jojo_account_settings_rpc;
grant update(stamp,revision,last_device) on jojo_account_settings.resume_masks to jojo_account_settings_rpc;

create function public.account_resume_masks_sync(p_device uuid,p_proof text,p_after bigint,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
    own uuid:=jojo_account_settings.require_device(p_device,p_proof);
    item jsonb; mask_profile uuid; mask_key text; mask_stamp bigint; old_stamp bigint;
    head bigint:=0; next_revision bigint; result jsonb;
begin
    if p_after is null or p_after<0 or jsonb_typeof(p_ops) is distinct from 'array'
       or jsonb_array_length(p_ops)>50 or octet_length(p_ops::text)>16384 then
        raise exception using errcode='22023',message='HOME_MASK_BATCH_INVALID';
    end if;
    -- Same owner lock as settings writes: commit order and the revision cursor agree.
    perform 1 from jojo_account_settings.accounts where owner_id=own and initialized for update;
    if not found then raise exception using errcode='42501',message='HOME_MASK_ACCOUNT_NOT_READY'; end if;
    if jsonb_array_length(p_ops)>0 then
        insert into jojo_account_settings.resume_mask_heads(owner_id) values(own) on conflict do nothing;
    end if;
    for item in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(item)<>'object' or (select count(*) from jsonb_object_keys(item))<>3
           or not item ?& array['profile','key','stamp']
           or jsonb_typeof(item->'profile')<>'string' or (item->>'profile') !~ '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
           or jsonb_typeof(item->'key')<>'string' or (item->>'key') !~ '^[0-9a-f]{64}$'
           or jsonb_typeof(item->'stamp')<>'number' or (item->>'stamp') !~ '^-?[0-9]+$' then
            raise exception using errcode='22023',message='HOME_MASK_INVALID';
        end if;
        if (item->>'stamp')::numeric=0 or abs((item->>'stamp')::numeric)>253402300799999 then
            raise exception using errcode='22023',message='HOME_MASK_CLOCK_INVALID';
        end if;
        mask_profile:=(item->>'profile')::uuid; mask_key:=item->>'key'; mask_stamp:=(item->>'stamp')::bigint;
        if not exists(select 1 from jojo_account_settings.profiles where owner_id=own and id=mask_profile and domain='home') then
            raise exception using errcode='42501',message='HOME_MASK_PROFILE_NOT_OWNED';
        end if;
        select stamp into old_stamp from jojo_account_settings.resume_masks where owner_id=own and profile=mask_profile and key=mask_key;
        if old_stamp is not null and (abs(old_stamp)>abs(mask_stamp) or abs(old_stamp)=abs(mask_stamp) and old_stamp>=mask_stamp) then
            continue;
        end if;
        if old_stamp is null and (select count(*) from jojo_account_settings.resume_masks where owner_id=own)>=10000 then
            raise exception using errcode='54000',message='HOME_MASK_LIMIT';
        end if;
        update jojo_account_settings.resume_mask_heads set revision=revision+1 where owner_id=own returning revision into strict next_revision;
        insert into jojo_account_settings.resume_masks(owner_id,profile,key,stamp,revision,last_device)
            values(own,mask_profile,mask_key,mask_stamp,next_revision,p_device)
            on conflict(owner_id,profile,key) do update set stamp=excluded.stamp,revision=excluded.revision,last_device=excluded.last_device;
    end loop;
    select revision into head from jojo_account_settings.resume_mask_heads where owner_id=own;
    head:=coalesce(head,0);
    if p_after>head then raise exception using errcode='22023',message='HOME_MASK_CURSOR_INVALID'; end if;
    select coalesce(jsonb_agg(jsonb_build_object('profile',profile,'key',key,'stamp',stamp,'revision',revision) order by revision),'[]'::jsonb),
        coalesce(max(revision),p_after) into result,next_revision
        from (select profile,key,stamp,revision from jojo_account_settings.resume_masks where owner_id=own and revision>p_after
            order by revision limit 500) page_rows;
    return jsonb_build_object('schema',1,'next_cursor',next_revision,'head_revision',head,'rows',result);
end;
$$;

grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_resume_masks_sync(uuid,text,bigint,jsonb) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
set local role jojo_account_settings_rpc;
revoke all on function public.account_resume_masks_sync(uuid,text,bigint,jsonb) from public,anon,authenticated;
grant execute on function public.account_resume_masks_sync(uuid,text,bigint,jsonb) to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;
do $permissions$
begin
    if has_function_privilege('anon','public.account_resume_masks_sync(uuid,text,bigint,jsonb)','EXECUTE')
       or not has_function_privilege('authenticated','public.account_resume_masks_sync(uuid,text,bigint,jsonb)','EXECUTE') then
        raise exception 'HOME_RESUME_MASKS_PERMISSIONS_INVALID';
    end if;
end;
$permissions$;
notify pgrst,'reload schema';
commit;
