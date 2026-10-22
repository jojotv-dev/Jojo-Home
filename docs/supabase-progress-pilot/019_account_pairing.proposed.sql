-- PROPOSITION NON APPLIQUEE. Dependance : 016 puis 018. Autorisation distincte requise.
-- Etend la progression a 32 installations maximum, sans associer ou activer un appareil ici.
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_setting('jojo.approved_019',true) is distinct from 'YES' then raise exception 'APPROVAL_019_REQUIRED'; end if;
    if to_regclass('jojo_account_settings.content_heads') is null or to_regclass('jojo_account_settings.pair_requests') is not null
        or not exists(select 1 from jojo_progress_pilot.pilot_profile_bindings
            where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and id='2e43435a-eda7-44d0-8d95-476a2a973446')
        or not exists(select 1 from pg_constraint where conrelid='jojo_progress_pilot.pilot_devices'::regclass and conname='pilot_devices_slot_check')
        or not exists(select 1 from pg_constraint where conrelid='jojo_progress_pilot.cloud_progress_scopes'::regclass and conname='cloud_progress_scopes_allowed_devices_check')
        then raise exception 'ACCOUNT_PAIR_PREFLIGHT_FAILED'; end if;
end;
$guard$;
create table jojo_account_settings.pair_requests(
    owner_id uuid not null, id uuid not null default gen_random_uuid(), installation uuid not null,
    device uuid not null, auth_session uuid not null, home_profile uuid not null,
    nonce uuid not null, public_key text not null check(length(public_key) between 350 and 500 and public_key~'^[A-Za-z0-9+/]+={0,2}$'),
    expires_at timestamptz not null default (clock_timestamp()+interval '10 minutes'),
    state text not null default 'pending' check(state in ('pending','approved','collected')),
    grant_value jsonb, approver_session uuid,
    primary key(owner_id,id), unique(owner_id,nonce),
    foreign key(owner_id,installation) references jojo_account_settings.installations(owner_id,id),
    foreign key(owner_id,home_profile) references jojo_account_settings.profiles(owner_id,id)
);
create table jojo_account_settings.enrollments(
    owner_id uuid not null, device uuid not null, installation uuid not null, profile uuid not null,
    request uuid not null, approver_session uuid not null,
    primary key(owner_id,device), unique(owner_id,installation),
    foreign key(owner_id,installation) references jojo_account_settings.installations(owner_id,id),
    foreign key(owner_id,profile) references jojo_progress_pilot.pilot_profile_bindings(owner_id,id),
    foreign key(owner_id,request) references jojo_account_settings.pair_requests(owner_id,id)
);
alter table jojo_account_settings.pair_requests enable row level security;
alter table jojo_account_settings.pair_requests force row level security;
alter table jojo_account_settings.enrollments enable row level security;
alter table jojo_account_settings.enrollments force row level security;
create policy pair_select on jojo_account_settings.pair_requests for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy pair_insert on jojo_account_settings.pair_requests for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and state='pending' and grant_value is null and approver_session is null
        and auth_session=(select session_id from jojo_progress_pilot.pilot_request_identity()));
create policy pair_update on jojo_account_settings.pair_requests for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy enrollment_select on jojo_account_settings.enrollments for select to jojo_account_settings_rpc,jojo_progress_pilot_rpc
    using(owner_id=(select owner_id from jojo_progress_pilot.pilot_request_identity()));
create policy enrollment_insert on jojo_account_settings.enrollments for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and approver_session=(select session_id from jojo_progress_pilot.pilot_request_identity()));
create policy enrollment_update on jojo_account_settings.enrollments for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner())
    with check(owner_id=jojo_account_settings.request_owner() and approver_session=(select session_id from jojo_progress_pilot.pilot_request_identity()));
revoke all on jojo_account_settings.pair_requests,jojo_account_settings.enrollments from public,anon,authenticated;
grant select,insert on jojo_account_settings.pair_requests,jojo_account_settings.enrollments to jojo_account_settings_rpc;
grant update(state,grant_value,approver_session) on jojo_account_settings.pair_requests to jojo_account_settings_rpc;
grant update(request,approver_session) on jojo_account_settings.enrollments to jojo_account_settings_rpc;
grant usage on schema jojo_account_settings to jojo_progress_pilot_rpc;
grant select on jojo_account_settings.enrollments to jojo_progress_pilot_rpc;

