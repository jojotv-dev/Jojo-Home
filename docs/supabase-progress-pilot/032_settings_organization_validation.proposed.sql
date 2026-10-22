-- VALIDATION NON EXECUTEE : accord distinct requis apres SQL 031.
-- Quatre sessions/installations fictives, dont trois TV du meme compte et un autre compte.
-- Toutes les ecritures sont annulees et l'etat initial est compare.
-- Ajouter APRES accord, apres BEGIN et avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_032 = 'YES';
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_032',true) is distinct from 'YES'
       or to_regprocedure('public.account_settings_read_v2(uuid,text,bigint)') is null then
        raise exception 'SETTINGS_ORGANIZATION_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where not t.tgisinternal and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions'))) then
        raise exception 'SETTINGS_ORGANIZATION_EXTERNAL_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;
create temporary sequence settings_organization_counter;
grant usage on sequence pg_temp.settings_organization_counter to authenticated;
create function pg_temp.s_assert(label text,ok boolean) returns integer language plpgsql as $$
begin
    if ok is distinct from true then raise exception 'SETTINGS_ORGANIZATION_ASSERTION_FAILED: %',label; end if;
    perform nextval('pg_temp.settings_organization_counter'); return 1;
end;
$$;
create function pg_temp.s_denied(label text,command text,code text default '22023') returns integer language plpgsql as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=code then return pg_temp.s_assert(label,true); end if;
        raise exception 'SETTINGS_ORGANIZATION_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'SETTINGS_ORGANIZATION_UNEXPECTED_SUCCESS: %',label;
end;
$$;
create function pg_temp.s_id(name text) returns uuid language sql as $$
    select (current_setting('jojo.s_fixture')::jsonb->>name)::uuid;
$$;
create function pg_temp.s_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.s_id(case when which='c' then 'other' else 'owner' end),
        'session_id',pg_temp.s_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.s_page(which text,legacy boolean default false,after_revision bigint default 0) returns jsonb language plpgsql as $$
begin
    if legacy then return public.account_settings_read(pg_temp.s_id('device_'||which),repeat(which,64),after_revision); end if;
    return public.account_settings_read_v2(pg_temp.s_id('device_'||which),repeat(which,64),after_revision);
end;
$$;
create function pg_temp.s_op(key text,value jsonb,revision bigint default 0,scope text default 'account',subject uuid default '00000000-0000-0000-0000-000000000000') returns jsonb language sql as $$
    select jsonb_build_object('operation_id',gen_random_uuid(),'scope',scope,'subject',subject,'key',key,'value',value,'expected_revision',revision);
$$;
create function pg_temp.s_write(which text,ops jsonb) returns jsonb language sql as $$
    select public.account_settings_write(pg_temp.s_id('device_'||which),repeat(which,64),ops);
$$;
create function pg_temp.s_entry(which text,setting text) returns jsonb language sql as $$
    select x from jsonb_array_elements(pg_temp.s_page(which)->'rows') x where x->>'key'=setting;
$$;
create function pg_temp.s_organization(name text default 'Informations') returns jsonb language sql as $$
    select jsonb_build_object('organization:'||repeat('a',64)||':hidden','true',
        'organization:'||repeat('b',64)||':groupName',name,
        'organization:'||repeat('a',64)||':groupOrder:LIVE','["12","34"]');
$$;
create function pg_temp.s_fingerprint() returns jsonb language sql as $$
    select jsonb_build_object(
        'accounts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.accounts t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
        'receipts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,device_id,operation_id)::text,'')) from jojo_account_settings.receipts t),
        'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.profiles t),
        'installations',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.installations t),
        'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions));
