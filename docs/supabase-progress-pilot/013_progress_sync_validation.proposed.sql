-- PROPOSITION NON EXECUTEE : accord distinct obligatoire pour ce script.
-- Projet jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh, apres 012.
-- Test de roles/JWT dans PostgreSQL, pas un essai Auth HTTP ni appareil.
-- Espace de progression fictif seulement, un utilisateur sans mot de passe et une
-- session fictive non associee. C est rendu inactif temporairement, puis tout est
-- annule dans une sous-transaction et compare a l'etat initial. Ne pas lancer
-- pendant une activation cloud ou un changement d'association.
-- Ajouter APRES accord dans la meme transaction, avant le premier DO :
-- SET LOCAL jojo.cloud_validation_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.cloud_validation_approved = 'APPROVED_013_ROLLBACK_ONLY';
begin;
set local statement_timeout='60s';
set local lock_timeout='3s';
set local idle_in_transaction_session_timeout='30s';
set local row_security=on;
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.cloud_validation_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.cloud_validation_approved',true) is distinct from 'APPROVED_013_ROLLBACK_ONLY' then
        raise exception 'CLOUD_VALIDATION_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if exists (select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid
        join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='auth' and c.relname in ('users','sessions') and not t.tgisinternal) then
        raise exception 'CLOUD_VALIDATION_EXTERNAL_AUTH_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;
create function pg_temp.cloud_assert(label text,condition boolean) returns integer
language plpgsql security invoker set search_path='' as $$
begin if condition is distinct from true then raise exception 'CLOUD_ASSERTION_FAILED: %',label; end if; return 1; end;
$$;
create function pg_temp.cloud_denied(label text,command text,expected text) returns integer
language plpgsql security invoker set search_path='' as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=expected then return 1; end if;
        raise exception 'CLOUD_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'CLOUD_UNEXPECTED_PERMISSION: %',label;
end;
$$;
create function pg_temp.cloud_ctx() returns jsonb language sql security invoker set search_path='' as $$
    select current_setting('jojo.cloud_validation_ctx')::jsonb;
$$;
create function pg_temp.cloud_login(which_device text,anonymous boolean default false) returns void
language plpgsql security invoker set search_path='' as $$
declare c jsonb:=pg_temp.cloud_ctx();
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',
        case when which_device='outsider' then c->>'outsider' else c->>'owner' end,
        'role',case when anonymous then 'anon' else 'authenticated' end,
        'is_anonymous',anonymous,'session_id',c->>('session_'||which_device))::text,true);
end;
$$;
create function pg_temp.cloud_read(after_revision bigint default 0) returns jsonb
language sql security invoker set search_path='' as $$
    select public.progress_sync_read((pg_temp.cloud_ctx()->>'profile')::uuid,
        (pg_temp.cloud_ctx()->>'epoch')::uuid,after_revision);
$$;
create function pg_temp.cloud_op(op uuid,seq bigint,expected bigint,pos bigint,hidden boolean default false,
    state text default 'in_progress') returns jsonb language sql security invoker set search_path='' as $$
    select jsonb_build_array(jsonb_build_object('operation_id',op,'sequence',seq,'expected_revision',expected,
        'payload',jsonb_build_object('content_key',repeat('a',64),'media_kind','file','position_ms',pos,
        'duration_ms',1000,'progress_percent',null,'watch_state',state,'resume_hidden',hidden,
        'source_time_ms',100,'server_revision',0,'received_time_ms',0)));
$$;
create function pg_temp.cloud_write(ops jsonb) returns jsonb language sql security invoker set search_path='' as $$
    select public.progress_sync_write((pg_temp.cloud_ctx()->>'profile')::uuid,
        (pg_temp.cloud_ctx()->>'epoch')::uuid,'d8f75684-9d39-4a83-b476-000000000099'::uuid,ops);
