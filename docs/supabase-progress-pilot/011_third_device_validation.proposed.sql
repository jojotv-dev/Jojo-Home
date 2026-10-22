-- PROPOSITION NON EXECUTEE. Revue et accord sur CE script avant execution.
-- Uniquement jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh, apres 009/010.
-- Aucun appareil commande, aucun miroir active, aucun changement de schema permanent.
-- Simule les roles/JWT dans PostgreSQL : ne teste pas Auth HTTP, TLS ou les TV.
-- Ecrit temporairement : un utilisateur Auth fictif sans mot de passe, trois sessions
-- fictives, trois observations factices A/B/C et active=false sur C uniquement.
-- Aucun compte/session reel n'est modifie. Aucun trigger externe Auth n'est accepte.
-- Les fixtures sont ANNULEES dans une sous-transaction, puis comparees au bilan
-- initial. Un second ROLLBACK termine la transaction contenant seulement les helpers.
-- Les refus attendus sont des assertions ; tout autre resultat donne FAILED.
-- Ne pas reutiliser 002 : il exige Auth et tables pilote vides.
-- APRES accord, verifier le projet dans le tableau de bord et fournir, dans la
-- MEME transaction, avant le premier DO, ces deux confirmations SET LOCAL :
-- jojo.progress_pilot_c_validation_project_ref = 'jtdnwdbapkdkpgtceauh'
-- jojo.progress_pilot_c_validation_authorized = 'APPROVED_C_RLS_ROLLBACK_ONLY'
-- Cette proposition ne definit pas elle-meme ces confirmations.

begin;
set local statement_timeout = '60s';
set local lock_timeout = '3s';
set local idle_in_transaction_session_timeout = '30s';
set local row_security = on;

do $preflight$
declare
    role_name text;
    table_name text;
    signature text;
