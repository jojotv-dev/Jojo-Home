-- VALIDATION NON EXECUTEE : apres 033, avec accord distinct ; ROLLBACK obligatoire.
-- Deux comptes Auth sans mot de passe, quatre sessions/installations fictives, aucun appareil commande.
-- Les charges base64 sont fictives : la cryptographie et les episodes sont testes dans les tests clients.
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_034 = 'YES';
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_034',true) is distinct from 'YES'
       or to_regclass('jojo_account_settings.netflix_resumes') is null then
        raise exception 'NETFLIX_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where not t.tgisinternal and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions'))) then
        raise exception 'NETFLIX_EXTERNAL_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;
create temporary sequence netflix_test_counter;
grant usage on sequence pg_temp.netflix_test_counter to authenticated;
create function pg_temp.n_assert(label text,ok boolean) returns integer language plpgsql as $$
begin
    if ok is distinct from true then raise exception 'NETFLIX_ASSERTION_FAILED: %',label; end if;
    perform nextval('pg_temp.netflix_test_counter'); return 1;
end;
$$;
create function pg_temp.n_denied(label text,command text,code text) returns integer language plpgsql as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=code then return pg_temp.n_assert(label,true); end if;
        raise exception 'NETFLIX_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'NETFLIX_UNEXPECTED_SUCCESS: %',label;
end;
$$;
create function pg_temp.n_id(name text) returns uuid language sql as $$
    select (current_setting('jojo.n_fixture')::jsonb->>name)::uuid;
$$;
create function pg_temp.n_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.n_id(case when which='d' then 'other' else 'owner' end),
        'session_id',pg_temp.n_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.n_sync(which text,ops jsonb default '[]',after_revision bigint default 0) returns jsonb language sql as $$
    select public.account_netflix_resumes_sync(pg_temp.n_id('device_'||which),repeat(which,64),after_revision,ops);
$$;
create function pg_temp.n_op(which text,stamp bigint,key text default repeat('1',64),sealed text default repeat('QUJD',16)) returns jsonb language sql as $$
    select jsonb_build_object('key',key,'stamp',stamp,'writer',pg_temp.n_id('device_'||which),'sealed',sealed);
$$;
create function pg_temp.n_choice(which text,key text default repeat('1',64)) returns jsonb language sql as $$
    select x from jsonb_array_elements(pg_temp.n_sync(which)->'rows') x where x->>'key'=key;
$$;
create function pg_temp.n_fingerprint() returns jsonb language sql as $$
    select jsonb_build_object(
        'heads',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.netflix_heads t),
        'resumes',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,key)::text,'')) from jojo_account_settings.netflix_resumes t),
        'accounts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.accounts t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
        'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.profiles t),
        'installations',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.installations t),
        'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions));
$$;
select pg_temp.n_assert('anon-cannot-execute',not has_function_privilege('anon','public.account_netflix_resumes_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.n_assert('authenticated-can-execute',has_function_privilege('authenticated','public.account_netflix_resumes_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.n_assert('no-direct-read',not has_table_privilege('authenticated','jojo_account_settings.netflix_resumes','SELECT'));
select pg_temp.n_assert('no-direct-write',not has_table_privilege('authenticated','jojo_account_settings.netflix_resumes','INSERT'));
select pg_temp.n_assert('resumes-forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.netflix_resumes'::regclass));
select pg_temp.n_assert('heads-forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.netflix_heads'::regclass));
select pg_temp.n_assert('rpc-role-no-bypass',(select not rolbypassrls and not rolsuper and not rolcanlogin from pg_roles where rolname='jojo_account_settings_rpc'));
select pg_temp.n_assert('no-public-create',not has_schema_privilege('jojo_account_settings_rpc','public','CREATE'));
select set_config('jojo.n_before',pg_temp.n_fingerprint()::text,true);
savepoint netflix_fixtures;
do $fixture$
declare own uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid(); d uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(own,null,'authenticated','authenticated',false),(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),
        (c,own,now()+interval '1 hour'),(d,other,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session,initialized,seed_operation,seed_hash)
        values(own,gen_random_uuid(),a,true,gen_random_uuid(),repeat('a',64)),(other,gen_random_uuid(),d,true,gen_random_uuid(),repeat('d',64));
    perform set_config('jojo.n_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_c',c,'session_d',d,
        'device_a',gen_random_uuid(),'device_b',gen_random_uuid(),'device_c',gen_random_uuid(),'device_d',gen_random_uuid())::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.n_login('a');