$$;
select pg_temp.s_assert('anon-no-execute',not has_function_privilege('anon','public.account_settings_read_v2(uuid,text,bigint)','EXECUTE'));
select pg_temp.s_assert('authenticated-execute',has_function_privilege('authenticated','public.account_settings_read_v2(uuid,text,bigint)','EXECUTE'));
select pg_temp.s_assert('entries-private',not has_table_privilege('authenticated','jojo_account_settings.entries','SELECT'));
select pg_temp.s_assert('catalog-private',not has_table_privilege('authenticated','jojo_account_settings.catalog','SELECT'));
select pg_temp.s_assert('helper-private',not has_function_privilege('authenticated','jojo_account_settings.organization_valid(jsonb)','EXECUTE'));
select pg_temp.s_assert('forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.entries'::regclass));
select pg_temp.s_assert('rpc-no-bypass',(select not rolbypassrls and not rolsuper and not rolcanlogin and not rolinherit from pg_roles where rolname='jojo_account_settings_rpc'));
select pg_temp.s_assert('catalog-three-new-keys',(select count(*)=3 from jojo_account_settings.catalog where key in ('home.home_section_order','iptv.sourceOrder','iptv.organization')));
select set_config('jojo.s_before',pg_temp.s_fingerprint()::text,true);
savepoint settings_organization_fixtures;
do $fixture$
declare own uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid(); d uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(own,null,'authenticated','authenticated',false),(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),
        (c,other,now()+interval '1 hour'),(d,own,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session,initialized,seed_operation,seed_hash)
        values(own,gen_random_uuid(),a,true,gen_random_uuid(),repeat('a',64)),(other,gen_random_uuid(),c,true,gen_random_uuid(),repeat('b',64));
    perform set_config('jojo.s_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_c',c,'session_d',d,
        'device_a',gen_random_uuid(),'device_b',gen_random_uuid(),'device_c',gen_random_uuid(),'device_d',gen_random_uuid())::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.s_login('a');
select public.account_settings_register(pg_temp.s_id('device_a'),repeat('a',64),'androidtv');
select pg_temp.s_login('b');
select public.account_settings_register(pg_temp.s_id('device_b'),repeat('b',64),'firetv');
select pg_temp.s_login('d');
select public.account_settings_register(pg_temp.s_id('device_d'),repeat('d',64),'androidtv');
select pg_temp.s_login('c');
select public.account_settings_register(pg_temp.s_id('device_c'),repeat('c',64),'androidtv');
select pg_temp.s_login('a');
select pg_temp.s_write('a',jsonb_build_array(
    pg_temp.s_op('home.home_section_order','["resumes","apps","movies"]'),
    pg_temp.s_op('iptv.sourceOrder',jsonb_build_array(gen_random_uuid(),gen_random_uuid())),
    pg_temp.s_op('iptv.organization',pg_temp.s_organization()),
    pg_temp.s_op('home.icon_size','"large"'),
    pg_temp.s_op('iptv.bufferSize','"small"',0,'device',pg_temp.s_id('device_a'))));
select pg_temp.s_login('b');
select pg_temp.s_assert('second-tv-receives-organization',pg_temp.s_entry('b','iptv.organization')->'value'=pg_temp.s_organization());
select pg_temp.s_assert('second-tv-receives-section-order',pg_temp.s_entry('b','home.home_section_order')->'value'='["resumes","apps","movies"]'::jsonb);
select pg_temp.s_assert('second-tv-receives-source-order',jsonb_array_length(pg_temp.s_entry('b','iptv.sourceOrder')->'value')=2);
select pg_temp.s_assert('player-setting-not-shared',pg_temp.s_entry('b','iptv.bufferSize') is null);
select pg_temp.s_assert('legacy-reader-filters-new-keys',not exists(select 1 from jsonb_array_elements(pg_temp.s_page('b',true)->'rows') x
    where x->>'key' in ('home.home_section_order','iptv.sourceOrder','iptv.organization')));