-- La politique historique A/B/C reste la premiere branche. Les nouveaux appareils
-- doivent avoir une approbation persistante et une session Auth qui leur est propre.
alter table jojo_progress_pilot.pilot_devices drop constraint pilot_devices_slot_check;
alter table jojo_progress_pilot.pilot_devices add constraint pilot_devices_slot_check check(slot between 1 and 32);
alter table jojo_progress_pilot.cloud_progress_scopes drop constraint cloud_progress_scopes_allowed_devices_check;
alter table jojo_progress_pilot.cloud_progress_scopes add constraint cloud_progress_scopes_allowed_devices_check
    check(cardinality(allowed_devices) between 1 and 32);
alter policy pilot_device_select on jojo_progress_pilot.pilot_devices using(
    active and jojo_progress_pilot.pilot_session_matches(owner_id,auth_session_id)
    and exists(select 1 from jojo_progress_pilot.pilot_profile_bindings b where b.owner_id=pilot_devices.owner_id and
        (pilot_devices.id=case pilot_devices.slot when 1 then b.device_a when 2 then b.device_b when 3 then b.device_c else null::uuid end
        or (pilot_devices.slot>=4 and exists(select 1 from jojo_account_settings.enrollments e
            where e.owner_id=b.owner_id and e.profile=b.id and e.device=pilot_devices.id)))));

-- Acces interne seulement : anon/authenticated n'ont ni droits sur le schema ni SET ROLE.
grant usage on schema jojo_progress_pilot to jojo_account_settings_rpc;
grant select on jojo_progress_pilot.pilot_devices,jojo_progress_pilot.pilot_profile_bindings,jojo_progress_pilot.cloud_progress_scopes to jojo_account_settings_rpc;
grant insert(owner_id,id,slot,auth_session_id,active) on jojo_progress_pilot.pilot_devices to jojo_account_settings_rpc;
grant update(auth_session_id) on jojo_progress_pilot.pilot_devices to jojo_account_settings_rpc;
grant update(allowed_devices) on jojo_progress_pilot.cloud_progress_scopes to jojo_account_settings_rpc;
create policy pair_device_select on jojo_progress_pilot.pilot_devices for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy pair_profile_select on jojo_progress_pilot.pilot_profile_bindings for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy pair_scope_select on jojo_progress_pilot.cloud_progress_scopes for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and enabled);
create policy pair_device_insert on jojo_progress_pilot.pilot_devices for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and slot>=4 and active and exists(
        select 1 from jojo_account_settings.enrollments e join jojo_account_settings.pair_requests p
            on p.owner_id=e.owner_id and p.id=e.request
        where e.owner_id=pilot_devices.owner_id and e.device=pilot_devices.id and p.device=pilot_devices.id
            and p.auth_session=pilot_devices.auth_session_id and p.expires_at>clock_timestamp() and p.state='approved'
            and e.approver_session=(select session_id from jojo_progress_pilot.pilot_request_identity())));
create policy pair_device_update on jojo_progress_pilot.pilot_devices for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and slot>=4 and active)
    with check(owner_id=jojo_account_settings.request_owner() and slot>=4 and active and exists(
        select 1 from jojo_account_settings.enrollments e join jojo_account_settings.pair_requests p on p.owner_id=e.owner_id and p.id=e.request
        where e.owner_id=pilot_devices.owner_id and e.device=pilot_devices.id and p.auth_session=pilot_devices.auth_session_id
            and p.expires_at>clock_timestamp() and p.state='approved' and e.approver_session=(select session_id from jojo_progress_pilot.pilot_request_identity())));
create policy pair_scope_update on jojo_progress_pilot.cloud_progress_scopes for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and enabled)
    with check(owner_id=jojo_account_settings.request_owner() and enabled);

create function jojo_account_settings.pair_request_value(p jojo_account_settings.pair_requests) returns jsonb
language sql stable security invoker set search_path='' as $$
 select jsonb_build_object('id',p.id,'owner',p.owner_id,'installation',p.installation,'device',p.device,
    'session',p.auth_session,'home_profile',p.home_profile,'nonce',p.nonce,'public_key',p.public_key,
    'expires_ms',floor(extract(epoch from p.expires_at)*1000)::bigint)
$$;
create function jojo_account_settings.pair_approver() returns uuid
language plpgsql stable security invoker set search_path='' as $$
declare v_device uuid;
begin
    select d.id into strict v_device from jojo_progress_pilot.pilot_devices d
    where d.owner_id=jojo_account_settings.request_owner() and d.active
        and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())
        and exists(select 1 from jojo_progress_pilot.cloud_progress_scopes s where s.owner_id=d.owner_id and s.enabled and d.id=any(s.allowed_devices));
    return v_device;