select public.account_settings_register(pg_temp.n_id('device_a'),repeat('a',64),'androidtv');
select pg_temp.n_login('b');
select public.account_settings_register(pg_temp.n_id('device_b'),repeat('b',64),'firetv');
select pg_temp.n_login('c');
select public.account_settings_register(pg_temp.n_id('device_c'),repeat('c',64),'androidtv');
select pg_temp.n_login('d');
select public.account_settings_register(pg_temp.n_id('device_d'),repeat('d',64),'firetv');
select pg_temp.n_login('a');
select pg_temp.n_assert('initial-empty',pg_temp.n_sync('a')->'rows'='[]'::jsonb);
select pg_temp.n_assert('first-snapshot',(pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',100)))->>'head_revision')::bigint=1);
select pg_temp.n_assert('immutable-retry',(pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',100)))->>'head_revision')::bigint=1);
select pg_temp.n_denied('same-clock-modified-cipher',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',100,repeat('1',64),repeat('REVG',16))))$q$,'22023');
select pg_temp.n_login('b');
select pg_temp.n_assert('second-tv-receives',pg_temp.n_choice('b')->>'sealed'=repeat('QUJD',16));
select pg_temp.n_assert('second-tv-can-update',(pg_temp.n_sync('b',jsonb_build_array(pg_temp.n_op('b',200,repeat('1',64),repeat('REVG',16))))->>'head_revision')::bigint=2);
select pg_temp.n_login('c');
select pg_temp.n_assert('third-tv-receives-latest',pg_temp.n_choice('c')->>'sealed'=repeat('REVG',16));
select pg_temp.n_assert('third-tv-can-update',(pg_temp.n_sync('c',jsonb_build_array(pg_temp.n_op('c',300,repeat('1',64),repeat('R0hJ',16))))->>'head_revision')::bigint=3);
select pg_temp.n_login('a');
select pg_temp.n_assert('first-tv-receives-third',pg_temp.n_choice('a')->>'sealed'=repeat('R0hJ',16));
select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',99)));
select pg_temp.n_assert('old-clock-cannot-resurrect',pg_temp.n_choice('a')->>'sealed'=repeat('R0hJ',16));
select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',301,repeat('2',64))));
select pg_temp.n_assert('separate-profile-rows',jsonb_array_length(pg_temp.n_sync('a')->'rows')=2);
select pg_temp.n_assert('opaque-wire-only',(select array_agg(k order by k)=array['key','revision','sealed','stamp','writer']
    from jsonb_object_keys(pg_temp.n_choice('a')) k));
select pg_temp.n_denied('raw-profile-key',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400,'fictional-profile')))$q$,'22023');
select pg_temp.n_denied('extra-clear-title',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400)||'{"title":"fixture"}'::jsonb))$q$,'22023');
select pg_temp.n_denied('arbitrary-url-payload',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400,repeat('1',64),'https://private/fixture')))$q$,'22023');
select pg_temp.n_denied('writer-spoof',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('b',400)))$q$,'22023');
select pg_temp.n_denied('zero-clock',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',0)))$q$,'22023');
select pg_temp.n_denied('overflow-clock',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',9223372036854775807)))$q$,'22023');
select pg_temp.n_denied('string-clock',$q$select pg_temp.n_sync('a',jsonb_build_array(jsonb_set(pg_temp.n_op('a',400),'{stamp}','"400"')))$q$,'22023');
select pg_temp.n_denied('null-key',$q$select pg_temp.n_sync('a',jsonb_build_array(jsonb_set(pg_temp.n_op('a',400),'{key}','null')))$q$,'22023');
select pg_temp.n_denied('empty-cipher',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400,repeat('1',64),'')))$q$,'22023');
select pg_temp.n_denied('oversized-cipher',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400,repeat('1',64),repeat('QUJD',22000))))$q$,'22023');
select pg_temp.n_denied('negative-cursor',$q$select pg_temp.n_sync('a','[]',-1)$q$,'22023');
select pg_temp.n_denied('future-cursor',$q$select pg_temp.n_sync('a','[]',9223372036854775807)$q$,'22023');
select pg_temp.n_denied('two-writes-atomic-rejection',$q$select pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',400,repeat('3',64)),pg_temp.n_op('a',400,repeat('4',64))))$q$,'22023');
select pg_temp.n_assert('rejected-batch-left-no-row',pg_temp.n_choice('a',repeat('3',64)) is null);
select pg_temp.n_denied('wrong-proof',$q$select public.account_netflix_resumes_sync(pg_temp.n_id('device_a'),repeat('f',64),0,'[]')$q$,'42501');
select pg_temp.n_denied('wrong-device',$q$select public.account_netflix_resumes_sync(gen_random_uuid(),repeat('a',64),0,'[]')$q$,'42501');
select pg_temp.n_login('d');
select pg_temp.n_assert('other-owner-isolated',pg_temp.n_sync('d')->'rows'='[]'::jsonb);
select pg_temp.n_denied('foreign-device-denied',$q$select pg_temp.n_sync('a')$q$,'42501');
select pg_temp.n_login('a',true);
select pg_temp.n_denied('anonymous-auth-denied',$q$select pg_temp.n_sync('a')$q$,'42501');
select pg_temp.n_login('a');
do $pagination$
declare i integer; first_page jsonb; last_page jsonb;
begin
    for i in 1..23 loop
        perform pg_temp.n_sync('a',jsonb_build_array(pg_temp.n_op('a',500,lpad(to_hex(i+100),64,'0'))));
    end loop;
    first_page:=pg_temp.n_sync('a');
    perform pg_temp.n_assert('page-bounded',jsonb_array_length(first_page->'rows')=20);
    perform pg_temp.n_assert('more-pages-visible',(first_page->>'next_cursor')::bigint<(first_page->>'head_revision')::bigint);
    last_page:=pg_temp.n_sync('a','[]',(first_page->>'next_cursor')::bigint);
    perform pg_temp.n_assert('last-page-advances',(last_page->>'next_cursor')::bigint=(last_page->>'head_revision')::bigint);
    perform pg_temp.n_assert('unchanged-poll-empty',pg_temp.n_sync('a','[]',(last_page->>'next_cursor')::bigint)->'rows'='[]'::jsonb);
end;
$pagination$;
reset role;
update auth.sessions set not_after=now()-interval '1 minute' where id=pg_temp.n_id('session_a');
set local role authenticated;
select pg_temp.n_denied('expired-session-denied',$q$select pg_temp.n_sync('a')$q$,'42501');
reset role;
rollback to savepoint netflix_fixtures;
select pg_temp.n_assert('state-restored',pg_temp.n_fingerprint()=current_setting('jojo.n_before')::jsonb);
select jsonb_build_object('assertions',(select last_value from pg_temp.netflix_test_counter),'existing_state_restored',true,'committed_test_writes',0) as diagnostic;
rollback;