$$;
create function pg_temp.cloud_fingerprint() returns jsonb language sql security invoker set search_path='' as $$
    select jsonb_build_object(
        'scopes',(select md5(coalesce(jsonb_agg(to_jsonb(s) order by owner_id,profile_binding_id,key_epoch)::text,'[]'))
            from jojo_progress_pilot.cloud_progress_scopes s),
        'progress',(select md5(coalesce(jsonb_agg(to_jsonb(w) order by owner_id,profile_binding_id,key_epoch,content_key)::text,'[]'))
            from jojo_progress_pilot.cloud_watch_progress w),
        'receipts',(select md5(coalesce(jsonb_agg(to_jsonb(r) order by owner_id,profile_binding_id,key_epoch,device_id,operation_id)::text,'[]'))
            from jojo_progress_pilot.cloud_progress_receipts r),
        'devices',(select md5(coalesce(jsonb_agg(to_jsonb(d) order by owner_id,id)::text,'[]')) from jojo_progress_pilot.pilot_devices d),
        'mirror',(select md5(coalesce(jsonb_agg(to_jsonb(w) order by owner_id,profile_binding_id,device_id,content_key)::text,'[]'))
            from jojo_progress_pilot.pilot_watch_progress w),
        'auth_users',(select md5(coalesce(jsonb_agg(jsonb_build_array(id,is_anonymous) order by id)::text,'[]')) from auth.users),
        'auth_sessions',(select md5(coalesce(jsonb_agg(jsonb_build_array(id,user_id,not_after) order by id)::text,'[]')) from auth.sessions));
$$;

do $tests$
declare
    c jsonb; initial_state jsonb; passed integer:=0; op1 jsonb; payload jsonb; response jsonb;
    name text; role_name text; function_name text; i integer; page_one jsonb; page_two jsonb;
    op_a uuid:='d8f75684-9d39-4a83-b476-000000000001';
    op_b uuid:='d8f75684-9d39-4a83-b476-000000000002';
    op_c uuid:='d8f75684-9d39-4a83-b476-000000000003';
    op_d uuid:='d8f75684-9d39-4a83-b476-000000000004';
    op_e uuid:='d8f75684-9d39-4a83-b476-000000000005';