begin
    if current_setting('jojo.progress_pilot_c_validation_project_ref', true)
            is distinct from 'jtdnwdbapkdkpgtceauh'
        or current_setting('jojo.progress_pilot_c_validation_authorized', true)
            is distinct from 'APPROVED_C_RLS_ROLLBACK_ONLY' then
        raise exception 'PILOT_C_VALIDATION_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if current_user <> 'postgres' or not exists (
        select 1 from pg_roles where rolname = current_user and rolbypassrls) then
        raise exception 'PILOT_C_VALIDATION_ADMIN_REQUIRED';
    end if;
    if (select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'jojo_progress_pilot' and c.relname in
            ('pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress')
            and c.relkind = 'r' and c.relrowsecurity and c.relforcerowsecurity
            and c.relowner <> (select oid from pg_roles where rolname = 'jojo_progress_pilot_rpc')) <> 3
        or exists (select 1 from pg_roles where rolname = 'jojo_progress_pilot_rpc'
            and (rolsuper or rolbypassrls or rolcanlogin))
        or pg_has_role('authenticated', 'jojo_progress_pilot_rpc', 'MEMBER')
        or pg_has_role('anon', 'jojo_progress_pilot_rpc', 'MEMBER')
        or has_schema_privilege('jojo_progress_pilot_rpc', 'public', 'CREATE')
        or has_schema_privilege('jojo_progress_pilot_rpc', 'auth', 'USAGE') then
        raise exception 'PILOT_C_VALIDATION_ROLE_OR_RLS_CHANGED';
    end if;
    -- Un refus metier 42501 ne doit pas masquer un EXECUTE accorde a anon,
    -- ni un refus SELECT * masquer un GRANT partiel sur certaines colonnes.
    foreach signature in array array[
        'public.pilot_register_device(uuid,uuid,uuid,uuid)',
        'public.pilot_write_progress(jsonb)',
        'public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)'] loop
        if has_function_privilege('anon', signature, 'EXECUTE')
            or not has_function_privilege('authenticated', signature, 'EXECUTE') then
            raise exception 'PILOT_C_VALIDATION_RPC_EXECUTE_CHANGED';
        end if;
    end loop;
    foreach role_name in array array['anon', 'authenticated'] loop
        if has_schema_privilege(role_name, 'jojo_progress_pilot', 'USAGE') then
            raise exception 'PILOT_C_VALIDATION_PRIVATE_SCHEMA_EXPOSED';
        end if;
        foreach table_name in array array['pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress'] loop
            if has_any_column_privilege(role_name, 'jojo_progress_pilot.' || table_name, 'SELECT,INSERT,UPDATE,REFERENCES')
                or has_table_privilege(role_name, 'jojo_progress_pilot.' || table_name, 'DELETE,TRUNCATE,TRIGGER') then
                raise exception 'PILOT_C_VALIDATION_DIRECT_TABLE_GRANT';
            end if;
        end loop;
        foreach signature in array array[
            'jojo_progress_pilot.pilot_session_matches(uuid,uuid)',
            'jojo_progress_pilot.pilot_request_identity()',
            'jojo_progress_pilot.pilot_guard_progress_revision()'] loop
            if has_function_privilege(role_name, signature, 'EXECUTE') then
                raise exception 'PILOT_C_VALIDATION_PRIVATE_HELPER_EXPOSED';
            end if;
        end loop;
    end loop;
    if (select count(*) from pg_proc p join (values
        ('public.pilot_register_device(uuid,uuid,uuid,uuid)'::regprocedure,
            '4ded89248ef210ef07ff38ba80471e97'),
        ('public.pilot_write_progress(jsonb)'::regprocedure,
            '2f51c07139dec9330c4ce09d3f290b0e'),
        ('public.pilot_register_third_device(uuid,uuid,uuid,uuid,uuid)'::regprocedure,
            '6ea43961e6e00becbea35a016c69e3a5')) expected(oid, body)
        on p.oid = expected.oid where md5(replace(p.prosrc, E'\r\n', E'\n')) = expected.body
            and p.prosecdef and p.proconfig @> array['search_path=""']::text[]
            and p.proowner = (select oid from pg_roles where rolname = 'jojo_progress_pilot_rpc')) <> 3 then
        raise exception 'PILOT_C_VALIDATION_RPC_CHANGED';
    end if;
    if not exists (select 1 from pg_proc p
        where p.oid = 'jojo_progress_pilot.pilot_guard_progress_revision()'::regprocedure
            and md5(replace(p.prosrc, E'\r\n', E'\n')) = 'd1f2ccc24d3984cf60528b16f92e72bb'
            and not p.prosecdef and p.proconfig @> array['search_path=""']::text[]) then
        raise exception 'PILOT_C_VALIDATION_REVISION_TRIGGER_CHANGED';
    end if;
    -- Une annulation SQL n'annule pas un appel HTTP produit par un trigger.
    if exists (select 1 from pg_trigger where not tgisinternal and tgenabled <> 'D'
        and tgrelid in ('auth.users'::regclass, 'auth.sessions'::regclass,
            'jojo_progress_pilot.pilot_profile_bindings'::regclass,
            'jojo_progress_pilot.pilot_devices'::regclass,
            'jojo_progress_pilot.pilot_watch_progress'::regclass)
        and not (tgrelid = 'jojo_progress_pilot.pilot_watch_progress'::regclass
            and tgname = 'pilot_progress_revision_guard'
            and tgfoid = 'jojo_progress_pilot.pilot_guard_progress_revision()'::regprocedure)) then
        raise exception 'PILOT_C_VALIDATION_UNREVIEWED_TRIGGER';
    end if;
end;
$preflight$;

-- Verrouille seulement les tables du pilote, pas Auth ni les donnees NAS.
-- Refuse apres 3 s si une ecriture concurrente empeche un bilan stable.
lock table jojo_progress_pilot.pilot_profile_bindings,
    jojo_progress_pilot.pilot_devices, jojo_progress_pilot.pilot_watch_progress
    in share row exclusive mode;

create temporary table pilot_c_results (
    case_id text primary key
) on commit drop;
grant select, insert on pg_temp.pilot_c_results to authenticated, anon, jojo_progress_pilot_rpc;
create temporary table pilot_c_summary (
    status text, assertions_passed integer, assertions_expected integer, case_ids jsonb,
    fixtures_rolled_back boolean, permanent_state_preserved boolean,
    failure_sqlstate text, failure_message text
) on commit drop;

