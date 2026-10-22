-- PROPOSITION REVISEE POUR REVUE UNIQUEMENT. NE PAS EXECUTER SANS ACCORD SEPARE.
-- Projet Supabase de TEST VIDE exclusivement. Aucun projet/schema n'a ete modifie.
-- Prerequis a verifier apres autorisation : droits administratifs de creation de role,
-- auth.sessions(id, user_id, not_after), auth.uid(), auth.jwt(), roles anon/authenticated.
-- Role RPC sans LOGIN/BYPASSRLS et distinct du proprietaire des tables.
begin;
-- Le helper Auth ci-dessous doit appartenir au role administratif constate lors du SELECT.
do $$
begin
    if current_user <> 'postgres' or not exists (
        select 1 from pg_catalog.pg_roles where rolname = 'postgres' and rolbypassrls and rolcreaterole
    ) then
        raise exception using errcode = '42501', message = 'PILOT_MIGRATION_ADMIN_REQUIRED';
    end if;
    if exists (select 1 from auth.users) or exists (select 1 from auth.sessions) then
        raise exception using errcode = '42501', message = 'PILOT_EMPTY_TEST_PROJECT_REQUIRED';
    end if;
end;
$$;
create role jojo_progress_pilot_rpc nologin noinherit nobypassrls;
grant jojo_progress_pilot_rpc to postgres;
create schema jojo_progress_pilot;
revoke all on schema jojo_progress_pilot from public, anon, authenticated;
grant usage on schema jojo_progress_pilot, auth to jojo_progress_pilot_rpc;
grant usage, create on schema public to jojo_progress_pilot_rpc; -- CREATE seulement pour ALTER FUNCTION OWNER.
grant execute on function auth.uid(), auth.jwt() to jojo_progress_pilot_rpc;

create table jojo_progress_pilot.pilot_profile_bindings (
    owner_id uuid primary key references auth.users(id) on delete cascade,
    singleton boolean not null default true unique check (singleton), -- Un profil dans le projet entier.
    id uuid not null,
    device_a uuid not null,
    device_b uuid not null,
    unique (owner_id, id),
    check (device_a <> device_b)
);
create table jojo_progress_pilot.pilot_devices (
    owner_id uuid not null references jojo_progress_pilot.pilot_profile_bindings(owner_id) on delete cascade,
    id uuid not null,
    slot smallint not null check (slot in (1, 2)),
    auth_session_id uuid not null,
    active boolean not null default true,
    primary key (owner_id, id),
    unique (owner_id, slot),
    unique (owner_id, auth_session_id)
);
create table jojo_progress_pilot.pilot_watch_progress (
    owner_id uuid not null,
    profile_binding_id uuid not null,
    device_id uuid not null,
    content_key text not null check (content_key ~ '^[0-9a-f]{64}$'),
    media_kind text not null check (media_kind in ('film', 'episode', 'file')),
    position_ms bigint not null check (position_ms >= 0),
    duration_ms bigint not null check (duration_ms >= 0),
    progress_percent real check (progress_percent between 0 and 100),
    source_time_ms bigint not null check (source_time_ms > 0),
    revision bigint not null check (revision > 0),
    operation_id uuid not null,
    received_at timestamptz not null default now(),
    primary key (owner_id, profile_binding_id, device_id, content_key),
    foreign key (owner_id, profile_binding_id) references jojo_progress_pilot.pilot_profile_bindings(owner_id, id) on delete cascade,
    foreign key (owner_id, device_id) references jojo_progress_pilot.pilot_devices(owner_id, id) on delete cascade
);
-- Aucune ligne de profil/appareil creee par cette proposition : preassociation administrative
-- distincte requise avant activation. Aucun INSERT/UPDATE/DELETE de ces tables via RPC/client.

