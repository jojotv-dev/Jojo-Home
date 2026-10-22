-- SUITE REVISEE PROPOSEE, NON EXECUTEE DANS CETTE VERSION. Accord separe requis.
-- Premiere version interrompue par 42501 (USAGE auth absent), puis fixtures annulees.
-- Exclusivement une base Supabase de test dediee et vide, APRES 001 ET 003 autorisees.
-- Avant execution autorisee, l'administrateur doit confirmer la connexion cible puis
-- definir manuellement jojo.progress_pilot_test_authorized = 'DEDICATED_EMPTY_TEST'.
-- Cette suite ne definit PAS elle-meme ce consentement. Sans lui elle echoue avant toute ecriture.
-- ROLLBACK annule les fixtures SQL, pas les effets externes d'eventuels triggers Auth.
-- Ne jamais executer sur une base de production, meme avec ROLLBACK.
begin;
do $$
begin
    if current_setting('jojo.progress_pilot_test_authorized', true) is distinct from 'DEDICATED_EMPTY_TEST' then
        raise exception 'Confirmer separement la connexion a la base de test dediee';
    end if;
    if not exists (select 1 from pg_roles where rolname = current_user and (rolsuper or rolbypassrls)) then
        raise exception 'Administrateur de test avec BYPASSRLS requis pour les fixtures';
    end if;
    if exists (select 1 from jojo_progress_pilot.pilot_profile_bindings)
        or exists (select 1 from jojo_progress_pilot.pilot_devices)
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress) then
        raise exception 'Tables pilote non vides : suite refusee';
    end if;
    if exists (select 1 from auth.users where id in (
        '11111111-1111-4111-8111-111111111111', '88888888-8888-4888-8888-888888888888')) then
        raise exception 'Collision avec les identites fictives : suite refusee';
    end if;
    if exists (select 1 from auth.users) or exists (select 1 from auth.sessions) then
        raise exception 'Auth non vide : executer cette suite avant de creer les vrais comptes de test';
    end if;
    if exists (select 1 from pg_roles where rolname = 'jojo_progress_pilot_rpc' and (rolsuper or rolbypassrls or rolcanlogin))
        or pg_has_role('authenticated', 'jojo_progress_pilot_rpc', 'MEMBER')
        or pg_has_role('anon', 'jojo_progress_pilot_rpc', 'MEMBER') then
        raise exception 'Role RPC non isole';
    end if;
    if exists (select 1 from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'jojo_progress_pilot' and c.relkind = 'r'
        and (not c.relrowsecurity or not c.relforcerowsecurity
            or c.relowner = (select oid from pg_roles where rolname = 'jojo_progress_pilot_rpc'))) then
        raise exception 'RLS ou propriete des tables invalide';
    end if;
    if not exists (select 1 from pg_catalog.pg_proc f
        join pg_catalog.pg_roles r on r.oid = f.proowner
        where f.oid = 'jojo_progress_pilot.pilot_session_matches(uuid,uuid)'::regprocedure
            and f.prosecdef and r.rolname = 'postgres' and r.rolbypassrls
            and f.proconfig @> array['search_path=""']::text[])
        or has_function_privilege('anon', 'jojo_progress_pilot.pilot_session_matches(uuid,uuid)', 'EXECUTE')
        or has_function_privilege('authenticated', 'jojo_progress_pilot.pilot_session_matches(uuid,uuid)', 'EXECUTE')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'jojo_progress_pilot.pilot_session_matches(uuid,uuid)', 'EXECUTE')
        or has_column_privilege('jojo_progress_pilot_rpc', 'auth.sessions', 'id', 'SELECT') then
        raise exception 'Helper prive Auth ou isolation de ses permissions invalide';
    end if;
    if to_regprocedure('jojo_progress_pilot.pilot_request_identity()') is null then
        raise exception 'Appliquer la correction 003 autorisee avant cette suite revisee';
    end if;
    if not exists (select 1 from pg_catalog.pg_proc f
        where f.oid = 'jojo_progress_pilot.pilot_request_identity()'::regprocedure
            and not f.prosecdef and f.prosqlbody is not null
            and f.proowner = (select oid from pg_catalog.pg_roles where rolname = 'postgres')
            and f.proconfig @> array['search_path=""']::text[])
        or has_function_privilege('anon', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or has_function_privilege('authenticated', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or not has_function_privilege('jojo_progress_pilot_rpc', 'jojo_progress_pilot.pilot_request_identity()', 'EXECUTE')
        or has_schema_privilege('jojo_progress_pilot_rpc', 'auth', 'USAGE') then
        raise exception 'Helper identite non isole ou acces Auth elargi';
    end if;
end;
$$;

-- Helpers temporaires, SECURITY INVOKER, uniquement dans cette connexion de test.
create temporary table pilot_validation_namespace (marker boolean) on commit drop;
create function pg_temp.pilot_expect_error(statement text, expected text[]) returns void
language plpgsql security invoker set search_path = '' as $$
begin
    begin
        execute statement;
    exception when others then
        if sqlstate = any(expected) then return; end if;
        raise;
    end;
    raise exception 'Instruction interdite acceptee : %', statement;
end;
$$;
create function pg_temp.pilot_op(device uuid, rev bigint default 1, pos bigint default 10000) returns jsonb
language sql security invoker set search_path = '' as $$
    select jsonb_build_array(jsonb_build_object('operation_id', md5(device::text || ':' || rev::text)::uuid,
        'revision', rev, 'payload', jsonb_build_object(
        'owner_id', '11111111-1111-4111-8111-111111111111',
        'profile_binding_id', '22222222-2222-4222-8222-222222222222', 'device_id', device,
        'content_key', repeat('a', 64), 'media_kind', 'film', 'position_ms', pos,
        'duration_ms', 100000, 'progress_percent', 10, 'source_time_ms', 1000 + rev)));
$$;
create function pg_temp.pilot_expect_ack(op jsonb, status text) returns void
language plpgsql security invoker set search_path = '' as $$
declare ack jsonb := public.pilot_write_progress(op);
begin
    -- Equality verifies exact count, keys, UUID, revision and status. NULL cannot pass.
    if ack is distinct from jsonb_build_array(jsonb_build_object(
        'operation_id', op->0->'operation_id', 'revision', op->0->'revision', 'status', status)) then
        raise exception 'ACK manquant, excessif ou incorrect : %', ack;
    end if;
end;
$$;

insert into auth.users(id, email, is_anonymous) values
    ('11111111-1111-4111-8111-111111111111', 'pilot-owner@example.invalid', false),
    ('88888888-8888-4888-8888-888888888888', 'pilot-outsider@example.invalid', false);
insert into auth.sessions(id, user_id, not_after) values
    ('77777777-7777-4777-8777-777777777777', '11111111-1111-4111-8111-111111111111', null),
    ('66666666-6666-4666-8666-666666666666', '11111111-1111-4111-8111-111111111111', null),
    ('99999999-9999-4999-8999-999999999999', '11111111-1111-4111-8111-111111111111', null),
    ('55555555-5555-4555-8555-555555555555', '88888888-8888-4888-8888-888888888888', null);
insert into jojo_progress_pilot.pilot_profile_bindings(owner_id, id, device_a, device_b) values (
    '11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444');
insert into jojo_progress_pilot.pilot_devices(owner_id, id, slot, auth_session_id) values
    ('11111111-1111-4111-8111-111111111111', '33333333-3333-4333-8333-333333333333', 1, '77777777-7777-4777-8777-777777777777'),
    ('11111111-1111-4111-8111-111111111111', '44444444-4444-4444-8444-444444444444', 2, '66666666-6666-4666-8666-666666666666');
select pg_temp.pilot_expect_error($q$insert into jojo_progress_pilot.pilot_profile_bindings(owner_id,id,device_a,device_b)
    values ('88888888-8888-4888-8888-888888888888','99999999-9999-4999-8999-999999999999',
        '55555555-5555-4555-8555-555555555555','66666666-6666-4666-8666-666666666666')$q$, array['23505']);

set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"77777777-7777-4777-8777-777777777777"}', true);
select pg_temp.pilot_expect_error('select * from jojo_progress_pilot.pilot_request_identity()', array['42501']);
select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444','33333333-3333-4333-8333-333333333333');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333'), 'applied');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333'), 'duplicate');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333', 2, 3000), 'applied');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333'), 'stale');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333', 2, 90000), 'conflict');