create function pg_temp.pilot_c_context() returns jsonb
language sql stable security invoker set search_path = '' as $$
    select current_setting('jojo.progress_pilot_c_validation_context')::jsonb;
$$;
create function pg_temp.pilot_c_assert(label text, condition boolean) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    if condition is distinct from true then
        raise exception 'PILOT_C_ASSERTION_FAILED: %', label;
    end if;
    insert into pg_temp.pilot_c_results values (label);
end;
$$;
create function pg_temp.pilot_c_ok(label text, statement text) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    execute statement;
    perform pg_temp.pilot_c_assert(label, true);
end;
$$;
create function pg_temp.pilot_c_denied(label text, statement text, expected_state text,
    expected_message text default null) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    begin
        execute statement;
        raise exception using errcode = 'ZP001', message = 'PILOT_C_EXPECTED_DENIAL_NOT_RAISED';
    exception when others then
        if sqlstate is distinct from expected_state
            or (expected_message is not null and sqlerrm is distinct from expected_message) then
            raise exception 'PILOT_C_DENIAL_FAILED: %, got %: %', label, sqlstate, sqlerrm;
        end if;
    end;
    perform pg_temp.pilot_c_assert(label, true);
end;
$$;
create function pg_temp.pilot_c_rows(label text, statement text, expected_rows bigint) returns void
language plpgsql security invoker set search_path = '' as $$
declare affected bigint;
begin
    execute statement;
    get diagnostics affected = row_count;
    perform pg_temp.pilot_c_assert(label, affected = expected_rows);
end;
$$;
create function pg_temp.pilot_c_claims(device text) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    perform set_config('request.jwt.claims', jsonb_build_object(
        'sub', pg_temp.pilot_c_context()->>'owner', 'role', 'authenticated',
        'is_anonymous', false, 'session_id', pg_temp.pilot_c_context()->>('session_' || device))::text, true);
end;
$$;
create function pg_temp.pilot_c_op(device text, rev bigint default 1, pos bigint default 10000) returns jsonb
language sql security invoker set search_path = '' as $$
    select jsonb_build_array(jsonb_build_object(
        'operation_id', md5('pilot-c-validation:' || device || ':' || rev)::uuid,
        'revision', rev, 'payload', jsonb_build_object(
            'owner_id', pg_temp.pilot_c_context()->>'owner',
            'profile_binding_id', pg_temp.pilot_c_context()->>'profile',
            'device_id', pg_temp.pilot_c_context()->>device,
            'content_key', pg_temp.pilot_c_context()->>'fixture_key',
            'media_kind', 'file', 'position_ms', pos, 'duration_ms', 100000,
            'progress_percent', pos::real / 1000, 'source_time_ms', 1000 + rev)));
$$;
create function pg_temp.pilot_c_ack(label text, op jsonb, expected_status text) returns void
language plpgsql security invoker set search_path = '' as $$
declare ack jsonb := public.pilot_write_progress(op);
begin
    perform pg_temp.pilot_c_assert(label, ack is not distinct from jsonb_build_array(
        jsonb_build_object('operation_id', op->0->'operation_id',
            'revision', op->0->'revision', 'status', expected_status)));
end;
$$;
create function pg_temp.pilot_c_register(device text, legacy boolean default false) returns void
language plpgsql security invoker set search_path = '' as $$
declare ctx jsonb := pg_temp.pilot_c_context();
begin
    if legacy then
        perform public.pilot_register_device((ctx->>'profile')::uuid,
            (ctx->>'a')::uuid, (ctx->>'b')::uuid, (ctx->>device)::uuid);
    else
        perform public.pilot_register_third_device((ctx->>'profile')::uuid,
            (ctx->>'a')::uuid, (ctx->>'b')::uuid, (ctx->>'c')::uuid, (ctx->>device)::uuid);
    end if;