-- auth.sessions a ses propres RLS. Aucun droit direct sur cette table pour le role RPC,
-- aucune modification de ses politiques. Ce helper prive lit uniquement l'existence
-- de la session du JWT, sous l'autorite postgres, et ne renvoie qu'un booleen.
create function jojo_progress_pilot.pilot_session_matches(p_owner uuid, p_session uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select coalesce(p_owner = auth.uid() and p_session = (auth.jwt()->>'session_id')::uuid
        and coalesce(auth.jwt()->>'is_anonymous', 'true') = 'false'
        and exists (select 1 from auth.sessions s where s.id = p_session and s.user_id = p_owner
            and (s.not_after is null or s.not_after > now())), false);
$$;
alter function jojo_progress_pilot.pilot_session_matches(uuid, uuid) owner to postgres;
revoke all on function jojo_progress_pilot.pilot_session_matches(uuid, uuid) from public, anon, authenticated;
grant execute on function jojo_progress_pilot.pilot_session_matches(uuid, uuid) to jojo_progress_pilot_rpc;

alter table jojo_progress_pilot.pilot_profile_bindings enable row level security;
alter table jojo_progress_pilot.pilot_devices enable row level security;
alter table jojo_progress_pilot.pilot_watch_progress enable row level security;
alter table jojo_progress_pilot.pilot_profile_bindings force row level security;
alter table jojo_progress_pilot.pilot_devices force row level security;
alter table jojo_progress_pilot.pilot_watch_progress force row level security;
revoke all on all tables in schema jojo_progress_pilot from public, anon, authenticated;
grant select on jojo_progress_pilot.pilot_profile_bindings, jojo_progress_pilot.pilot_devices to jojo_progress_pilot_rpc;
grant select, insert on jojo_progress_pilot.pilot_watch_progress to jojo_progress_pilot_rpc;
grant update (media_kind, position_ms, duration_ms, progress_percent, source_time_ms, revision, operation_id)
    on jojo_progress_pilot.pilot_watch_progress to jojo_progress_pilot_rpc;

-- SELECT est interne a la RPC/UPSERT ; authenticated/anon n'ont ni USAGE ni droits sur les tables.
create policy pilot_profile_select on jojo_progress_pilot.pilot_profile_bindings for select to jojo_progress_pilot_rpc
    using (jojo_progress_pilot.pilot_session_matches(owner_id, (auth.jwt()->>'session_id')::uuid));
create policy pilot_device_select on jojo_progress_pilot.pilot_devices for select to jojo_progress_pilot_rpc
    using (active and jojo_progress_pilot.pilot_session_matches(owner_id, auth_session_id)
        and exists (select 1 from jojo_progress_pilot.pilot_profile_bindings b
            where b.owner_id = pilot_devices.owner_id and pilot_devices.id =
                case when pilot_devices.slot = 1 then b.device_a else b.device_b end));
create policy pilot_progress_select on jojo_progress_pilot.pilot_watch_progress for select to jojo_progress_pilot_rpc
    using (owner_id = auth.uid() and exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id = pilot_watch_progress.owner_id and d.id = pilot_watch_progress.device_id));
create policy pilot_progress_insert on jojo_progress_pilot.pilot_watch_progress for insert to jojo_progress_pilot_rpc
    with check (owner_id = auth.uid() and exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id = pilot_watch_progress.owner_id and d.id = pilot_watch_progress.device_id));
create policy pilot_progress_update on jojo_progress_pilot.pilot_watch_progress for update to jojo_progress_pilot_rpc
    using (owner_id = auth.uid() and exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id = pilot_watch_progress.owner_id and d.id = pilot_watch_progress.device_id))
    with check (owner_id = auth.uid() and exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id = pilot_watch_progress.owner_id and d.id = pilot_watch_progress.device_id));
-- Aucun droit/politique DELETE, ni INSERT/UPDATE des associations pour le role RPC.

create function jojo_progress_pilot.pilot_guard_progress_revision() returns trigger
language plpgsql security invoker set search_path = '' as $$
begin
    if tg_op = 'UPDATE' then
        if row(new.owner_id, new.profile_binding_id, new.device_id, new.content_key)
            is distinct from row(old.owner_id, old.profile_binding_id, old.device_id, old.content_key) then
            raise exception using errcode = '42501', message = 'PILOT_IDENTITY_IMMUTABLE';
        end if;
        if new.revision < old.revision then return null; end if;
        if new.revision = old.revision then
            if row(new.operation_id, new.media_kind, new.position_ms, new.duration_ms, new.progress_percent, new.source_time_ms)
                is not distinct from row(old.operation_id, old.media_kind, old.position_ms, old.duration_ms, old.progress_percent, old.source_time_ms)
                then return null;
            end if;
            raise exception using errcode = '23514', message = 'PILOT_REVISION_CONFLICT';
        end if;
    end if;
    new.received_at := clock_timestamp();
    return new;
end;
$$;
revoke all on function jojo_progress_pilot.pilot_guard_progress_revision() from public, anon, authenticated;
grant execute on function jojo_progress_pilot.pilot_guard_progress_revision() to jojo_progress_pilot_rpc;
create trigger pilot_progress_revision_guard before insert or update on jojo_progress_pilot.pilot_watch_progress
    for each row execute function jojo_progress_pilot.pilot_guard_progress_revision();

create function public.pilot_register_device(
    p_profile_binding_id uuid, p_device_a uuid, p_device_b uuid, p_device_id uuid
) returns void language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := auth.uid();
    v_session uuid := (auth.jwt()->>'session_id')::uuid;
begin
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
alter function public.pilot_register_device(uuid, uuid, uuid, uuid) owner to jojo_progress_pilot_rpc;
revoke all on function public.pilot_register_device(uuid, uuid, uuid, uuid) from public, anon, authenticated;
grant execute on function public.pilot_register_device(uuid, uuid, uuid, uuid) to authenticated;

create function public.pilot_write_progress(p_ops jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_owner uuid := auth.uid();
    v_session uuid := (auth.jwt()->>'session_id')::uuid;
    v_entry jsonb;
    v_payload jsonb;
    v_row jojo_progress_pilot.pilot_watch_progress%rowtype;
    v_existing jojo_progress_pilot.pilot_watch_progress%rowtype;
    v_count bigint;
    v_status text;
    v_ack jsonb := '[]'::jsonb;
begin
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
alter function public.pilot_write_progress(jsonb) owner to jojo_progress_pilot_rpc;
revoke all on function public.pilot_write_progress(jsonb) from public, anon, authenticated;
grant execute on function public.pilot_write_progress(jsonb) to authenticated;

revoke create on schema public from jojo_progress_pilot_rpc;
commit;
