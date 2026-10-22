-- VALIDATION NON EXECUTEE : apres 025, avec accord distinct ; ROLLBACK obligatoire.
-- Deux comptes Auth fictifs sans mot de passe, trois sessions/installations et profils fictifs.
-- Aucun appareil commande, aucune video lue, aucune donnee existante modifiee durablement.
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_026 = 'YES';
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_026',true) is distinct from 'YES'
       or to_regclass('jojo_account_settings.resume_masks') is null then
        raise exception 'HOME_MASK_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where not t.tgisinternal and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions'))) then
        raise exception 'HOME_MASK_EXTERNAL_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;
create temporary sequence mask_test_counter;
grant usage on sequence pg_temp.mask_test_counter to authenticated;
create function pg_temp.m_assert(label text,ok boolean) returns integer language plpgsql as $$
begin
    if ok is distinct from true then raise exception 'HOME_MASK_ASSERTION_FAILED: %',label; end if;
    perform nextval('pg_temp.mask_test_counter');
    return 1;
end;
$$;
create function pg_temp.m_denied(label text,command text,code text) returns integer language plpgsql as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=code then return pg_temp.m_assert(label,true); end if;
        raise exception 'HOME_MASK_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'HOME_MASK_UNEXPECTED_SUCCESS: %',label;
end;
$$;
create function pg_temp.m_id(name text) returns uuid language sql as $$
    select (current_setting('jojo.m_fixture')::jsonb->>name)::uuid;
$$;
create function pg_temp.m_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.m_id(case when which='c' then 'other' else 'owner' end),
        'session_id',pg_temp.m_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.m_sync(which text,ops jsonb default '[]',after_revision bigint default 0) returns jsonb language sql as $$
    select public.account_resume_masks_sync(pg_temp.m_id('device_'||which),repeat(which,64),after_revision,ops);
$$;
create function pg_temp.m_op(key text,stamp bigint,profile uuid default null) returns jsonb language sql as $$
    select jsonb_build_object('profile',coalesce(profile,pg_temp.m_id('profile')),'key',key,'stamp',stamp);
$$;
create function pg_temp.m_stamp(which text,key text,profile uuid default null) returns bigint language sql as $$
    select (x->>'stamp')::bigint from jsonb_array_elements(pg_temp.m_sync(which)->'rows') x
        where x->>'key'=key and (x->>'profile')::uuid=coalesce(profile,pg_temp.m_id('profile'));
$$;
create function pg_temp.m_fingerprint() returns jsonb language sql as $$
    select jsonb_build_object(
        'heads',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.resume_mask_heads t),
        'masks',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,profile,key)::text,'')) from jojo_account_settings.resume_masks t),
        'accounts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.accounts t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
        'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.profiles t),
        'installations',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.installations t),
        'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions));