-- Aucun SELECT ni DML direct pour le client, y compris sur ses propres observations.
do $$
declare t text; cmd text;
begin
    foreach t in array array['pilot_profile_bindings','pilot_devices','pilot_watch_progress'] loop
        foreach cmd in array array['select * from ','insert into ','update ','delete from '] loop
            perform pg_temp.pilot_expect_error(case cmd
                when 'insert into ' then cmd || 'jojo_progress_pilot.' || t || ' default values'
                when 'update ' then cmd || 'jojo_progress_pilot.' || t || ' set owner_id = owner_id'
                else cmd || 'jojo_progress_pilot.' || t end, array['42501']);
        end loop;
    end loop;
end;
$$;

-- Valeurs, limites de lot et metadonnees interdites.
do $$
declare op jsonb := pg_temp.pilot_op('33333333-3333-4333-8333-333333333333', 3); bad jsonb;
begin
    foreach bad in array array[
        '[]'::jsonb, '{}'::jsonb,
        (select jsonb_agg(op->0) from generate_series(1,51)),
        jsonb_set(op, '{0,payload,title}', '"forbidden"'),
        jsonb_set(op, '{0,payload,url}', '"https://example.invalid"'),
        jsonb_set(op, '{0,payload,deleted}', 'true'),
        jsonb_set(op, '{0,payload,position_ms}', '-1'),
        jsonb_set(op, '{0,payload,duration_ms}', '-1'),
        jsonb_set(op, '{0,payload,progress_percent}', '101'),
        jsonb_set(op, '{0,payload,media_kind}', '"live"'),
        jsonb_set(op, '{0,payload,content_key}', '"not-a-hmac"'),
        jsonb_set(op, '{0,revision}', '0'), jsonb_set(op, '{0,revision}', '1.5'),
        jsonb_set(op, '{0,payload,position_ms}', '"1000"'),
        jsonb_set(op, '{0,payload,source_time_ms}', 'null')
    ] loop
        perform pg_temp.pilot_expect_error(format('select public.pilot_write_progress(%L::jsonb)', bad), array['22023','23514','23502']);
    end loop;
    perform pg_temp.pilot_expect_error($q$select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
        '33333333-3333-4333-8333-333333333333','55555555-5555-4555-8555-555555555555','55555555-5555-4555-8555-555555555555')$q$, array['42501']);
    perform pg_temp.pilot_expect_error(format('select public.pilot_write_progress(%L::jsonb)',
        jsonb_set(op, '{0,payload,owner_id}', '"88888888-8888-4888-8888-888888888888"')), array['42501']);
    perform pg_temp.pilot_expect_error(format('select public.pilot_write_progress(%L::jsonb)',
        jsonb_set(op, '{0,payload,profile_binding_id}', '"99999999-9999-4999-8999-999999999999"')), array['23503']);