select pg_temp.s_assert('legacy-reader-still-works',exists(select 1 from jsonb_array_elements(pg_temp.s_page('b',true)->'rows') x where x->>'key'='home.icon_size'));
select pg_temp.s_assert('readers-have-same-head',pg_temp.s_page('b')->'head_revision'=pg_temp.s_page('b',true)->'head_revision');
select pg_temp.s_assert('stale-write-conflicts',pg_temp.s_write('b',jsonb_build_array(pg_temp.s_op('iptv.organization','null')))->0->>'status'='conflict');
select pg_temp.s_assert('conflict-keeps-choice',pg_temp.s_entry('b','iptv.organization')->'value'=pg_temp.s_organization());
select pg_temp.s_write('b',jsonb_build_array(pg_temp.s_op('iptv.organization',pg_temp.s_organization('Actualites'),
    (pg_temp.s_entry('b','iptv.organization')->>'revision')::bigint)));
select pg_temp.s_login('d');
select pg_temp.s_assert('third-tv-receives-second-tv',pg_temp.s_entry('d','iptv.organization')->'value'=pg_temp.s_organization('Actualites'));
select pg_temp.s_write('d',jsonb_build_array(pg_temp.s_op('iptv.organization','null',
    (pg_temp.s_entry('d','iptv.organization')->>'revision')::bigint)));
select pg_temp.s_login('a');
select pg_temp.s_assert('first-tv-receives-third-tv-reset',pg_temp.s_entry('a','iptv.organization')->'value'='null'::jsonb);
select pg_temp.s_assert('device-setting-retained',pg_temp.s_entry('a','iptv.bufferSize')->'value'='"small"'::jsonb);
select pg_temp.s_assert('unchanged-poll-empty',pg_temp.s_page('a',false,(pg_temp.s_page('a')->>'head_revision')::bigint)->'rows'='[]'::jsonb);
select pg_temp.s_denied('invalid-home-order',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('home.home_section_order','["apps","apps"]')))$q$);
select pg_temp.s_denied('private-provider-url',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.sourceOrder','["https://private.invalid"]')))$q$);
select pg_temp.s_denied('private-name-url',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',pg_temp.s_organization('https://private.invalid'))))$q$);
select pg_temp.s_denied('parental-override-not-shared',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',jsonb_build_object('organization:'||repeat('a',64)||':blocked','true'))))$q$);
select pg_temp.s_denied('hardware-override-not-shared',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',jsonb_build_object('organization:'||repeat('a',64)||':externalPlayerMode','all'))))$q$);
select pg_temp.s_denied('wrong-value-type',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',jsonb_build_object('organization:'||repeat('a',64)||':hidden',true))))$q$);
select pg_temp.s_denied('duplicate-group-order',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',jsonb_build_object('organization:'||repeat('a',64)||':groupOrder:LIVE','["12","12"]'))))$q$);
select pg_temp.s_denied('private-item-url',$q$select pg_temp.s_write('a',jsonb_build_array(pg_temp.s_op('iptv.organization',jsonb_build_object('organization:'||repeat('a',64)||':itemOrder','["https://private.invalid"]'))))$q$);
select pg_temp.s_denied('wrong-proof',$q$select public.account_settings_read_v2(pg_temp.s_id('device_a'),repeat('0',64),0)$q$,'42501');
select pg_temp.s_login('c');
select pg_temp.s_assert('other-account-empty',pg_temp.s_page('c')->'rows'='[]'::jsonb);
select pg_temp.s_denied('other-account-cannot-read',$q$select public.account_settings_read_v2(pg_temp.s_id('device_a'),repeat('a',64),0)$q$,'42501');
select pg_temp.s_login('a',true);
select pg_temp.s_denied('anonymous-user-rejected',$q$select pg_temp.s_page('a')$q$,'42501');
reset role;
rollback to savepoint settings_organization_fixtures;
select pg_temp.s_assert('existing-state-restored',pg_temp.s_fingerprint()=current_setting('jojo.s_before')::jsonb);
select jsonb_build_object('assertions',(select last_value from pg_temp.settings_organization_counter),
    'existing_state_restored',true,'committed_test_writes',0) as diagnostic;
rollback;