$$;
select pg_temp.m_assert('no-anonymous-execute',not has_function_privilege('anon','public.account_resume_masks_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.m_assert('authenticated-execute',has_function_privilege('authenticated','public.account_resume_masks_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.m_assert('no-direct-read',not has_table_privilege('authenticated','jojo_account_settings.resume_masks','SELECT'));
select pg_temp.m_assert('no-direct-write',not has_table_privilege('authenticated','jojo_account_settings.resume_masks','INSERT'));
select pg_temp.m_assert('forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.resume_masks'::regclass));
select pg_temp.m_assert('rpc-role-no-bypass',(select not rolbypassrls and not rolsuper from pg_roles where rolname='jojo_account_settings_rpc'));
select set_config('jojo.m_before',pg_temp.m_fingerprint()::text,true);
savepoint mask_fixtures;
do $fixture$
declare own uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid();
    p uuid:=gen_random_uuid(); q uuid:=gen_random_uuid(); iptv uuid:=gen_random_uuid(); outsider uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(own,null,'authenticated','authenticated',false),(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),(c,other,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session,initialized,seed_operation,seed_hash)
        values(own,gen_random_uuid(),a,true,gen_random_uuid(),repeat('a',64)),(other,gen_random_uuid(),c,true,gen_random_uuid(),repeat('b',64));
    insert into jojo_account_settings.profiles(owner_id,id,domain,primary_profile)
        values(own,p,'home',true),(own,q,'home',false),(own,iptv,'iptv',false),(other,outsider,'home',true);
    perform set_config('jojo.m_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_c',c,
        'device_a',gen_random_uuid(),'device_b',gen_random_uuid(),'device_c',gen_random_uuid(),'profile',p,'second',q,'iptv',iptv,'outsider',outsider)::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.m_login('a');
select public.account_settings_register(pg_temp.m_id('device_a'),repeat('a',64),'androidtv');
select pg_temp.m_login('b');
select public.account_settings_register(pg_temp.m_id('device_b'),repeat('b',64),'firetv');
select pg_temp.m_login('c');
select public.account_settings_register(pg_temp.m_id('device_c'),repeat('c',64),'androidtv');
select pg_temp.m_login('a');
select pg_temp.m_assert('initial-empty',pg_temp.m_sync('a')->'rows'='[]'::jsonb);
select pg_temp.m_assert('first-removal',(pg_temp.m_sync('a',jsonb_build_array(pg_temp.m_op(repeat('a',64),100)))->>'head_revision')::bigint=1);
select pg_temp.m_login('b');
select pg_temp.m_assert('other-tv-sees-removal',pg_temp.m_stamp('b',repeat('a',64))=100);
select pg_temp.m_assert('replay-is-idempotent',(pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),100)))->>'head_revision')::bigint=1);
select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('b',64),200)));
select pg_temp.m_assert('different-video-retained',pg_temp.m_stamp('b',repeat('a',64))=100 and pg_temp.m_stamp('b',repeat('b',64))=200);
select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),-90)));
select pg_temp.m_assert('stale-replay-cannot-restore',pg_temp.m_stamp('b',repeat('a',64))=100);
select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),-101)));
select pg_temp.m_assert('explicit-later-replay',pg_temp.m_stamp('b',repeat('a',64))=-101);
select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),101)));
select pg_temp.m_assert('removal-wins-tie',pg_temp.m_stamp('b',repeat('a',64))=101);
select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),104,pg_temp.m_id('second'))));
select pg_temp.m_assert('profiles-are-independent',pg_temp.m_stamp('b',repeat('a',64))=101 and pg_temp.m_stamp('b',repeat('a',64),pg_temp.m_id('second'))=104);
select pg_temp.m_denied('other-account-profile',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),300,pg_temp.m_id('outsider'))))$q$,'42501');
select pg_temp.m_denied('iptv-profile-forbidden',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),300,pg_temp.m_id('iptv'))))$q$,'42501');
select pg_temp.m_denied('wrong-proof',$q$select public.account_resume_masks_sync(pg_temp.m_id('device_b'),repeat('f',64),0,'[]')$q$,'42501');
select pg_temp.m_denied('wrong-device',$q$select public.account_resume_masks_sync(gen_random_uuid(),repeat('b',64),0,'[]')$q$,'42501');
select pg_temp.m_denied('raw-url-forbidden',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op('https://private',300)))$q$,'22023');
select pg_temp.m_denied('unknown-field',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),300)||' {"title":"private"}'::jsonb))$q$,'22023');
select pg_temp.m_denied('zero-clock',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),0)))$q$,'22023');
select pg_temp.m_denied('overflow-clock',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('a',64),9223372036854775807)))$q$,'22023');
select pg_temp.m_denied('string-clock',$q$select pg_temp.m_sync('b',jsonb_build_array(jsonb_set(pg_temp.m_op(repeat('a',64),300),'{stamp}','"300"'))) $q$,'22023');
select pg_temp.m_denied('negative-cursor',$q$select pg_temp.m_sync('b','[]',-1)$q$,'22023');
select pg_temp.m_denied('future-cursor',$q$select pg_temp.m_sync('b','[]',9223372036854775807)$q$,'22023');
select pg_temp.m_denied('oversized-batch',$q$select pg_temp.m_sync('b',(select jsonb_agg(pg_temp.m_op(lpad(to_hex(i),64,'0'),300)) from generate_series(1,51) i))$q$,'22023');
select pg_temp.m_denied('atomic-batch',$q$select pg_temp.m_sync('b',jsonb_build_array(pg_temp.m_op(repeat('c',64),300),pg_temp.m_op('invalid',300)))$q$,'22023');
select pg_temp.m_assert('failed-batch-left-no-removal',pg_temp.m_stamp('b',repeat('c',64)) is null);
select pg_temp.m_login('c');
select pg_temp.m_assert('other-account-sees-nothing',pg_temp.m_sync('c')->'rows'='[]'::jsonb);
select pg_temp.m_denied('other-session-cannot-use-device',$q$select pg_temp.m_sync('a')$q$,'42501');
select pg_temp.m_login('a',true);
select pg_temp.m_denied('anonymous-auth-denied',$q$select pg_temp.m_sync('a')$q$,'42501');
select pg_temp.m_login('a');
do $pagination$
declare i integer; first_page jsonb; last_page jsonb;
begin
    for i in 0..10 loop
        perform pg_temp.m_sync('a',(select jsonb_agg(pg_temp.m_op(lpad(to_hex(n),64,'0'),500)) from generate_series(i*50+1,i*50+50) n));
    end loop;
    first_page:=pg_temp.m_sync('a');
    perform pg_temp.m_assert('first-page-bounded',jsonb_array_length(first_page->'rows')=500);
    perform pg_temp.m_assert('more-pages-visible',(first_page->>'next_cursor')::bigint<(first_page->>'head_revision')::bigint);
    last_page:=pg_temp.m_sync('a','[]',(first_page->>'next_cursor')::bigint);
    perform pg_temp.m_assert('last-page-advances',(last_page->>'next_cursor')::bigint=(last_page->>'head_revision')::bigint);
    perform pg_temp.m_assert('unchanged-poll-empty',pg_temp.m_sync('a','[]',(last_page->>'next_cursor')::bigint)->'rows'='[]'::jsonb);
end;
$pagination$;
reset role;
rollback to savepoint mask_fixtures;
select pg_temp.m_assert('state-restored',pg_temp.m_fingerprint()=current_setting('jojo.m_before')::jsonb);
select jsonb_build_object('assertions',(select last_value from pg_temp.mask_test_counter),'existing_state_restored',true,'committed_test_writes',0) as diagnostic;
rollback;
