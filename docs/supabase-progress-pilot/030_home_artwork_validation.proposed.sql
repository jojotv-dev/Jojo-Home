-- VALIDATION NON EXECUTEE : apres 029, avec accord distinct ; ROLLBACK obligatoire.
-- Deux comptes Auth fictifs sans mot de passe et trois sessions/installations fictives.
-- Aucun appareil commande, aucune video lue, aucune image reelle importe.
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_030 = 'YES';
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_030',true) is distinct from 'YES'
       or to_regclass('jojo_account_settings.artwork_choices') is null then
        raise exception 'HOME_ARTWORK_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where not t.tgisinternal and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions'))) then
        raise exception 'HOME_ARTWORK_EXTERNAL_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;
create temporary sequence artwork_test_counter;
grant usage on sequence pg_temp.artwork_test_counter to authenticated;
create function pg_temp.a_assert(label text,ok boolean) returns integer language plpgsql as $$
begin
    if ok is distinct from true then raise exception 'HOME_ARTWORK_ASSERTION_FAILED: %',label; end if;
    perform nextval('pg_temp.artwork_test_counter'); return 1;
end;
$$;
create function pg_temp.a_denied(label text,command text,code text) returns integer language plpgsql as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=code then return pg_temp.a_assert(label,true); end if;
        raise exception 'HOME_ARTWORK_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'HOME_ARTWORK_UNEXPECTED_SUCCESS: %',label;
end;
$$;
create function pg_temp.a_id(name text) returns uuid language sql as $$
    select (current_setting('jojo.a_fixture')::jsonb->>name)::uuid;
$$;
create function pg_temp.a_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.a_id(case when which='c' then 'other' else 'owner' end),
        'session_id',pg_temp.a_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.a_sync(which text,ops jsonb default '[]',after_revision bigint default 0) returns jsonb language sql as $$
    select public.account_home_artwork_sync(pg_temp.a_id('device_'||which),repeat(which,64),after_revision,ops);
$$;
create function pg_temp.a_op(key text,stamp bigint,image text default 'Fixture.jpg',tmdb_id integer default 0,media_type text default '') returns jsonb language sql as $$
    select jsonb_build_object('key',key,'image',image,'stamp',stamp,'tmdb_id',tmdb_id,'media_type',media_type);
$$;
create function pg_temp.a_choice(which text,key text) returns jsonb language sql as $$
    select x from jsonb_array_elements(pg_temp.a_sync(which)->'rows') x where x->>'key'=key;
$$;
create function pg_temp.a_fingerprint() returns jsonb language sql as $$
    select jsonb_build_object(
        'heads',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.artwork_heads t),
        'choices',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,key)::text,'')) from jojo_account_settings.artwork_choices t),
        'accounts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.accounts t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
        'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.profiles t),
        'installations',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.installations t),
        'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions));
$$;
select pg_temp.a_assert('anon-cannot-execute',not has_function_privilege('anon','public.account_home_artwork_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.a_assert('authenticated-can-execute',has_function_privilege('authenticated','public.account_home_artwork_sync(uuid,text,bigint,jsonb)','EXECUTE'));
select pg_temp.a_assert('no-direct-read',not has_table_privilege('authenticated','jojo_account_settings.artwork_choices','SELECT'));
select pg_temp.a_assert('no-direct-write',not has_table_privilege('authenticated','jojo_account_settings.artwork_choices','INSERT'));
select pg_temp.a_assert('choices-forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.artwork_choices'::regclass));
select pg_temp.a_assert('heads-forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.artwork_heads'::regclass));
select pg_temp.a_assert('rpc-role-no-bypass',(select not rolbypassrls and not rolsuper and not rolcanlogin from pg_roles where rolname='jojo_account_settings_rpc'));
select set_config('jojo.a_before',pg_temp.a_fingerprint()::text,true);
savepoint artwork_fixtures;
do $fixture$
declare own uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(own,null,'authenticated','authenticated',false),(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),(c,other,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session,initialized,seed_operation,seed_hash)
        values(own,gen_random_uuid(),a,true,gen_random_uuid(),repeat('a',64)),(other,gen_random_uuid(),c,true,gen_random_uuid(),repeat('b',64));
    perform set_config('jojo.a_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_c',c,
        'device_a',gen_random_uuid(),'device_b',gen_random_uuid(),'device_c',gen_random_uuid())::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.a_login('a');
