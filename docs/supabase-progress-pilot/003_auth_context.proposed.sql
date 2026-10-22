-- PROPOSITION 003 NON EXECUTEE. Accord explicite distinct requis avant application.
-- Projet jtdnwdbapkdkpgtceauh de TEST VIDE uniquement, apres 001 appliquee.
-- L'essai 002 a constate que postgres ne peut pas deleguer USAGE sur le schema auth.
-- Aucune permission, table ou fonction du schema auth n'est modifiee ici.
-- Un helper SQL prive SECURITY INVOKER utilise les fonctions Auth par references
-- resolues a sa creation (BEGIN ATOMIC). Il renvoie seulement deux UUID du JWT courant.
-- Aucun nouveau SECURITY DEFINER administratif ; les deux RPC gardent leur role limite.
begin;
do $$
begin
    if current_user <> 'postgres' or current_setting('server_version_num')::integer < 140000 then
        raise exception using errcode = '42501', message = 'PILOT_CORRECTION_ADMIN_VERSION_REQUIRED';
    end if;
    if exists (select 1 from auth.users) or exists (select 1 from auth.sessions)
        or exists (select 1 from jojo_progress_pilot.pilot_profile_bindings)
        or exists (select 1 from jojo_progress_pilot.pilot_devices)
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress) then
        raise exception using errcode = '42501', message = 'PILOT_EMPTY_TEST_PROJECT_REQUIRED';
    end if;
    if not exists (select 1 from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc'
            and not rolcanlogin and not rolinherit and not rolsuper and not rolbypassrls)
        or pg_has_role('authenticated', 'jojo_progress_pilot_rpc', 'MEMBER')
        or pg_has_role('anon', 'jojo_progress_pilot_rpc', 'MEMBER')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'auth.uid()', 'EXECUTE')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'auth.jwt()', 'EXECUTE') then
        raise exception using errcode = '42501', message = 'PILOT_RPC_AUTH_EXECUTE_REQUIRED';
    end if;
    if not exists (select 1 from pg_catalog.pg_proc p where p.oid = 'public.pilot_register_device(uuid,uuid,uuid,uuid)'::regprocedure
        and p.proowner = (select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')
        and p.prosecdef and p.proconfig @> array['search_path=""']::text[]
        and md5(replace(p.prosrc, E'\r\n', E'\n')) = '2481e312aa71ce788c6ce57aa1be8059') then
        raise exception using errcode = '42501', message = 'PILOT_RPC_DEFINITION_CHANGED';
    end if;
    if not exists (select 1 from pg_catalog.pg_proc p where p.oid = 'public.pilot_write_progress(jsonb)'::regprocedure
        and p.proowner = (select oid from pg_catalog.pg_roles where rolname = 'jojo_progress_pilot_rpc')
        and p.prosecdef and p.proconfig @> array['search_path=""']::text[]
        and md5(replace(p.prosrc, E'\r\n', E'\n')) = '3c87d61e946ca1719641515f9f1d2732') then
        raise exception using errcode = '42501', message = 'PILOT_RPC_DEFINITION_CHANGED';
    end if;
end;
$$;

-- Corps SQL analyse lors de la creation sous postgres, puis execute comme l'appelant.
-- Pas de lecture d'auth.sessions ici ; le helper booleen de 001 garde ce controle.
create function jojo_progress_pilot.pilot_request_identity()
returns table (owner_id uuid, session_id uuid)
language sql stable security invoker set search_path = ''
begin atomic
    select auth.uid(), (auth.jwt()->>'session_id')::uuid;
end;
revoke all on function jojo_progress_pilot.pilot_request_identity() from public, anon, authenticated;
grant execute on function jojo_progress_pilot.pilot_request_identity() to jojo_progress_pilot_rpc;

-- CREATE OR REPLACE conserve les proprietaires et ACL existants des deux RPC.
-- Seule l'obtention des UUID change ; controles, payload, UPSERT et ACK sont ceux de 001.
create or replace function public.pilot_register_device(
    p_profile_binding_id uuid, p_device_a uuid, p_device_b uuid, p_device_id uuid
) returns void language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_session uuid;
begin
    select identity.owner_id, identity.session_id into strict v_owner, v_session
        from jojo_progress_pilot.pilot_request_identity() as identity;
    -- Validation seulement : aucune creation ni reassociation d'appareil/session.
    if not coalesce(jojo_progress_pilot.pilot_session_matches(v_owner, v_session), false)
        or not exists (
            select 1 from jojo_progress_pilot.pilot_profile_bindings b
            join jojo_progress_pilot.pilot_devices d on d.owner_id = b.owner_id
            where b.owner_id = v_owner and b.id = p_profile_binding_id
                and b.device_a = p_device_a and b.device_b = p_device_b
                and d.id = p_device_id and d.active and d.auth_session_id = v_session
                and d.id = case when d.slot = 1 then b.device_a else b.device_b end
        ) then
        raise exception using errcode = '42501', message = 'PILOT_ASSOCIATION_DENIED';
    end if;
end;
$$;

create or replace function public.pilot_write_progress(p_ops jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid;
    v_session uuid;
    v_entry jsonb;
    v_payload jsonb;
    v_row jojo_progress_pilot.pilot_watch_progress%rowtype;
    v_existing jojo_progress_pilot.pilot_watch_progress%rowtype;
    v_count bigint;
    v_status text;
    v_ack jsonb := '[]'::jsonb;
begin
    select identity.owner_id, identity.session_id into strict v_owner, v_session
        from jojo_progress_pilot.pilot_request_identity() as identity;
    if not coalesce(jojo_progress_pilot.pilot_session_matches(v_owner, v_session), false) then
        raise exception using errcode = '42501', message = 'PILOT_SESSION_REQUIRED';
    end if;
    if jsonb_typeof(p_ops) is distinct from 'array' then
        raise exception using errcode = '22023', message = 'PILOT_BATCH_INVALID';
    end if;
    if jsonb_array_length(p_ops) not between 1 and 50 then
        raise exception using errcode = '22023', message = 'PILOT_BATCH_INVALID';
    end if;
    for v_entry in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(v_entry) is distinct from 'object' or
            not (v_entry ?& array['operation_id', 'revision', 'payload']) or
            exists (select 1 from jsonb_object_keys(v_entry) as fields(key)
                where fields.key not in ('operation_id', 'revision', 'payload')) then
            raise exception using errcode = '22023', message = 'PILOT_OPERATION_INVALID';
        end if;
        v_payload := v_entry->'payload';
        if jsonb_typeof(v_payload) is distinct from 'object' or
            not (v_payload ?& array['owner_id', 'profile_binding_id', 'device_id', 'content_key',
                'media_kind', 'position_ms', 'duration_ms', 'source_time_ms']) or
            exists (select 1 from jsonb_object_keys(v_payload) as fields(key) where fields.key not in (
                'owner_id', 'profile_binding_id', 'device_id', 'content_key', 'media_kind',
                'position_ms', 'duration_ms', 'progress_percent', 'source_time_ms')) then
            raise exception using errcode = '22023', message = 'PILOT_PAYLOAD_INVALID';
        end if;
        if jsonb_typeof(v_entry->'operation_id') is distinct from 'string'
            or jsonb_typeof(v_entry->'revision') is distinct from 'number'
            or (v_entry->>'revision') !~ '^[0-9]+$'
            or exists (select 1 from unnest(array['owner_id', 'profile_binding_id', 'device_id', 'content_key', 'media_kind']) k
                where jsonb_typeof(v_payload->k) is distinct from 'string')
            or exists (select 1 from unnest(array['position_ms', 'duration_ms', 'source_time_ms']) k
                where jsonb_typeof(v_payload->k) is distinct from 'number' or (v_payload->>k) !~ '^[0-9]+$')
            or (v_payload ? 'progress_percent' and jsonb_typeof(v_payload->'progress_percent') not in ('number', 'null')) then
            raise exception using errcode = '22023', message = 'PILOT_VALUE_INVALID';
        end if;
        v_row := jsonb_populate_record(null::jojo_progress_pilot.pilot_watch_progress, v_payload ||
            jsonb_build_object('revision', v_entry->'revision', 'operation_id', v_entry->'operation_id'));
        if v_row.owner_id is distinct from v_owner or not exists (
            select 1 from jojo_progress_pilot.pilot_devices d where d.owner_id = v_owner and d.id = v_row.device_id
                and d.active and d.auth_session_id = v_session
        ) then
            raise exception using errcode = '42501', message = 'PILOT_DEVICE_DENIED';
        end if;
        insert into jojo_progress_pilot.pilot_watch_progress as stored (
            owner_id, profile_binding_id, device_id, content_key, media_kind, position_ms,
            duration_ms, progress_percent, source_time_ms, revision, operation_id
        ) values (
            v_row.owner_id, v_row.profile_binding_id, v_row.device_id, v_row.content_key,
            v_row.media_kind, v_row.position_ms, v_row.duration_ms, v_row.progress_percent,
            v_row.source_time_ms, v_row.revision, v_row.operation_id
        ) on conflict (owner_id, profile_binding_id, device_id, content_key) do update set
            media_kind = excluded.media_kind, position_ms = excluded.position_ms,
            duration_ms = excluded.duration_ms, progress_percent = excluded.progress_percent,
            source_time_ms = excluded.source_time_ms, revision = excluded.revision,
            operation_id = excluded.operation_id
        where stored.revision < excluded.revision;
        get diagnostics v_count = row_count;
        if v_count = 1 then v_status := 'applied';
        else
            select * into strict v_existing from jojo_progress_pilot.pilot_watch_progress
                where owner_id = v_row.owner_id and profile_binding_id = v_row.profile_binding_id
                    and device_id = v_row.device_id and content_key = v_row.content_key;
            if v_existing.revision > v_row.revision then v_status := 'stale';
            elsif row(v_existing.revision, v_existing.operation_id, v_existing.media_kind, v_existing.position_ms,
                    v_existing.duration_ms, v_existing.progress_percent, v_existing.source_time_ms)
                is not distinct from row(v_row.revision, v_row.operation_id, v_row.media_kind, v_row.position_ms,
                    v_row.duration_ms, v_row.progress_percent, v_row.source_time_ms)
                then v_status := 'duplicate';
            else v_status := 'conflict';
            end if;
        end if;
        -- Retour technique exclusivement : aucune position, duree ni ligne de progression.
        v_ack := v_ack || jsonb_build_array(jsonb_build_object(
            'operation_id', v_row.operation_id, 'revision', v_row.revision, 'status', v_status));
    end loop;
    return v_ack;
end;
$$;

-- Verification de la forme du helper, sans l'appeler ni creer de donnees.
do $$
begin
    if not exists (select 1 from pg_catalog.pg_proc p
        where p.oid = 'jojo_progress_pilot.pilot_request_identity()'::regprocedure
            and p.proowner = (select oid from pg_catalog.pg_roles where rolname = 'postgres')
            and not p.prosecdef and p.prosqlbody is not null
            and p.proconfig @> array['search_path=""']::text[])
        or has_function_privilege('anon', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or has_function_privilege('authenticated', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE') then
        raise exception using errcode = '42501', message = 'PILOT_IDENTITY_HELPER_INVALID';
    end if;
end;
$$;
commit;
