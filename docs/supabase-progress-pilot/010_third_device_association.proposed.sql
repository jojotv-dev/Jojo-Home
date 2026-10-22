-- PROPOSITION NON EXECUTEE. Accord distinct sur cette association apres 009.
-- Ne pas reutiliser le SQL 004 : il exige des tables vides et concerne seulement A/B.
-- Parametres de session administrative a renseigner seulement apres revue et accord :
--   set jojo.progress_pilot_extension_project_ref = 'jtdnwdbapkdkpgtceauh';
--   set jojo.progress_pilot_third_association_authorized = 'APPROVED_THIRD_DEVICE_ASSOCIATION';
--   set jojo.progress_pilot_third_auth_session = 'a3ebaaf8-eff5-4393-8183-c749597d5568';
-- Session C relevee apres connexion au compte Auth de test attendu, en NAS_ONLY.
-- La garde exige exactement cette session ; aucun jeton ni secret n'est inclus.
-- Effet : champ device_c du profil existant et UNE nouvelle ligne appareil/session.
-- active=true autorise les RPC pour cette session ; aucun miroir Android n'est active.
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

do $preflight$
begin
    if current_setting('jojo.progress_pilot_extension_project_ref', true)
            is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.progress_pilot_third_association_authorized', true)
            is distinct from 'APPROVED_THIRD_DEVICE_ASSOCIATION'
        or current_user <> 'postgres'
        or not exists (select 1 from pg_catalog.pg_roles where rolname = current_user and rolbypassrls) then
        raise exception using errcode = '42501', message = 'PILOT_THIRD_ASSOCIATION_APPROVAL_REQUIRED';
    end if;
    if current_setting('jojo.progress_pilot_third_auth_session', true)
            is distinct from 'a3ebaaf8-eff5-4393-8183-c749597d5568'
        or to_regprocedure('public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)') is null then
        raise exception 'PILOT_THIRD_SESSION_AND_APPROVED_EXTENSION_REQUIRED';
    end if;
end;
$preflight$;

lock table jojo_progress_pilot.pilot_profile_bindings,
    jojo_progress_pilot.pilot_devices,
    jojo_progress_pilot.pilot_watch_progress in share row exclusive mode;

create temporary table pilot_third_before on commit drop as
select
    (select md5(jsonb_agg(to_jsonb(b) order by b.owner_id)::text)
        from jojo_progress_pilot.pilot_profile_bindings b) as bindings,
    (select md5(jsonb_agg(to_jsonb(d) order by d.owner_id, d.id)::text)
        from jojo_progress_pilot.pilot_devices d) as devices,
    (select md5(coalesce(jsonb_agg(to_jsonb(w) order by w.owner_id, w.profile_binding_id, w.device_id, w.content_key)::text, '[]'))
        from jojo_progress_pilot.pilot_watch_progress w) as progress;

do $association$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_binding constant uuid := '2e43435a-eda7-44d0-8d95-476a2a973446';
    v_a constant uuid := 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba';
    v_b constant uuid := '12bb7efd-7782-4903-8fd1-052ea5db924d';
    v_c constant uuid := '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
    v_session uuid := current_setting('jojo.progress_pilot_third_auth_session')::uuid;
    v_rows bigint;
begin
    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings) <> 1
        or (select count(*) from jojo_progress_pilot.pilot_devices) <> 2
        or not exists (select 1 from jojo_progress_pilot.pilot_profile_bindings
            where owner_id = v_owner and id = v_binding and device_a = v_a and device_b = v_b and device_c is null)
        or not exists (select 1 from jojo_progress_pilot.pilot_devices where owner_id = v_owner and id = v_a and slot = 1)
        or not exists (select 1 from jojo_progress_pilot.pilot_devices where owner_id = v_owner and id = v_b and slot = 2)
        or v_c in (v_a, v_b)
        or exists (select 1 from jojo_progress_pilot.pilot_devices where id = v_c or auth_session_id = v_session) then
        raise exception 'PILOT_THIRD_EXPECTED_PREASSOCIATION_REQUIRED_NO_OVERWRITE';
    end if;
    perform 1 from auth.users where id = v_owner and is_anonymous is false for share;
    if not found then raise exception 'PILOT_THIRD_EXPECTED_OWNER_REQUIRED'; end if;
    perform 1 from auth.sessions where id = v_session and user_id = v_owner
        and (not_after is null or not_after > clock_timestamp()) for share;
    if not found then raise exception 'PILOT_THIRD_EXPECTED_AUTH_SESSION_REQUIRED'; end if;

    update jojo_progress_pilot.pilot_profile_bindings set device_c = v_c
        where owner_id = v_owner and id = v_binding and device_c is null;
    get diagnostics v_rows = row_count;
    if v_rows <> 1 then raise exception 'PILOT_THIRD_BINDING_UPDATE_FAILED'; end if;
    insert into jojo_progress_pilot.pilot_devices(owner_id, id, slot, auth_session_id, active)
        values (v_owner, v_c, 3, v_session, true);

    if (select count(*) from jojo_progress_pilot.pilot_devices) <> 3
        or not exists (select 1 from pg_temp.pilot_third_before old
            where old.bindings is not distinct from
                (select md5(jsonb_agg(to_jsonb(b) || jsonb_build_object('device_c', null) order by b.owner_id)::text)
                    from jojo_progress_pilot.pilot_profile_bindings b)
                and old.devices is not distinct from
                    (select md5(jsonb_agg(to_jsonb(d) order by d.owner_id, d.id)::text)
                        from jojo_progress_pilot.pilot_devices d where d.id <> v_c)
                and old.progress is not distinct from
                    (select md5(coalesce(jsonb_agg(to_jsonb(w) order by w.owner_id, w.profile_binding_id, w.device_id, w.content_key)::text, '[]'))
                        from jojo_progress_pilot.pilot_watch_progress w)) then
        raise exception 'PILOT_THIRD_ASSOCIATION_POSTCONDITION_FAILED';
    end if;
end;
$association$;

select b.id as profile_binding_id, d.id as device_id, d.slot, d.auth_session_id, d.active
from jojo_progress_pilot.pilot_profile_bindings b
join jojo_progress_pilot.pilot_devices d on d.owner_id = b.owner_id
order by d.slot;
commit;