select public.account_settings_register(pg_temp.a_id('device_a'),repeat('a',64),'androidtv');
select pg_temp.a_login('b');
select public.account_settings_register(pg_temp.a_id('device_b'),repeat('b',64),'firetv');
select pg_temp.a_login('c');
select public.account_settings_register(pg_temp.a_id('device_c'),repeat('c',64),'androidtv');
select pg_temp.a_login('a');
select pg_temp.a_assert('initial-empty',pg_temp.a_sync('a')->'rows'='[]'::jsonb);
select pg_temp.a_assert('first-choice',(pg_temp.a_sync('a',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',100,'w500:French.jpg',17,'tv')))->>'head_revision')::bigint=1);
select pg_temp.a_login('b');
select pg_temp.a_assert('other-tv-exact-image',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='w500:French.jpg');
select pg_temp.a_assert('exact-programme-identity',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'tmdb_id'='17'
    and pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'media_type'='tv');
select pg_temp.a_assert('replay-idempotent',(pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',100,'w500:French.jpg',17,'tv')))->>'head_revision')::bigint=1);
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_hero_artwork_a',101,'Wide.jpg',17,'tv')));
select pg_temp.a_assert('portrait-and-hero-independent',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='w500:French.jpg'
    and pg_temp.a_choice('b','home_videos_programme_hero_artwork_a')->>'image'='Wide.jpg');
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',99,'Old.jpg',18,'tv')));
select pg_temp.a_assert('old-device-cannot-restore-old-image',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='w500:French.jpg');
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',102,'New.jpg',19,'tv')));
select pg_temp.a_assert('new-choice-wins',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='New.jpg');
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',102,'Z.jpg',20,'tv')));
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',102,'A.jpg',21,'tv')));
select pg_temp.a_assert('clock-tie-deterministic',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='Z.jpg');
select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',103,'')));
select pg_temp.a_assert('explicit-reset',pg_temp.a_choice('b','home_videos_programme_portrait_artwork_a')->>'image'='');
select pg_temp.a_denied('private-url',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200,'https://private/image')))$q$,'22023');
select pg_temp.a_denied('local-file',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200,'file:///private.jpg')))$q$,'22023');
select pg_temp.a_denied('raw-path-key',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('smb://private/video.mkv',200)))$q$,'22023');
select pg_temp.a_denied('extra-personal-metadata',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200)||'{"title":"private"}'::jsonb))$q$,'22023');
select pg_temp.a_denied('invalid-type',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200,'A.jpg',17,'person')))$q$,'22023');
select pg_temp.a_denied('reset-with-id',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200,'',17,'tv')))$q$,'22023');
select pg_temp.a_denied('overflow-id',$q$select pg_temp.a_sync('b',jsonb_build_array(jsonb_set(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200),'{tmdb_id}','2147483648')))$q$,'22023');
select pg_temp.a_denied('zero-clock',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',0)))$q$,'22023');
select pg_temp.a_denied('overflow-clock',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_portrait_artwork_a',9223372036854775807)))$q$,'22023');
select pg_temp.a_denied('string-clock',$q$select pg_temp.a_sync('b',jsonb_build_array(jsonb_set(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200),'{stamp}','"200"')))$q$,'22023');
select pg_temp.a_denied('null-key',$q$select pg_temp.a_sync('b',jsonb_build_array(jsonb_set(pg_temp.a_op('home_videos_programme_portrait_artwork_a',200),'{key}','null')))$q$,'22023');
select pg_temp.a_denied('negative-cursor',$q$select pg_temp.a_sync('b','[]',-1)$q$,'22023');
select pg_temp.a_denied('future-cursor',$q$select pg_temp.a_sync('b','[]',9223372036854775807)$q$,'22023');
select pg_temp.a_denied('oversized-batch',$q$select pg_temp.a_sync('b',(select jsonb_agg(pg_temp.a_op('home_videos_programme_hero_artwork_'||to_hex(i),300)) from generate_series(1,51) i))$q$,'22023');
select pg_temp.a_denied('wrong-proof',$q$select public.account_home_artwork_sync(pg_temp.a_id('device_b'),repeat('f',64),0,'[]')$q$,'42501');
select pg_temp.a_denied('wrong-device',$q$select public.account_home_artwork_sync(gen_random_uuid(),repeat('b',64),0,'[]')$q$,'42501');
select pg_temp.a_denied('atomic-batch',$q$select pg_temp.a_sync('b',jsonb_build_array(pg_temp.a_op('home_videos_programme_hero_artwork_ffff',300),pg_temp.a_op('invalid',300)))$q$,'22023');
select pg_temp.a_assert('failed-batch-left-no-choice',pg_temp.a_choice('b','home_videos_programme_hero_artwork_ffff') is null);
select pg_temp.a_login('c');
select pg_temp.a_assert('other-account-isolated',pg_temp.a_sync('c')->'rows'='[]'::jsonb);
select pg_temp.a_denied('other-session-device',$q$select pg_temp.a_sync('a')$q$,'42501');
select pg_temp.a_login('a',true);
select pg_temp.a_denied('anonymous-auth',$q$select pg_temp.a_sync('a')$q$,'42501');
select pg_temp.a_login('a');
do $pagination$
declare i integer; first_page jsonb; last_page jsonb;
begin
    for i in 0..10 loop
        perform pg_temp.a_sync('a',(select jsonb_agg(pg_temp.a_op('home_videos_series_episode_artwork_'||to_hex(n),500))
            from generate_series(i*50+1,i*50+50) n));
    end loop;
    first_page:=pg_temp.a_sync('a');
    perform pg_temp.a_assert('page-bounded',jsonb_array_length(first_page->'rows')=500);
    perform pg_temp.a_assert('more-pages-visible',(first_page->>'next_cursor')::bigint<(first_page->>'head_revision')::bigint);
    last_page:=pg_temp.a_sync('a','[]',(first_page->>'next_cursor')::bigint);
    perform pg_temp.a_assert('last-page-advances',(last_page->>'next_cursor')::bigint=(last_page->>'head_revision')::bigint);
    perform pg_temp.a_assert('unchanged-poll-empty',pg_temp.a_sync('a','[]',(last_page->>'next_cursor')::bigint)->'rows'='[]'::jsonb);
end;
$pagination$;
reset role;
rollback to savepoint artwork_fixtures;
select pg_temp.a_assert('state-restored',pg_temp.a_fingerprint()=current_setting('jojo.a_before')::jsonb);
select jsonb_build_object('assertions',(select last_value from pg_temp.artwork_test_counter),'existing_state_restored',true,'committed_test_writes',0) as diagnostic;
rollback;
