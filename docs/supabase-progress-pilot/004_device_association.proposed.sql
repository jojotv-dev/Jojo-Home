-- PROPOSITION E-024 : aucune execution autorisee a ce stade.
-- Uniquement le projet de test jojo-progress-pilot-test, ref jtdnwdbapkdkpgtceauh.
-- Relire les deux sessions a l'ecran si une nouvelle connexion a eu lieu.
-- L'administrateur doit verifier le projet cible et, APRES accord distinct,
-- definir jojo.progress_pilot_association_project_ref = 'jtdnwdbapkdkpgtceauh'
-- et jojo.progress_pilot_association_authorized = 'APPROVED_E024_PROFILE_TWO_DEVICES'.
-- Ce fichier ne definit jamais lui-meme ces deux confirmations.
--
-- Effet : un profil opaque et deux installations/sessions preassociees.
-- active=true autorise les RPC du pilote pour ces seules sessions ; cela
-- n'active pas le miroir dans l'application, qui reste en NAS_ONLY.
-- Aucun schema, droit, RPC, compte Auth, session Auth ou checkpoint modifie.
-- Aucun nom, numero de profil local, IP, titre, chemin, URL ou secret transmis.
begin;
set local lock_timeout = '5s';
set local statement_timeout = '30s';

do $association$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_binding constant uuid := '2e43435a-eda7-44d0-8d95-476a2a973446';
    v_device_a constant uuid := 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba';
    v_device_b constant uuid := '12bb7efd-7782-4903-8fd1-052ea5db924d';
    v_session_a constant uuid := '5e75b1f7-172d-44f1-8d1b-fb3f0402b34c';
    v_session_b constant uuid := '6acfd337-c01f-4012-96a1-005a6bf29f28';
begin
    if current_setting('jojo.progress_pilot_association_project_ref', true)
            is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.progress_pilot_association_authorized', true)
            is distinct from 'APPROVED_E024_PROFILE_TWO_DEVICES' then
        raise exception using errcode = '42501', message = 'PILOT_ASSOCIATION_APPROVAL_REQUIRED';
    end if;
    if not exists (select 1 from pg_catalog.pg_roles
            where rolname = current_user and (rolsuper or rolbypassrls)) then
        raise exception using errcode = '42501', message = 'PILOT_ASSOCIATION_ADMIN_REQUIRED';
    end if;
    if v_device_a = v_device_b or v_session_a = v_session_b then
        raise exception 'PILOT_DISTINCT_DEVICES_AND_SESSIONS_REQUIRED';
    end if;
    if to_regprocedure('jojo_progress_pilot.pilot_request_identity()') is null then
        raise exception 'PILOT_APPROVED_MIGRATIONS_REQUIRED';
    end if;
    if (select count(*) from pg_catalog.pg_class c
            join pg_catalog.pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'jojo_progress_pilot'
                and c.relname in ('pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress')
                and c.relkind = 'r' and c.relrowsecurity and c.relforcerowsecurity) <> 3 then
        raise exception 'PILOT_FORCED_RLS_REQUIRED';
    end if;

    -- Bloquer une ecriture concurrente entre le controle et les INSERT.
    lock table jojo_progress_pilot.pilot_profile_bindings,
        jojo_progress_pilot.pilot_devices,
        jojo_progress_pilot.pilot_watch_progress in share row exclusive mode;
    if exists (select 1 from jojo_progress_pilot.pilot_profile_bindings)
        or exists (select 1 from jojo_progress_pilot.pilot_devices)
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress) then
        raise exception 'PILOT_TABLES_MUST_BE_EMPTY_NO_OVERWRITE';
    end if;

    -- Lire seulement identite/non-anonymat ; aucun e-mail ni secret.
    perform 1 from auth.users
        where id = v_owner and is_anonymous is false for share;
    if not found then
        raise exception 'PILOT_EXPECTED_NON_ANONYMOUS_OWNER_REQUIRED';
    end if;
    -- Les verrous de lecture evitent une suppression/expiration concurrente
    -- de ces lignes pendant la courte transaction ; aucune session n'est modifiee.
    perform 1 from auth.sessions where id = v_session_a and user_id = v_owner
        and (not_after is null or not_after > clock_timestamp()) for share;
    if not found then
        raise exception 'PILOT_EXPECTED_SESSION_A_REQUIRED';
    end if;
    perform 1 from auth.sessions where id = v_session_b and user_id = v_owner
        and (not_after is null or not_after > clock_timestamp()) for share;
    if not found then
        raise exception 'PILOT_EXPECTED_SESSION_B_REQUIRED';
    end if;

    insert into jojo_progress_pilot.pilot_profile_bindings
        (owner_id, singleton, id, device_a, device_b)
    values (v_owner, true, v_binding, v_device_a, v_device_b);
    insert into jojo_progress_pilot.pilot_devices
        (owner_id, id, slot, auth_session_id, active)
    values (v_owner, v_device_a, 1, v_session_a, true),
        (v_owner, v_device_b, 2, v_session_b, true);

    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings) <> 1
        or (select count(*) from jojo_progress_pilot.pilot_devices) <> 2
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress) then
        raise exception 'PILOT_ASSOCIATION_POSTCONDITION_FAILED';
    end if;
end;
$association$;

-- Resultat administratif limite aux UUID techniques et au nombre de lignes.
select b.id as profile_binding_id, d.id as device_id, d.slot,
    d.auth_session_id, d.active,
    (select count(*) from jojo_progress_pilot.pilot_watch_progress) as progress_rows
from jojo_progress_pilot.pilot_profile_bindings b
join jojo_progress_pilot.pilot_devices d on d.owner_id = b.owner_id
order by d.slot;
commit;