begin
    select jsonb_build_object('owner',b.owner_id,'profile',b.id,'b',d.id,'c',e.id,
        'session_b',d.auth_session_id,'session_c',e.auth_session_id,'session_a',a.auth_session_id,
        'epoch','d8f75684-9d39-4a83-b476-000000000010',
        'outsider','d8f75684-9d39-4a83-b476-000000000020',
        'session_outsider','d8f75684-9d39-4a83-b476-000000000021',
        'session_fresh','d8f75684-9d39-4a83-b476-000000000022',
        'session_revoked','d8f75684-9d39-4a83-b476-000000000023') into strict c
      from jojo_progress_pilot.pilot_profile_bindings b
      join jojo_progress_pilot.pilot_devices a on a.owner_id=b.owner_id and a.id=b.device_a and a.active
      join jojo_progress_pilot.pilot_devices d on d.owner_id=b.owner_id and d.id=b.device_b and d.active
      join jojo_progress_pilot.pilot_devices e on e.owner_id=b.owner_id and e.id=b.device_c and e.active
      join auth.sessions sa on sa.id=a.auth_session_id and sa.user_id=b.owner_id
        and (sa.not_after is null or sa.not_after>now()+interval '2 minutes')
      join auth.sessions sb on sb.id=d.auth_session_id and sb.user_id=b.owner_id
        and (sb.not_after is null or sb.not_after>now()+interval '2 minutes')
      join auth.sessions sc on sc.id=e.auth_session_id and sc.user_id=b.owner_id
        and (sc.not_after is null or sc.not_after>now()+interval '2 minutes')
      where b.owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and b.id='2e43435a-eda7-44d0-8d95-476a2a973446';
    if exists (select 1 from jojo_progress_pilot.cloud_progress_scopes where key_epoch=(c->>'epoch')::uuid)
       or exists (select 1 from auth.users where id=(c->>'outsider')::uuid)
       or exists (select 1 from auth.sessions where id in ((c->>'session_fresh')::uuid,(c->>'session_outsider')::uuid,
            (c->>'session_revoked')::uuid)) then raise exception 'CLOUD_FIXTURE_COLLISION'; end if;
    perform set_config('jojo.cloud_validation_ctx',c::text,true);
    initial_state:=pg_temp.cloud_fingerprint();
    begin
        insert into auth.users(id,email,is_anonymous) values ((c->>'outsider')::uuid,'cloud-validation@example.invalid',false);
        insert into auth.sessions(id,user_id,not_after) values
            ((c->>'session_outsider')::uuid,(c->>'outsider')::uuid,null),
            ((c->>'session_fresh')::uuid,(c->>'owner')::uuid,null);
        insert into jojo_progress_pilot.cloud_progress_scopes(owner_id,profile_binding_id,key_epoch,enabled,allowed_devices)
            values ((c->>'owner')::uuid,(c->>'profile')::uuid,(c->>'epoch')::uuid,true,
                array[(c->>'b')::uuid,(c->>'c')::uuid]);
        foreach name in array array['cloud_progress_scopes','cloud_watch_progress','cloud_progress_receipts'] loop
            passed:=passed+pg_temp.cloud_assert(name||'_forced_rls',exists(select 1 from pg_class t join pg_namespace n on n.oid=t.relnamespace
                where n.nspname='jojo_progress_pilot' and t.relname=name and t.relrowsecurity and t.relforcerowsecurity
                and t.relowner<>(select oid from pg_roles where rolname='jojo_progress_pilot_rpc')));
            foreach role_name in array array['anon','authenticated'] loop
                passed:=passed+pg_temp.cloud_assert(name||'_no_direct_grant_'||role_name,
                    not has_any_column_privilege(role_name,'jojo_progress_pilot.'||name,'SELECT,INSERT,UPDATE,REFERENCES')
                    and not has_table_privilege(role_name,'jojo_progress_pilot.'||name,'DELETE,TRUNCATE,TRIGGER'));
            end loop;
        end loop;
        foreach function_name in array array['public.progress_sync_read(uuid,uuid,bigint,text)',
            'public.progress_sync_write(uuid,uuid,uuid,jsonb)'] loop
            passed:=passed+pg_temp.cloud_assert('rpc_acl_'||function_name,
                not has_function_privilege('anon',function_name,'EXECUTE')
                and has_function_privilege('authenticated',function_name,'EXECUTE')
                and exists(select 1 from pg_proc where oid=function_name::regprocedure and prosecdef
                    and proowner=(select oid from pg_roles where rolname='jojo_progress_pilot_rpc')
                    and proconfig @> array['search_path=""']::text[]));
        end loop;
        execute 'set local role authenticated'; perform pg_temp.cloud_login('b');
        passed:=passed+pg_temp.cloud_assert('empty_scope',pg_temp.cloud_read()->'rows'='[]'::jsonb);
        op1:=pg_temp.cloud_op(op_a,1,0,900);
        response:=pg_temp.cloud_write(op1);
        passed:=passed+pg_temp.cloud_assert('write_b_applied',response->0->>'status'='applied' and (response->0->>'server_revision')::bigint=1);
        perform pg_temp.cloud_login('c');
        response:=pg_temp.cloud_read();
        passed:=passed+pg_temp.cloud_assert('read_b_from_c',(response->'rows'->0->>'position_ms')::bigint=900);
        response:=pg_temp.cloud_write(pg_temp.cloud_op(op_b,1,0,950));
        passed:=passed+pg_temp.cloud_assert('stale_c_conflict',response->0->>'status'='conflict' and (response->0->>'server_revision')::bigint=1);
        response:=pg_temp.cloud_write(pg_temp.cloud_op(op_c,2,1,100));
        passed:=passed+pg_temp.cloud_assert('explicit_rewind_applied',(response->0->>'server_revision')::bigint=2);
        perform pg_temp.cloud_login('b');
        response:=pg_temp.cloud_write(op1);
        passed:=passed+pg_temp.cloud_assert('lost_ack_duplicate',response->0->>'status'='duplicate' and (response->0->>'server_revision')::bigint=1);
        passed:=passed+pg_temp.cloud_assert('duplicate_keeps_newer_c',(pg_temp.cloud_read()->'rows'->0->>'position_ms')::bigint=100);
        passed:=passed+pg_temp.cloud_denied('changed_body',format('select pg_temp.cloud_write(%L::jsonb)',
            pg_temp.cloud_op(op_a,1,0,800)::text),'P0001');
        passed:=passed+pg_temp.cloud_denied('sequence_regression',format('select pg_temp.cloud_write(%L::jsonb)',
            pg_temp.cloud_op(op_d,1,2,800)::text),'P0001');
        payload:=pg_temp.cloud_op(op_d,2,2,-1);
        passed:=passed+pg_temp.cloud_denied('negative_position',format('select pg_temp.cloud_write(%L::jsonb)',payload::text),'P0001');
        foreach name in array array['title','url','path','owner_id','device_id'] loop
            payload:=jsonb_set(pg_temp.cloud_op(op_d,2,2,200),array['0','payload',name],'"forbidden"'::jsonb,true);
            passed:=passed+pg_temp.cloud_denied('unexpected_'||name,format('select pg_temp.cloud_write(%L::jsonb)',payload::text),'P0001');
        end loop;
        payload:=jsonb_set(pg_temp.cloud_op(op_d,2,2,200),'{0,payload,server_revision}','9');
        passed:=passed+pg_temp.cloud_denied('client_revision',format('select pg_temp.cloud_write(%L::jsonb)',payload::text),'P0001');
        passed:=passed+pg_temp.cloud_denied('empty_batch','select pg_temp.cloud_write(''[]''::jsonb)','P0001');
        passed:=passed+pg_temp.cloud_denied('invalid_cursor','select pg_temp.cloud_read(-1)','P0001');
        passed:=passed+pg_temp.cloud_denied('future_cursor','select pg_temp.cloud_read(999)','P0001');
        perform pg_temp.cloud_login('c');
        response:=pg_temp.cloud_write(pg_temp.cloud_op(op_d,3,2,100,true,'completed'));
        passed:=passed+pg_temp.cloud_assert('logical_hide_applied',response->0->>'status'='applied');
        response:=pg_temp.cloud_read();
        passed:=passed+pg_temp.cloud_assert('hide_keeps_watched',(response->'rows'->0->>'resume_hidden')::boolean
            and response->'rows'->0->>'watch_state'='completed');
        response:=pg_temp.cloud_write(pg_temp.cloud_op(op_e,4,3,0,false,'unseen'));
        passed:=passed+pg_temp.cloud_assert('manual_unseen_applied',(response->0->>'server_revision')::bigint=4);
        passed:=passed+pg_temp.cloud_assert('caught_up_empty',pg_temp.cloud_read(4)->'rows'='[]'::jsonb);
        foreach name in array array['cloud_progress_scopes','cloud_watch_progress','cloud_progress_receipts'] loop
            passed:=passed+pg_temp.cloud_denied('direct_read_'||name,'select * from jojo_progress_pilot.'||name,'42501');
            passed:=passed+pg_temp.cloud_denied('direct_delete_'||name,'delete from jojo_progress_pilot.'||name,'42501');
        end loop;
        -- Deux pages reelles du contrat SQL, sur des cles entierement factices.
        perform pg_temp.cloud_login('b'); payload:='[]'::jsonb;
        for i in 1..201 loop
            payload:=payload || jsonb_set(pg_temp.cloud_op(md5('CLOUD_TEST_OP_'||i)::uuid,100+i,0,10),
                '{0,payload,content_key}',to_jsonb(encode(sha256(convert_to('CLOUD_TEST_KEY_'||i,'UTF8')),'hex')));
            if jsonb_array_length(payload)=50 then perform pg_temp.cloud_write(payload); payload:='[]'::jsonb; end if;
        end loop;
        if jsonb_array_length(payload)>0 then perform pg_temp.cloud_write(payload); end if;
        page_one:=pg_temp.cloud_read();
        page_two:=pg_temp.cloud_read((page_one->>'next_cursor')::bigint);
        passed:=passed+pg_temp.cloud_assert('page_bounded_200',jsonb_array_length(page_one->'rows')=200);
        passed:=passed+pg_temp.cloud_assert('page_has_more',(page_one->>'next_cursor')::bigint<(page_one->>'head_revision')::bigint);
        passed:=passed+pg_temp.cloud_assert('next_page_not_lost',jsonb_array_length(page_two->'rows')=2);
        passed:=passed+pg_temp.cloud_assert('head_reached',(page_two->>'next_cursor')::bigint=(page_two->>'head_revision')::bigint);
        response:=public.progress_sync_read((c->>'profile')::uuid,(c->>'epoch')::uuid,0,
            encode(sha256(convert_to('CLOUD_TEST_KEY_201','UTF8')),'hex'));
        passed:=passed+pg_temp.cloud_assert('target_outside_page',(response->'point'->>'server_revision')::bigint=205
            and (response->>'next_cursor')::bigint<205);

        perform pg_temp.cloud_login('c',true);
        passed:=passed+pg_temp.cloud_denied('anonymous_authenticated_user','select pg_temp.cloud_read()','P0002');
        perform pg_temp.cloud_login('a');
        passed:=passed+pg_temp.cloud_denied('a_excluded_from_scope_read','select pg_temp.cloud_read()','P0002');
        passed:=passed+pg_temp.cloud_denied('a_excluded_from_scope_write',format('select pg_temp.cloud_write(%L::jsonb)',op1::text),'P0002');
        perform pg_temp.cloud_login('outsider');
        passed:=passed+pg_temp.cloud_denied('other_owner_read','select pg_temp.cloud_read()','P0002');
        passed:=passed+pg_temp.cloud_denied('other_owner_write',format('select pg_temp.cloud_write(%L::jsonb)',op1::text),'P0002');
        perform pg_temp.cloud_login('fresh');
        passed:=passed+pg_temp.cloud_denied('unbound_session','select pg_temp.cloud_read()','P0002');
        perform pg_temp.cloud_login('revoked');
        passed:=passed+pg_temp.cloud_denied('missing_session','select pg_temp.cloud_read()','P0002');
        perform pg_temp.cloud_login('c');
        passed:=passed+pg_temp.cloud_denied('other_profile',format('select public.progress_sync_read(%L::uuid,%L::uuid,0)',
            'd8f75684-9d39-4a83-b476-000000000090',c->>'epoch'),'P0002');
        passed:=passed+pg_temp.cloud_denied('other_epoch',format('select public.progress_sync_read(%L::uuid,%L::uuid,0)',
            c->>'profile','d8f75684-9d39-4a83-b476-000000000090'),'P0002');
        execute 'set local role anon'; perform pg_temp.cloud_login('c',true);
        passed:=passed+pg_temp.cloud_denied('anon_read','select pg_temp.cloud_read()','42501');
        passed:=passed+pg_temp.cloud_denied('anon_write',format('select pg_temp.cloud_write(%L::jsonb)',op1::text),'42501');
        execute 'reset role';
        update jojo_progress_pilot.cloud_progress_scopes set enabled=false where key_epoch=(c->>'epoch')::uuid;
        execute 'set local role authenticated'; perform pg_temp.cloud_login('b');
        passed:=passed+pg_temp.cloud_denied('disabled_scope_read','select pg_temp.cloud_read()','P0002');
        passed:=passed+pg_temp.cloud_denied('disabled_scope_write',format('select pg_temp.cloud_write(%L::jsonb)',op1::text),'P0002');
        execute 'reset role';
        update jojo_progress_pilot.cloud_progress_scopes set enabled=true where key_epoch=(c->>'epoch')::uuid;
        update jojo_progress_pilot.pilot_devices set active=false where owner_id=(c->>'owner')::uuid and id=(c->>'c')::uuid;
        execute 'set local role authenticated'; perform pg_temp.cloud_login('c');
        passed:=passed+pg_temp.cloud_denied('inactive_device_read','select pg_temp.cloud_read()','P0002');
        passed:=passed+pg_temp.cloud_denied('inactive_device_write',format('select pg_temp.cloud_write(%L::jsonb)',op1::text),'P0002');
        perform pg_temp.cloud_login('b');
        passed:=passed+pg_temp.cloud_assert('b_still_reads_common_state',jsonb_array_length(pg_temp.cloud_read()->'rows')=200);
        execute 'reset role';
        execute 'set local role jojo_progress_pilot_rpc';
        passed:=passed+pg_temp.cloud_denied('rpc_cannot_delete','delete from jojo_progress_pilot.cloud_watch_progress','42501');
        passed:=passed+pg_temp.cloud_denied('rpc_cannot_open_scope',format(
            'insert into jojo_progress_pilot.cloud_progress_scopes(owner_id,profile_binding_id,key_epoch) values(%L,%L,%L)',
            c->>'owner',c->>'profile',c->>'epoch'),'42501');
        execute 'reset role';
        raise exception using errcode='P7777',message='CLOUD_TEST_ROLLBACK';
    exception when sqlstate 'P7777' then null;
    end;
    -- La sous-transaction a annule TOUTES les fixtures et la desactivation de C.
    passed:=passed+pg_temp.cloud_assert('state_restored',pg_temp.cloud_fingerprint()=initial_state);
    if passed<>64 then raise exception 'CLOUD_VALIDATION_ASSERTION_COUNT: %',passed; end if;
    perform set_config('jojo.cloud_validation_result',jsonb_build_object('status','PASS','assertions',passed,
        'fixtures_rolled_back',true,'network_tested',false,'devices_commanded',false)::text,true);
end;
$tests$;
select current_setting('jojo.cloud_validation_result')::jsonb as validation;
rollback;