end;
$$;
create function public.account_pair_request(p_device uuid,p_proof text,p_target uuid,p_profile uuid,p_nonce uuid,p_public_key text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; r jojo_account_settings.pair_requests;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    perform 1 from jojo_account_settings.accounts where owner_id=v_owner for update;
    if p_target is null or p_target='00000000-0000-0000-0000-000000000000' or p_nonce is null
        or p_public_key is null or length(p_public_key) not between 350 and 500 or p_public_key!~'^[A-Za-z0-9+/]+={0,2}$'
        or not exists(select 1 from jojo_account_settings.entries e where e.owner_id=v_owner and e.subject=p_profile
            and e.scope='profile' and e.key='profiles.home' and e.value->>'primary'='true')
        or exists(select 1 from jojo_progress_pilot.pilot_devices d where d.owner_id=v_owner and d.id=p_target and d.slot<=3)
        then raise exception 'ACCOUNT_PAIR_REQUEST_INVALID'; end if;
    select * into r from jojo_account_settings.pair_requests where owner_id=v_owner and nonce=p_nonce;
    if found then
        if r.installation<>p_device or r.device<>p_target or r.public_key<>p_public_key or r.home_profile<>p_profile
            or r.auth_session<>(select session_id from jojo_progress_pilot.pilot_request_identity()) or r.expires_at<=clock_timestamp()
            then raise exception 'ACCOUNT_PAIR_NONCE_REUSED'; end if;
        return jojo_account_settings.pair_request_value(r);
    end if;
    if (select count(*) from jojo_account_settings.pair_requests where owner_id=v_owner and expires_at>clock_timestamp() and state<>'collected')>=5
        or (select count(*) from jojo_account_settings.pair_requests where owner_id=v_owner)>=1000 then raise exception 'ACCOUNT_PAIR_REQUEST_LIMIT'; end if;
    insert into jojo_account_settings.pair_requests(owner_id,installation,device,auth_session,home_profile,nonce,public_key)
        values(v_owner,p_device,p_target,(select session_id from jojo_progress_pilot.pilot_request_identity()),p_profile,p_nonce,p_public_key) returning * into r;
    return jojo_account_settings.pair_request_value(r);
end;
$$;
create function public.account_pair_list(p_device uuid,p_proof text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; result jsonb;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof); perform jojo_account_settings.pair_approver();
    select coalesce(jsonb_agg(jojo_account_settings.pair_request_value(p) order by p.expires_at),'[]') into result
        from jojo_account_settings.pair_requests p where p.owner_id=v_owner and p.state='pending' and p.expires_at>clock_timestamp();
    return result;
end;
$$;
create function public.account_pair_approve(p_device uuid,p_proof text,p_request uuid,p_grant jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; approver uuid; r jojo_account_settings.pair_requests; v_profile uuid; v_epoch uuid;
    v_slot smallint; previous jojo_account_settings.enrollments;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof); approver:=jojo_account_settings.pair_approver();
    -- Same lock as request creation: slot allocation and approvals cannot race for this account.
    perform 1 from jojo_account_settings.accounts where owner_id=v_owner for update;
    select * into strict r from jojo_account_settings.pair_requests where owner_id=v_owner and id=p_request for update;
    if r.expires_at<=clock_timestamp() or r.installation=p_device
        or not exists(select 1 from jojo_account_settings.installations i where i.owner_id=v_owner and i.id=r.installation and i.active and i.auth_session_id=r.auth_session)
        then raise exception 'ACCOUNT_PAIR_EXPIRED_OR_CHANGED'; end if;
    if r.state<>'pending' then
        if r.grant_value=p_grant then return jsonb_build_object('schema',1,'approved',true); end if;
        raise exception 'ACCOUNT_PAIR_ALREADY_APPROVED';
    end if;
    if jsonb_typeof(p_grant) is distinct from 'object' or (select count(*) from jsonb_object_keys(p_grant))<>6
        or not(p_grant ?& array['request','profile','key_epoch','device_a','device_b','packet'])
        or p_grant->'request' is distinct from jojo_account_settings.pair_request_value(r)
        or p_grant->>'packet' is null or length(p_grant->>'packet')<>344 or p_grant->>'packet'!~'^[A-Za-z0-9+/]+==$'
        then raise exception 'ACCOUNT_PAIR_GRANT_INVALID'; end if;
    v_profile:=(p_grant->>'profile')::uuid; v_epoch:=(p_grant->>'key_epoch')::uuid;
    if not exists(select 1 from jojo_progress_pilot.cloud_progress_scopes s join jojo_progress_pilot.pilot_profile_bindings b
        on b.owner_id=s.owner_id and b.id=s.profile_binding_id
        where s.owner_id=v_owner and s.profile_binding_id=v_profile and s.key_epoch=v_epoch and s.enabled
            and approver=any(s.allowed_devices) and b.device_a=(p_grant->>'device_a')::uuid and b.device_b=(p_grant->>'device_b')::uuid)
        then raise exception using errcode='42501',message='ACCOUNT_PAIR_SCOPE_DENIED'; end if;
    select * into previous from jojo_account_settings.enrollments where owner_id=v_owner and (device=r.device or installation=r.installation);
    if found and (previous.device<>r.device or previous.installation<>r.installation or previous.profile<>v_profile) then raise exception 'ACCOUNT_PAIR_IDENTITY_CHANGED'; end if;
    select slot into v_slot from jojo_progress_pilot.pilot_devices where owner_id=v_owner and id=r.device and slot>=4 and active;
    if v_slot is null then
        select min(n)::smallint into v_slot from generate_series(4,32) n where not exists(
            select 1 from jojo_progress_pilot.pilot_devices d where d.owner_id=v_owner and d.slot=n);
        if v_slot is null then raise exception 'ACCOUNT_PAIR_DEVICE_LIMIT'; end if;
    end if;
    update jojo_account_settings.pair_requests set state='approved',grant_value=p_grant,
        approver_session=(select session_id from jojo_progress_pilot.pilot_request_identity()) where owner_id=v_owner and id=r.id;
    insert into jojo_account_settings.enrollments(owner_id,device,installation,profile,request,approver_session)
        values(v_owner,r.device,r.installation,v_profile,r.id,(select session_id from jojo_progress_pilot.pilot_request_identity()))
        on conflict(owner_id,device) do update set request=excluded.request,approver_session=excluded.approver_session;
    insert into jojo_progress_pilot.pilot_devices(owner_id,id,slot,auth_session_id,active)
        values(v_owner,r.device,v_slot,r.auth_session,true)
        on conflict(owner_id,id) do update set auth_session_id=excluded.auth_session_id;
    update jojo_progress_pilot.cloud_progress_scopes set allowed_devices=array_append(allowed_devices,r.device)
        where owner_id=v_owner and profile_binding_id=v_profile and key_epoch=v_epoch and not(r.device=any(allowed_devices));
    return jsonb_build_object('schema',1,'approved',true);
end;
$$;
create function public.account_pair_collect(p_device uuid,p_proof text,p_request uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_owner uuid; r jojo_account_settings.pair_requests;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    select * into strict r from jojo_account_settings.pair_requests where owner_id=v_owner and id=p_request for update;
    if r.installation<>p_device or r.auth_session<>(select session_id from jojo_progress_pilot.pilot_request_identity())
        or r.expires_at<=clock_timestamp() or r.state not in ('approved','collected') or r.grant_value is null
        then raise exception using errcode='42501',message='ACCOUNT_PAIR_NOT_READY_OR_DENIED'; end if;
    update jojo_account_settings.pair_requests set state='collected' where owner_id=v_owner and id=p_request;
    return r.grant_value;
end;
$$;
revoke all on function jojo_account_settings.pair_request_value(jojo_account_settings.pair_requests),jojo_account_settings.pair_approver() from public,anon,authenticated;
grant execute on function jojo_account_settings.pair_request_value(jojo_account_settings.pair_requests),jojo_account_settings.pair_approver() to jojo_account_settings_rpc;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_pair_request(uuid,text,uuid,uuid,uuid,text) owner to jojo_account_settings_rpc;
alter function public.account_pair_list(uuid,text) owner to jojo_account_settings_rpc;
alter function public.account_pair_approve(uuid,text,uuid,jsonb) owner to jojo_account_settings_rpc;
alter function public.account_pair_collect(uuid,text,uuid) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
-- Les ACL doivent etre gerees par le proprietaire effectif. SET seul n'implique pas INHERIT.
set local role jojo_account_settings_rpc;
revoke all on function public.account_pair_request(uuid,text,uuid,uuid,uuid,text),public.account_pair_list(uuid,text),
    public.account_pair_approve(uuid,text,uuid,jsonb),public.account_pair_collect(uuid,text,uuid) from public,anon,authenticated;
grant execute on function public.account_pair_request(uuid,text,uuid,uuid,uuid,text),public.account_pair_list(uuid,text),
    public.account_pair_approve(uuid,text,uuid,jsonb),public.account_pair_collect(uuid,text,uuid) to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;
do $permissions$
begin
    if exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname='public' and p.proname in ('account_pair_request','account_pair_list','account_pair_approve','account_pair_collect')
            and (has_function_privilege('anon',p.oid,'EXECUTE') or not has_function_privilege('authenticated',p.oid,'EXECUTE')))
        then raise exception 'ACCOUNT_PAIR_RPC_PERMISSIONS_INVALID'; end if;
end;
$permissions$;
commit;