end;
$$;

-- Appareil B : ecriture positive propre, lecture directe refusee, ecriture/usurpation A refusees.
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"66666666-6666-4666-8666-666666666666"}', true);
select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444','44444444-4444-4444-8444-444444444444');
select pg_temp.pilot_expect_ack(pg_temp.pilot_op('44444444-4444-4444-8444-444444444444'), 'applied');
select pg_temp.pilot_expect_error($q$select public.pilot_write_progress(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333',3))$q$, array['42501']);
select pg_temp.pilot_expect_error($q$select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444','33333333-3333-4333-8333-333333333333')$q$, array['42501']);
select pg_temp.pilot_expect_error('select * from jojo_progress_pilot.pilot_watch_progress', array['42501']);

-- RLS interne au role de la RPC : B ne voit que B, sa lecture A est filtree.
reset role;
set local role jojo_progress_pilot_rpc;
do $$
begin
    if (select count(*) from jojo_progress_pilot.pilot_request_identity()) is distinct from 1::bigint
        or not exists (select 1 from jojo_progress_pilot.pilot_request_identity() identity
            where identity.owner_id = '11111111-1111-4111-8111-111111111111'
                and identity.session_id = '66666666-6666-4666-8666-666666666666') then
        raise exception 'Helper identite ne correspond pas au JWT courant B';
    end if;
    if jojo_progress_pilot.pilot_session_matches('11111111-1111-4111-8111-111111111111',
        '66666666-6666-4666-8666-666666666666') is distinct from true
        or jojo_progress_pilot.pilot_session_matches('88888888-8888-4888-8888-888888888888',
            '66666666-6666-4666-8666-666666666666') is distinct from false
        or jojo_progress_pilot.pilot_session_matches('11111111-1111-4111-8111-111111111111',
            '77777777-7777-4777-8777-777777777777') is distinct from false then
        raise exception 'Helper Auth accepte une autre identite/session ou refuse la session B';
    end if;
    perform pg_temp.pilot_expect_error('select id from auth.sessions', array['42501']);
    if (select count(*) from jojo_progress_pilot.pilot_watch_progress) is distinct from 1::bigint
        or exists (select 1 from jojo_progress_pilot.pilot_watch_progress where device_id <> '44444444-4444-4444-8444-444444444444') then
        raise exception 'Isolation RLS interne invalide';
    end if;
end;
$$;
reset role;
set local role authenticated;

-- Nouveau login du meme compte : aucune reassociation silencieuse de A ou B.
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"99999999-9999-4999-8999-999999999999"}', true);
select pg_temp.pilot_expect_error($q$select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
    '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444','33333333-3333-4333-8333-333333333333')$q$, array['42501']);
select pg_temp.pilot_expect_error($q$select public.pilot_write_progress(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333',3))$q$, array['42501']);

-- Autre compte, compte Auth anonyme (role authenticated), session inexistante, claim absent.
do $$
declare claims text;
begin
    foreach claims in array array[
        '{"sub":"88888888-8888-4888-8888-888888888888","role":"authenticated","is_anonymous":false,"session_id":"55555555-5555-4555-8555-555555555555"}',
        '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":true,"session_id":"77777777-7777-4777-8777-777777777777"}',
        '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"}',
        '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","session_id":"77777777-7777-4777-8777-777777777777"}'
    ] loop
        perform set_config('request.jwt.claims', claims, true);
        perform pg_temp.pilot_expect_error($q$select public.pilot_write_progress(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333',3))$q$, array['42501']);
        perform pg_temp.pilot_expect_error($q$select public.pilot_register_device('22222222-2222-4222-8222-222222222222',
            '33333333-3333-4333-8333-333333333333','44444444-4444-4444-8444-444444444444','33333333-3333-4333-8333-333333333333')$q$, array['42501']);
    end loop;
end;
$$;

-- Expiration effective de la session A et desactivation administrative de B.
reset role;
update auth.sessions set not_after = now() - interval '1 minute' where id = '77777777-7777-4777-8777-777777777777';
update jojo_progress_pilot.pilot_devices set active = false where id = '44444444-4444-4444-8444-444444444444';
set local role authenticated;
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"77777777-7777-4777-8777-777777777777"}', true);
select pg_temp.pilot_expect_error($q$select public.pilot_write_progress(pg_temp.pilot_op('33333333-3333-4333-8333-333333333333',3))$q$, array['42501']);
select set_config('request.jwt.claims', '{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","is_anonymous":false,"session_id":"66666666-6666-4666-8666-666666666666"}', true);
select pg_temp.pilot_expect_error($q$select public.pilot_write_progress(pg_temp.pilot_op('44444444-4444-4444-8444-444444444444',2))$q$, array['42501']);

reset role;
set local role anon;
select pg_temp.pilot_expect_error('select * from jojo_progress_pilot.pilot_request_identity()', array['42501']);
select pg_temp.pilot_expect_error('select public.pilot_write_progress(''[]''::jsonb)', array['42501']);
select pg_temp.pilot_expect_error($q$select public.pilot_register_device(null,null,null,null)$q$, array['42501']);
select pg_temp.pilot_expect_error('select * from jojo_progress_pilot.pilot_watch_progress', array['42501']);
reset role;

-- Etat final verifie administrativement : deux observations independantes, aucune regression,
-- aucun changement d'association, aucun effet des essais refuses.
do $$
begin
    if (select count(*) from jojo_progress_pilot.pilot_watch_progress) is distinct from 2::bigint
        or not exists (select 1 from jojo_progress_pilot.pilot_watch_progress where device_id = '33333333-3333-4333-8333-333333333333' and revision = 2 and position_ms = 3000)
        or not exists (select 1 from jojo_progress_pilot.pilot_watch_progress where device_id = '44444444-4444-4444-8444-444444444444' and revision = 1 and position_ms = 10000)
        or (select count(*) from jojo_progress_pilot.pilot_devices) is distinct from 2::bigint
        or not exists (select 1 from jojo_progress_pilot.pilot_devices where slot = 1 and auth_session_id = '77777777-7777-4777-8777-777777777777') then
        raise exception 'Donnees finales alterees par un rejeu ou un cas interdit';
    end if;
end;
$$;
rollback;