end;
$$;
-- Empreintes SQL internes : aucune position ni ligne de progression n'est affichee.
-- Auth : uniquement id/is_anonymous et id/user_id/not_after, jamais mot de passe,
-- jeton, adresse du compte reel ou contenu d'une session.
create function pg_temp.pilot_c_fingerprint() returns jsonb
language sql security invoker set search_path = '' as $$
    select jsonb_build_object(
        'profiles', (select md5(coalesce(jsonb_agg(to_jsonb(b) order by owner_id)::text, '[]'))
            from jojo_progress_pilot.pilot_profile_bindings b),
        'devices', (select md5(coalesce(jsonb_agg(to_jsonb(d) order by owner_id, id)::text, '[]'))
            from jojo_progress_pilot.pilot_devices d),
        'progress', (select md5(coalesce(jsonb_agg(to_jsonb(w)
            order by owner_id, profile_binding_id, device_id, content_key)::text, '[]'))
            from jojo_progress_pilot.pilot_watch_progress w),
        'auth_users_public', (select md5(coalesce(jsonb_agg(jsonb_build_array(id, is_anonymous)
            order by id)::text, '[]')) from auth.users),
        'auth_sessions_public', (select md5(coalesce(jsonb_agg(jsonb_build_array(id, user_id, not_after)
            order by id)::text, '[]')) from auth.sessions));
$$;

do $validation$
declare
    ctx jsonb;
    before_state jsonb;
    cases jsonb := '[]';
    passed integer := 0;
    success boolean := false;
    failure_state text;
    failure_message text;
    device text;
    table_name text;
    verb text;
    field text;
    claims jsonb;
    op jsonb;
