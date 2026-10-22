-- PROPOSITION NON EXECUTEE : accord distinct requis avant application.
-- Projet : jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh.
-- Remplace uniquement la session autorisee de C (.163), apres sa reconnexion.
-- Aucun changement de schema, progression, cle, profil, compte Auth ou mode Android.
-- Apres accord, ajouter DANS cette transaction avant DO :
-- SET LOCAL jojo.progress_session_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.progress_session_rebind_approved = 'APPROVED_SESSION_REBIND_023';

begin isolation level repeatable read;
set local lock_timeout = '3s';
set local statement_timeout = '30s';

do $rebind$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_binding constant uuid := '2e43435a-eda7-44d0-8d95-476a2a973446';
    v_a constant uuid := 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba';
    v_b constant uuid := '12bb7efd-7782-4903-8fd1-052ea5db924d';
    v_c constant uuid := '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
    v_installation constant uuid := 'f6a3447e-58e3-4589-bf52-54456a3d97cc';
    v_old_hash constant text := '2ede3d99fe8c35b55649b01f77351b31e005dcfcbfa49285e69351122ff0b3f5';
    v_new_hash constant text := '242890bd8c5aebdca6930bdbc843c53593e3cab6bdf6929294b62f800f02613b';
    v_device jojo_progress_pilot.pilot_devices%rowtype;
    v_install jojo_account_settings.installations%rowtype;
    v_rows bigint;
begin
    if current_user <> 'postgres'
        or current_setting('jojo.progress_session_project_ref', true)
            is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.progress_session_rebind_approved', true)
            is distinct from 'APPROVED_SESSION_REBIND_023'
        or not exists (select 1 from pg_catalog.pg_roles
            where rolname = current_user and rolbypassrls) then
        raise exception using errcode = '42501', message = 'SESSION_REBIND_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if exists (select 1 from pg_catalog.pg_trigger
        where tgrelid = 'jojo_progress_pilot.pilot_devices'::regclass and not tgisinternal) then
        raise exception 'SESSION_REBIND_UNREVIEWED_TRIGGER';
    end if;

    -- Verrouillage de C et de sa nouvelle installation, sans verrou de table global.
    select * into strict v_device from jojo_progress_pilot.pilot_devices
        where owner_id = v_owner and id = v_c for update;
    select * into strict v_install from jojo_account_settings.installations
        where owner_id = v_owner and id = v_installation for share;
    perform 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id = v_owner and id = v_binding
            and device_a = v_a and device_b = v_b and device_c = v_c for share;
    if not found then raise exception 'SESSION_REBIND_PROFILE_BINDING_CHANGED'; end if;

    if v_device.slot <> 3 or not v_device.active or not v_install.active
        or v_install.platform <> 'firetv'
        or v_device.auth_session_id = v_install.auth_session_id
        or encode(sha256(convert_to(v_device.auth_session_id::text, 'UTF8')), 'hex') <> v_old_hash
        or encode(sha256(convert_to(v_install.auth_session_id::text, 'UTF8')), 'hex') <> v_new_hash then
        raise exception 'SESSION_REBIND_IDENTITY_OR_SESSION_CHANGED';
    end if;
    perform 1 from auth.users where id = v_owner and is_anonymous is false for share;
    if not found then raise exception 'SESSION_REBIND_OWNER_INVALID'; end if;
    perform 1 from auth.sessions where id = v_install.auth_session_id and user_id = v_owner
        and (not_after is null or not_after > clock_timestamp()) for share;
    if not found then raise exception 'SESSION_REBIND_CURRENT_SESSION_INVALID'; end if;
    if exists (select 1 from jojo_progress_pilot.pilot_devices
        where auth_session_id = v_install.auth_session_id and (owner_id <> v_owner or id <> v_c)) then
        raise exception 'SESSION_REBIND_SESSION_ALREADY_ASSIGNED';
    end if;

    -- Une colonne d'une seule ligne ; l'ancienne session perd cette association.
    update jojo_progress_pilot.pilot_devices
        set auth_session_id = v_install.auth_session_id
        where owner_id = v_owner and id = v_c and auth_session_id = v_device.auth_session_id;
    get diagnostics v_rows = row_count;
    if v_rows <> 1 then raise exception 'SESSION_REBIND_EXPECTED_ONE_ROW'; end if;
    if (select to_jsonb(d) from jojo_progress_pilot.pilot_devices d
            where owner_id = v_owner and id = v_c)
        is distinct from (to_jsonb(v_device) || jsonb_build_object('auth_session_id', v_install.auth_session_id)) then
        raise exception 'SESSION_REBIND_UNEXPECTED_ROW_CHANGE';
    end if;
end;
$rebind$;

select 'SESSION_REBOUND_NO_ACTIVATION' as result,
    d.slot, d.auth_session_id = i.auth_session_id as same_session
from jojo_progress_pilot.pilot_devices d
join jojo_account_settings.installations i on i.owner_id = d.owner_id
    and i.id = 'f6a3447e-58e3-4589-bf52-54456a3d97cc'
where d.owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'
    and d.id = '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
commit;
