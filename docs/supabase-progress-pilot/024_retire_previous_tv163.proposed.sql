-- PROPOSITION NON EXECUTEE : nouvel accord distinct requis.
-- jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Observation UI .163 : installation 68b2cda6-ba08-4109-a5a6-cb59fa9e5401,
-- NAS_ONLY, aucun profil de progression associe, verification HMAC requise.
-- Retire l'ANCIENNE installation C et annule son changement de session 023.
-- Conserve les progressions et libere la session de l'installation actuelle
-- pour le parcours d'association par code/cle chiffree deja disponible (019).
-- N'associe ni n'active la nouvelle installation. Aucun changement Auth/schema.
-- Apres accord, ajouter DANS la transaction avant DO :
-- SET LOCAL jojo.retire_tv163_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.retire_tv163_approved = 'APPROVED_RETIRE_PREVIOUS_TV163_024';
begin isolation level repeatable read;
set local lock_timeout = '3s';
set local statement_timeout = '30s';

do $retire$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_old_device constant uuid := '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
    v_current_device constant uuid := '68b2cda6-ba08-4109-a5a6-cb59fa9e5401';
    v_installation constant uuid := 'f6a3447e-58e3-4589-bf52-54456a3d97cc';
    v_current_hash constant text := '242890bd8c5aebdca6930bdbc843c53593e3cab6bdf6929294b62f800f02613b';
    v_prior_hash constant text := '2ede3d99fe8c35b55649b01f77351b31e005dcfcbfa49285e69351122ff0b3f5';
    v_before jojo_progress_pilot.pilot_devices%rowtype;
    v_current_session uuid;
    v_prior_session uuid;
    v_rows bigint;
begin
    if current_user <> 'postgres'
        or current_setting('jojo.retire_tv163_project_ref', true) is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.retire_tv163_approved', true) is distinct from 'APPROVED_RETIRE_PREVIOUS_TV163_024'
        or not exists (select 1 from pg_catalog.pg_roles where rolname=current_user and rolbypassrls) then
        raise exception using errcode='42501', message='RETIRE_TV163_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if exists (select 1 from pg_catalog.pg_trigger
        where tgrelid='jojo_progress_pilot.pilot_devices'::regclass and not tgisinternal) then
        raise exception 'RETIRE_TV163_UNREVIEWED_TRIGGER';
    end if;
    select * into strict v_before from jojo_progress_pilot.pilot_devices
        where owner_id=v_owner and id=v_old_device for update;
    select auth_session_id into strict v_current_session from jojo_account_settings.installations
        where owner_id=v_owner and id=v_installation and active and platform='firetv' for share;
    if v_before.slot<>3 or not v_before.active or v_before.auth_session_id<>v_current_session
        or encode(sha256(convert_to(v_current_session::text,'UTF8')),'hex')<>v_current_hash then
        raise exception 'RETIRE_TV163_EXPECTED_023_STATE_CHANGED';
    end if;
    perform 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id=v_owner and id='2e43435a-eda7-44d0-8d95-476a2a973446'
            and device_a='f8648865-cd01-4be7-bd4a-2e2c7b6659ba'
            and device_b='12bb7efd-7782-4903-8fd1-052ea5db924d'
            and device_c=v_old_device for share;
    if not found then raise exception 'RETIRE_TV163_PROFILE_BINDING_CHANGED'; end if;
    if exists (select 1 from jojo_progress_pilot.pilot_devices where id=v_current_device)
        or exists (select 1 from jojo_account_settings.enrollments
            where owner_id=v_owner and (device=v_current_device or installation=v_installation))
        or exists (select 1 from jojo_account_settings.pair_requests
            where owner_id=v_owner and state in ('pending','approved') and expires_at>clock_timestamp()) then
        raise exception 'RETIRE_TV163_PAIRING_STATE_CHANGED';
    end if;
    perform 1 from auth.users where id=v_owner and is_anonymous is false for share;
    if not found then raise exception 'RETIRE_TV163_OWNER_INVALID'; end if;
    perform 1 from auth.sessions where id=v_current_session and user_id=v_owner
        and (not_after is null or not_after>clock_timestamp()) for share;
    if not found then raise exception 'RETIRE_TV163_CURRENT_SESSION_INVALID'; end if;
    select id into strict v_prior_session from auth.sessions
        where user_id=v_owner and encode(sha256(convert_to(id::text,'UTF8')),'hex')=v_prior_hash
            and (not_after is null or not_after>clock_timestamp()) for share;
    if v_prior_session=v_current_session or exists (select 1 from jojo_progress_pilot.pilot_devices
        where auth_session_id=v_prior_session and (owner_id<>v_owner or id<>v_old_device)) then
        raise exception 'RETIRE_TV163_PREVIOUS_SESSION_UNAVAILABLE';
    end if;

    -- Une seule ancienne association ; aucune suppression de progression.
    update jojo_progress_pilot.pilot_devices set active=false,auth_session_id=v_prior_session
        where owner_id=v_owner and id=v_old_device and active and auth_session_id=v_current_session;
    get diagnostics v_rows=row_count;
    if v_rows<>1 then raise exception 'RETIRE_TV163_EXPECTED_ONE_ROW'; end if;
    if (select to_jsonb(d) from jojo_progress_pilot.pilot_devices d where owner_id=v_owner and id=v_old_device)
        is distinct from (to_jsonb(v_before)||jsonb_build_object('active',false,'auth_session_id',v_prior_session)) then
        raise exception 'RETIRE_TV163_UNEXPECTED_CHANGE';
    end if;
end;
$retire$;

select 'OLD_C_RETIRED_NO_NEW_ASSOCIATION_OR_ACTIVATION' as result,
    d.active as old_installation_active,
    not exists (select 1 from jojo_progress_pilot.pilot_devices other
        where other.auth_session_id=i.auth_session_id) as current_session_free_for_pairing
from jojo_progress_pilot.pilot_devices d
join jojo_account_settings.installations i on i.owner_id=d.owner_id
    and i.id='f6a3447e-58e3-4589-bf52-54456a3d97cc'
where d.owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c'
    and d.id='5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
commit;