begin
    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings) <> 1
        or (select count(*) from jojo_progress_pilot.pilot_devices) <> 3 then
        raise exception 'PILOT_C_VALIDATION_EXPECTED_ONE_PROFILE_THREE_DEVICES';
    end if;
    select jsonb_build_object('owner', b.owner_id, 'profile', b.id,
        'a', b.device_a, 'b', b.device_b, 'c', b.device_c,
        'session_a', a.auth_session_id, 'session_b', d.auth_session_id, 'session_c', c.auth_session_id,
        'fixture_key', md5('pilot-c-validation:fixture:1') || md5('pilot-c-validation:fixture:2'),
        'outsider', 'd31cbaf1-633a-4c78-a1e1-376cd18d7500',
        'session_outsider', 'd31cbaf1-633a-4c78-a1e1-376cd18d7501',
        'session_fresh', 'd31cbaf1-633a-4c78-a1e1-376cd18d7502',
        'session_expired', 'd31cbaf1-633a-4c78-a1e1-376cd18d7503',
        'session_nonexistent', 'd31cbaf1-633a-4c78-a1e1-376cd18d7504') into strict ctx
    from jojo_progress_pilot.pilot_profile_bindings b
    join jojo_progress_pilot.pilot_devices a on a.owner_id = b.owner_id and a.id = b.device_a and a.slot = 1 and a.active
    join jojo_progress_pilot.pilot_devices d on d.owner_id = b.owner_id and d.id = b.device_b and d.slot = 2 and d.active
    join jojo_progress_pilot.pilot_devices c on c.owner_id = b.owner_id and c.id = b.device_c and c.slot = 3 and c.active
    where b.owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'
        and b.id = '2e43435a-eda7-44d0-8d95-476a2a973446'
        and b.device_a = 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba'
        and b.device_b = '12bb7efd-7782-4903-8fd1-052ea5db924d'
        and b.device_c = '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4'
        and c.auth_session_id = 'a3ebaaf8-eff5-4393-8183-c749597d5568';
    if exists (select 1 from jojo_progress_pilot.pilot_devices d
        where not exists (select 1 from auth.sessions s where s.id = d.auth_session_id
            and s.user_id = d.owner_id and (s.not_after is null or s.not_after > now())))
        or exists (select 1 from auth.users where id = (ctx->>'outsider')::uuid
            or email = 'pilot-c-validation@example.invalid')
        or exists (select 1 from auth.sessions where id in ((ctx->>'session_outsider')::uuid,
            (ctx->>'session_fresh')::uuid, (ctx->>'session_expired')::uuid,
            (ctx->>'session_nonexistent')::uuid))
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress
            where content_key = ctx->>'fixture_key') then
        raise exception 'PILOT_C_VALIDATION_SESSION_EXPIRED_OR_FIXTURE_COLLISION';
    end if;
    perform set_config('jojo.progress_pilot_c_validation_context', ctx::text, true);
    before_state := pg_temp.pilot_c_fingerprint();

    -- Toute ecriture de fixture, y compris la desactivation temporaire de C,
    -- est dans ce bloc EXCEPTION, toujours annule avant son controle final.
    begin
        insert into auth.users(id, email, is_anonymous)
            values ((ctx->>'outsider')::uuid, 'pilot-c-validation@example.invalid', false);
        insert into auth.sessions(id, user_id, not_after) values
            ((ctx->>'session_outsider')::uuid, (ctx->>'outsider')::uuid, null),
            ((ctx->>'session_fresh')::uuid, (ctx->>'owner')::uuid, null),
            ((ctx->>'session_expired')::uuid, (ctx->>'owner')::uuid, now() - interval '1 minute');
        perform pg_temp.pilot_c_denied('constraint_no_fourth_slot', format(
            'update jojo_progress_pilot.pilot_devices set slot=4 where id=%L::uuid', ctx->>'c'), '23514');
        perform pg_temp.pilot_c_denied('constraint_c_distinct_from_a', format(
            'update jojo_progress_pilot.pilot_profile_bindings set device_c=device_a where owner_id=%L::uuid', ctx->>'owner'), '23514');

        execute 'set local role authenticated';
        foreach device in array array['a', 'b'] loop
            perform pg_temp.pilot_c_claims(device);
            perform pg_temp.pilot_c_ok(device || '_legacy_register',
                format('select pg_temp.pilot_c_register(%L,true)', device));
            perform pg_temp.pilot_c_ack(device || '_own_write', pg_temp.pilot_c_op(device), 'applied');
            perform pg_temp.pilot_c_denied(device || '_cannot_register_c',
                $q$select pg_temp.pilot_c_register('c')$q$, '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
            perform pg_temp.pilot_c_denied(device || '_cannot_write_c',
                $q$select public.pilot_write_progress(pg_temp.pilot_c_op('c'))$q$, '42501', 'PILOT_DEVICE_DENIED');
        end loop;
        perform pg_temp.pilot_c_claims('c');
        perform pg_temp.pilot_c_ok('c_register', $q$select pg_temp.pilot_c_register('c')$q$);
        perform pg_temp.pilot_c_denied('c_cannot_use_legacy_register',
            $q$select pg_temp.pilot_c_register('c',true)$q$, '42501', 'PILOT_ASSOCIATION_DENIED');
        foreach device in array array['a', 'b'] loop
            perform pg_temp.pilot_c_denied('c_cannot_register_' || device,
                format('select pg_temp.pilot_c_register(%L)', device), '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
            perform pg_temp.pilot_c_denied('c_cannot_write_' || device,
                format('select public.pilot_write_progress(pg_temp.pilot_c_op(%L,2))', device), '42501', 'PILOT_DEVICE_DENIED');
            perform pg_temp.pilot_c_denied('c_legacy_cannot_register_' || device,
                format('select pg_temp.pilot_c_register(%L,true)', device), '42501', 'PILOT_ASSOCIATION_DENIED');
        end loop;
        perform pg_temp.pilot_c_ack('c_insert', pg_temp.pilot_c_op('c'), 'applied');
        perform pg_temp.pilot_c_ack('c_duplicate', pg_temp.pilot_c_op('c'), 'duplicate');
        perform pg_temp.pilot_c_ack('c_new_revision_rewind', pg_temp.pilot_c_op('c',2,3000), 'applied');
        perform pg_temp.pilot_c_ack('c_stale', pg_temp.pilot_c_op('c'), 'stale');
        perform pg_temp.pilot_c_ack('c_conflict', pg_temp.pilot_c_op('c',2,90000), 'conflict');

        foreach table_name in array array['pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress'] loop
            foreach verb in array array['select', 'insert', 'update', 'delete'] loop
                perform pg_temp.pilot_c_denied('authenticated_direct_' || verb || '_' || table_name,
                    case verb when 'select' then format('select * from jojo_progress_pilot.%I', table_name)
                    when 'insert' then format('insert into jojo_progress_pilot.%I default values', table_name)
                    when 'update' then format('update jojo_progress_pilot.%I set owner_id=owner_id', table_name)
                    else format('delete from jojo_progress_pilot.%I', table_name) end, '42501');
            end loop;
        end loop;
        perform pg_temp.pilot_c_denied('authenticated_private_identity',
            'select * from jojo_progress_pilot.pilot_request_identity()', '42501');
        foreach field in array array['title', 'url', 'path', 'nas_token', 'deleted'] loop
            op := jsonb_set(pg_temp.pilot_c_op('c',4), array['0','payload',field], '"forbidden"');
            perform pg_temp.pilot_c_denied('c_metadata_' || field,
                format('select public.pilot_write_progress(%L::jsonb)', op), '22023', 'PILOT_PAYLOAD_INVALID');
        end loop;
        op := jsonb_set(pg_temp.pilot_c_op('c',4), '{0,payload,owner_id}', ctx->'outsider');
        perform pg_temp.pilot_c_denied('c_wrong_owner',
            format('select public.pilot_write_progress(%L::jsonb)', op), '42501', 'PILOT_DEVICE_DENIED');
        op := jsonb_set(pg_temp.pilot_c_op('c',4), '{0,payload,profile_binding_id}', ctx->'outsider');
        perform pg_temp.pilot_c_denied('c_wrong_profile',
            format('select public.pilot_write_progress(%L::jsonb)', op), '23503');
        foreach field in array array['profile', 'a', 'b', 'c'] loop
            claims := ctx || jsonb_build_object(field, ctx->>'outsider');
            perform pg_temp.pilot_c_denied('c_register_wrong_' || field, format(
                'select public.pilot_register_third_device(%L::uuid,%L::uuid,%L::uuid,%L::uuid,%L::uuid)',
                claims->>'profile', claims->>'a', claims->>'b', claims->>'c', ctx->>'c'),
                '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
        end loop;
        perform pg_temp.pilot_c_denied('c_register_null_c', format(
            'select public.pilot_register_third_device(%L::uuid,%L::uuid,%L::uuid,null,%L::uuid)',
            ctx->>'profile', ctx->>'a', ctx->>'b', ctx->>'c'), '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');

        execute 'reset role';
        execute 'set local role jojo_progress_pilot_rpc';
        perform pg_temp.pilot_c_assert('c_rls_visible_device',
            (select count(*) = 1 and bool_and(id = (ctx->>'c')::uuid)
                from jojo_progress_pilot.pilot_devices));
        perform pg_temp.pilot_c_assert('c_rls_progress_isolation',
            not exists (select 1 from jojo_progress_pilot.pilot_watch_progress where device_id <> (ctx->>'c')::uuid)
            and exists (select 1 from jojo_progress_pilot.pilot_watch_progress
                where content_key = ctx->>'fixture_key' and revision = 2 and position_ms = 3000));
        perform pg_temp.pilot_c_denied('rpc_cannot_read_auth', 'select id from auth.sessions', '42501');
        perform pg_temp.pilot_c_rows('c_rls_update_own', format(
            'update jojo_progress_pilot.pilot_watch_progress set revision=3,position_ms=4000,progress_percent=4,source_time_ms=1003,operation_id=%L::uuid where content_key=%L',
            md5('pilot-c-validation:c:3')::uuid, ctx->>'fixture_key'), 1);
        perform pg_temp.pilot_c_rows('c_revision_regression_ignored', format(
            'update jojo_progress_pilot.pilot_watch_progress set revision=1 where content_key=%L', ctx->>'fixture_key'), 0);
        perform pg_temp.pilot_c_denied('c_same_revision_conflict', format(
            'update jojo_progress_pilot.pilot_watch_progress set position_ms=9999 where content_key=%L', ctx->>'fixture_key'),
            '23514', 'PILOT_REVISION_CONFLICT');
        perform pg_temp.pilot_c_rows('c_rls_cannot_update_ab', format(
            'update jojo_progress_pilot.pilot_watch_progress set revision=9 where device_id<>%L::uuid', ctx->>'c'), 0);
        perform pg_temp.pilot_c_denied('rpc_cannot_delete_progress',
            'delete from jojo_progress_pilot.pilot_watch_progress', '42501');
        foreach device in array array['a', 'b'] loop
            perform pg_temp.pilot_c_denied('c_rls_cannot_insert_' || device, format(
                'insert into jojo_progress_pilot.pilot_watch_progress(owner_id,profile_binding_id,device_id,content_key,media_kind,position_ms,duration_ms,source_time_ms,revision,operation_id) values (%L::uuid,%L::uuid,%L::uuid,%L,''file'',0,100000,1001,1,%L::uuid)',
                ctx->>'owner', ctx->>'profile', ctx->>device, repeat('e',64), md5('rls-denied:' || device)::uuid), '42501');
        end loop;

        execute 'reset role';
        execute 'set local role authenticated';
        op := jsonb_build_array(pg_temp.pilot_c_op('c',4)->0, pg_temp.pilot_c_op('a',2)->0);
        perform pg_temp.pilot_c_denied('c_mixed_batch_rejected',
            format('select public.pilot_write_progress(%L::jsonb)', op), '42501', 'PILOT_DEVICE_DENIED');
        execute 'reset role';
        execute 'set local role jojo_progress_pilot_rpc';
        perform pg_temp.pilot_c_assert('c_mixed_batch_atomic', exists (
            select 1 from jojo_progress_pilot.pilot_watch_progress where content_key = ctx->>'fixture_key'
                and revision = 3 and position_ms = 4000));
        foreach device in array array['a', 'b'] loop
            perform pg_temp.pilot_c_claims(device);
            perform pg_temp.pilot_c_assert(device || '_rls_isolation',
                (select count(*) = 1 from jojo_progress_pilot.pilot_watch_progress where content_key = ctx->>'fixture_key')
                and not exists (select 1 from jojo_progress_pilot.pilot_watch_progress where device_id <> (ctx->>device)::uuid));
        end loop;

        -- Autre utilisateur avec session existante et valide dans les fixtures SQL.
        claims := jsonb_build_object('sub', ctx->>'outsider', 'role','authenticated',
            'is_anonymous',false,'session_id',ctx->>'session_outsider');
        perform set_config('request.jwt.claims', claims::text, true);
        perform pg_temp.pilot_c_assert('outsider_session_valid_in_database',
            jojo_progress_pilot.pilot_session_matches((ctx->>'outsider')::uuid, (ctx->>'session_outsider')::uuid));
        perform pg_temp.pilot_c_assert('outsider_rls_no_rows',
            not exists (select 1 from jojo_progress_pilot.pilot_profile_bindings)
            and not exists (select 1 from jojo_progress_pilot.pilot_devices)
            and not exists (select 1 from jojo_progress_pilot.pilot_watch_progress));
        execute 'reset role';
        execute 'set local role authenticated';
        perform pg_temp.pilot_c_denied('outsider_c_register', $q$select pg_temp.pilot_c_register('c')$q$,
            '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
        perform pg_temp.pilot_c_denied('outsider_c_write', $q$select public.pilot_write_progress(pg_temp.pilot_c_op('c',4))$q$,
            '42501', 'PILOT_DEVICE_DENIED');

        foreach device in array array['fresh', 'expired', 'nonexistent'] loop
            perform pg_temp.pilot_c_claims(device);
            perform pg_temp.pilot_c_denied(device || '_c_register', $q$select pg_temp.pilot_c_register('c')$q$,
                '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
            perform pg_temp.pilot_c_denied(device || '_c_write', $q$select public.pilot_write_progress(pg_temp.pilot_c_op('c',4))$q$,
                '42501', case device when 'fresh' then 'PILOT_DEVICE_DENIED' else 'PILOT_SESSION_REQUIRED' end);
        end loop;
        foreach field in array array['anonymous', 'missing_anonymous', 'missing_session', 'missing_sub'] loop
            claims := jsonb_build_object('sub',ctx->>'owner','role','authenticated',
                'is_anonymous',false,'session_id',ctx->>'session_c');
            claims := case field when 'anonymous' then claims || '{"is_anonymous":true}'::jsonb
                when 'missing_anonymous' then claims - 'is_anonymous'
                when 'missing_session' then claims - 'session_id' else claims - 'sub' end;
            perform set_config('request.jwt.claims', claims::text, true);
            perform pg_temp.pilot_c_denied(field || '_c_register', $q$select pg_temp.pilot_c_register('c')$q$,
                '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
            perform pg_temp.pilot_c_denied(field || '_c_write', $q$select public.pilot_write_progress(pg_temp.pilot_c_op('c',4))$q$,
                '42501', 'PILOT_SESSION_REQUIRED');
        end loop;

        execute 'reset role';
        update jojo_progress_pilot.pilot_devices set active=false where owner_id=(ctx->>'owner')::uuid and id=(ctx->>'c')::uuid;
        execute 'set local role authenticated';
        perform pg_temp.pilot_c_claims('c');
        perform pg_temp.pilot_c_denied('inactive_c_register', $q$select pg_temp.pilot_c_register('c')$q$,
            '42501', 'PILOT_THIRD_DEVICE_ASSOCIATION_DENIED');
        perform pg_temp.pilot_c_denied('inactive_c_write', $q$select public.pilot_write_progress(pg_temp.pilot_c_op('c',4))$q$,
            '42501', 'PILOT_DEVICE_DENIED');

        execute 'reset role';
        execute 'set local role anon';
        perform pg_temp.pilot_c_denied('anon_register_c', 'select public.pilot_register_third_device(null,null,null,null,null)', '42501');
        perform pg_temp.pilot_c_denied('anon_register_ab', 'select public.pilot_register_device(null,null,null,null)', '42501');
        perform pg_temp.pilot_c_denied('anon_write', 'select public.pilot_write_progress(''[]''::jsonb)', '42501');
        foreach table_name in array array['pilot_profile_bindings', 'pilot_devices', 'pilot_watch_progress'] loop
            foreach verb in array array['select', 'insert', 'update', 'delete'] loop
                perform pg_temp.pilot_c_denied('anon_direct_' || verb || '_' || table_name,
                    case verb when 'select' then format('select * from jojo_progress_pilot.%I', table_name)
                    when 'insert' then format('insert into jojo_progress_pilot.%I default values', table_name)
                    when 'update' then format('update jojo_progress_pilot.%I set owner_id=owner_id', table_name)
                    else format('delete from jojo_progress_pilot.%I', table_name) end, '42501');
            end loop;
        end loop;
        execute 'reset role';
        select count(*), jsonb_agg(case_id order by case_id) into passed, cases from pg_temp.pilot_c_results;
        if passed <> 97 then raise exception 'PILOT_C_VALIDATION_UNEXPECTED_ASSERTION_COUNT: %', passed; end if;
        -- Ce signal annule TOUT le bloc, meme lorsque les assertions reussissent.
        raise exception using errcode = 'ZP011', message = 'PILOT_C_FIXTURES_ROLLBACK';
    exception
        when sqlstate 'ZP011' then
            if sqlerrm is distinct from 'PILOT_C_FIXTURES_ROLLBACK' then raise; end if;
            success := true;
        when others then
            get stacked diagnostics failure_state = returned_sqlstate, failure_message = message_text;
            success := false;
    end;
    execute 'reset role';
    if pg_temp.pilot_c_fingerprint() is distinct from before_state
        or exists (select 1 from pg_temp.pilot_c_results) then
        raise exception 'PILOT_C_VALIDATION_ROLLBACK_OR_PRESERVATION_FAILED';
    end if;
    insert into pg_temp.pilot_c_summary values (
        case when success then 'PASSED_FIXTURES_ROLLED_BACK' else 'FAILED_FIXTURES_ROLLED_BACK' end,
        passed, 97, cases, true, true, failure_state, failure_message);
end;
$validation$;

-- Seul PASSED avec les deux booleens true, aucun echec et les assertions attendues
-- constitue un succes. FAILED n'est jamais une autorisation d'activer le pilote.
select * from pg_temp.pilot_c_summary;
rollback;
