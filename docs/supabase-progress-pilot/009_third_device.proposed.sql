-- PROPOSITION NON EXECUTEE : accord distinct requis avant toute application.
-- Projet de test jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Extension de capacite : aucun appareil/session/checkpoint n'est ajoute ici.
-- A/B et leurs deux RPC existantes restent compatibles et inchanges.
-- Apres accord, la session administrative doit confirmer explicitement :
--   set jojo.progress_pilot_extension_project_ref = 'jtdnwdbapkdkpgtceauh';
--   set jojo.progress_pilot_extension_authorized = 'APPROVED_THIRD_DEVICE_SCHEMA';
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

do $preflight$
begin
    if current_setting('jojo.progress_pilot_extension_project_ref', true)
            is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.progress_pilot_extension_authorized', true)
            is distinct from 'APPROVED_THIRD_DEVICE_SCHEMA'
        or current_user <> 'postgres' then
        raise exception using errcode = '42501', message = 'PILOT_EXTENSION_APPROVAL_REQUIRED';
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = current_user and rolbypassrls)
        or not exists (select 1 from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc'
            and not rolcanlogin and not rolinherit and not rolbypassrls and not rolsuper)
        or pg_has_role('anon', 'jojo_progress_pilot_rpc', 'MEMBER')
        or pg_has_role('authenticated', 'jojo_progress_pilot_rpc', 'MEMBER')
        or has_schema_privilege('jojo_progress_pilot_rpc', 'public', 'CREATE') then
        raise exception 'PILOT_EXTENSION_ROLE_STATE_CHANGED';
    end if;
    if (select count(*) from pg_catalog.pg_class c
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'jojo_progress_pilot'
                and c.relname in ('pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress')
                and c.relkind = 'r' and c.relrowsecurity and c.relforcerowsecurity
                and c.relowner <> (select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')) <> 3
        or has_schema_privilege('anon', 'jojo_progress_pilot', 'USAGE')
        or has_schema_privilege('authenticated', 'jojo_progress_pilot', 'USAGE') then
        raise exception 'PILOT_EXTENSION_RLS_STATE_CHANGED';
    end if;
    if exists (select 1 from pg_catalog.pg_attribute
            where attrelid = 'jojo_progress_pilot.pilot_profile_bindings'::regclass
                and attname = 'device_c' and not attisdropped)
        or to_regprocedure('public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)') is not null then
        raise exception 'PILOT_EXTENSION_ALREADY_PRESENT_REVIEW_REQUIRED';
    end if;
    if to_regprocedure('jojo_progress_pilot.pilot_request_identity()') is null
        or to_regprocedure('jojo_progress_pilot.pilot_session_matches(uuid,uuid)') is null
        or not has_function_privilege('jojo_progress_pilot_rpc', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'jojo_progress_pilot.pilot_session_matches(uuid,uuid)', 'EXECUTE') then
        raise exception 'PILOT_EXTENSION_AUTH_HELPERS_REQUIRED';
    end if;
    -- Empreintes des corps apres la correction Auth 003, fins de ligne normalisees.
    if not exists (select 1 from pg_catalog.pg_proc p
            where p.oid = 'public.pilot_register_device(uuid,uuid,uuid,uuid)'::regprocedure
                and p.prosecdef and p.proconfig @> array['search_path=""']::text[]
                and p.proowner = (select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')
                and md5(replace(p.prosrc, E'\r\n', E'\n')) = '4ded89248ef210ef07ff38ba80471e97')
        or not exists (select 1 from pg_catalog.pg_proc p
            where p.oid = 'public.pilot_write_progress(jsonb)'::regprocedure
                and p.prosecdef and p.proconfig @> array['search_path=""']::text[]
                and p.proowner = (select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')
                and md5(replace(p.prosrc, E'\r\n', E'\n')) = '2f51c07139dec9330c4ce09d3f290b0e') then
        raise exception 'PILOT_EXTENSION_EXISTING_RPC_CHANGED';
    end if;
    if not exists (select 1 from pg_catalog.pg_policy
            where polrelid = 'jojo_progress_pilot.pilot_devices'::regclass
                and polname = 'pilot_device_select' and polcmd = 'r'
                and polroles = array[(select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')])
        or not exists (select 1 from pg_catalog.pg_constraint
            where conrelid = 'jojo_progress_pilot.pilot_devices'::regclass
                and conname = 'pilot_devices_slot_check' and contype = 'c' and convalidated) then
        raise exception 'PILOT_EXTENSION_EXISTING_POLICY_OR_CONSTRAINT_CHANGED';
    end if;
end;
$preflight$;

lock table jojo_progress_pilot.pilot_profile_bindings,
    jojo_progress_pilot.pilot_devices,
    jojo_progress_pilot.pilot_watch_progress in share row exclusive mode;

do $identity$
begin
    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings) <> 1
        or not exists (select 1 from jojo_progress_pilot.pilot_profile_bindings
            where owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'
                and id = '2e43435a-eda7-44d0-8d95-476a2a973446'
                and device_a = 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba'
                and device_b = '12bb7efd-7782-4903-8fd1-052ea5db924d')
        or (select count(*) from jojo_progress_pilot.pilot_devices) <> 2
        or not exists (select 1 from jojo_progress_pilot.pilot_devices
            where owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'
                and id = 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba' and slot = 1)
        or not exists (select 1 from jojo_progress_pilot.pilot_devices
            where owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'
                and id = '12bb7efd-7782-4903-8fd1-052ea5db924d' and slot = 2) then
        raise exception 'PILOT_EXTENSION_EXPECTED_TEST_BINDING_REQUIRED';
    end if;
end;
$identity$;

-- Empreintes internes temporaires seulement : aucune progression n'est retournee.
create temporary table pilot_extension_before on commit drop as
select
    (select md5(jsonb_agg(to_jsonb(b) order by b.owner_id)::text)
        from jojo_progress_pilot.pilot_profile_bindings b) as bindings,
    (select md5(jsonb_agg(to_jsonb(d) order by d.owner_id, d.id)::text)
        from jojo_progress_pilot.pilot_devices d) as devices,
    (select md5(coalesce(jsonb_agg(to_jsonb(w) order by w.owner_id, w.profile_binding_id, w.device_id, w.content_key)::text, '[]'))
        from jojo_progress_pilot.pilot_watch_progress w) as progress;

alter table jojo_progress_pilot.pilot_profile_bindings add column device_c uuid;
alter table jojo_progress_pilot.pilot_profile_bindings
    add constraint pilot_profile_device_c_distinct
        check (device_c is null or (device_c <> device_a and device_c <> device_b));
alter table jojo_progress_pilot.pilot_devices drop constraint pilot_devices_slot_check;
alter table jojo_progress_pilot.pilot_devices
    add constraint pilot_devices_slot_check check (slot in (1, 2, 3));

alter policy pilot_device_select on jojo_progress_pilot.pilot_devices
    using (active and jojo_progress_pilot.pilot_session_matches(owner_id, auth_session_id)
        and exists (select 1 from jojo_progress_pilot.pilot_profile_bindings b
            where b.owner_id = pilot_devices.owner_id and pilot_devices.id =
                case pilot_devices.slot
                    when 1 then b.device_a when 2 then b.device_b when 3 then b.device_c
                    else null::uuid end));

-- RPC dediee a C : les signatures et corps utilises par A/B restent inchanges.
create function public.pilot_register_third_device(
    p_profile_binding_id uuid, p_device_a uuid, p_device_b uuid, p_device_c uuid, p_device_id uuid
) returns void language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_session uuid;
begin
    select identity.owner_id, identity.session_id into strict v_owner, v_session
        from jojo_progress_pilot.pilot_request_identity() as identity;
    if p_device_c is null or p_device_id is distinct from p_device_c
        or not coalesce(jojo_progress_pilot.pilot_session_matches(v_owner, v_session), false)
        or not exists (
            select 1 from jojo_progress_pilot.pilot_profile_bindings b
            join jojo_progress_pilot.pilot_devices d on d.owner_id = b.owner_id
            where b.owner_id = v_owner and b.id = p_profile_binding_id
                and b.device_a = p_device_a and b.device_b = p_device_b and b.device_c = p_device_c
                and d.id = p_device_c and d.slot = 3 and d.active and d.auth_session_id = v_session
        ) then
        raise exception using errcode = '42501', message = 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED';
    end if;
end;
$$;
-- CREATE est requis pour transferer la propriete ; retire dans cette transaction.
-- Le role est NOLOGIN, sans membre anon/authenticated, et ses RPC ne font aucun DDL.
grant create on schema public to jojo_progress_pilot_rpc;
alter function public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid) owner to jojo_progress_pilot_rpc;
revoke create on schema public from jojo_progress_pilot_rpc;
revoke all on function public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid) from public, anon, authenticated;
grant execute on function public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid) to authenticated;

do $postflight$
begin
    if not exists (select 1 from pg_temp.pilot_extension_before old
            where old.bindings is not distinct from
                (select md5(jsonb_agg(to_jsonb(b) - 'device_c' order by b.owner_id)::text)
                    from jojo_progress_pilot.pilot_profile_bindings b)
                and old.devices is not distinct from
                    (select md5(jsonb_agg(to_jsonb(d) order by d.owner_id, d.id)::text)
                        from jojo_progress_pilot.pilot_devices d)
                and old.progress is not distinct from
                    (select md5(coalesce(jsonb_agg(to_jsonb(w) order by w.owner_id, w.profile_binding_id, w.device_id, w.content_key)::text, '[]'))
                        from jojo_progress_pilot.pilot_watch_progress w))
        or exists (select 1 from jojo_progress_pilot.pilot_profile_bindings where device_c is not null)
        or has_schema_privilege('jojo_progress_pilot_rpc', 'public', 'CREATE')
        or has_function_privilege('anon', 'public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE')
        or not has_function_privilege('authenticated', 'public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)', 'EXECUTE') then
        raise exception 'PILOT_EXTENSION_POSTCONDITION_FAILED';
    end if;
end;
$postflight$;
select 'THIRD_DEVICE_CAPACITY_ONLY_NO_ASSOCIATION_OR_ACTIVATION' as result;
commit;
