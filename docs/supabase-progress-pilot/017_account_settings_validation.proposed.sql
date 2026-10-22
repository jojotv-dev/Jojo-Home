-- PROPOSITION NON EXECUTEE. Accord distinct requis ; projet de test uniquement.
-- Apres 016. Fixtures sans mot de passe ; aucune commande appareil ni appel reseau.
-- Ne lancer qu'en dehors des publications/reconfigurations de parametres.
-- Ajouter APRES accord dans CETTE transaction avant le premier DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.account_validation_approved = 'APPROVED_017_ROLLBACK_ONLY';
begin;
set transaction isolation level repeatable read;
set local statement_timeout='60s';
set local lock_timeout='3s';
set local idle_in_transaction_session_timeout='30s';
set local row_security=on;
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.account_validation_approved',true) is distinct from 'APPROVED_017_ROLLBACK_ONLY'
       or to_regclass('jojo_account_settings.accounts') is null then
        raise exception 'ACCOUNT_VALIDATION_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid
        join pg_namespace n on n.oid=c.relnamespace where not t.tgisinternal
        and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions'))) then
        raise exception 'ACCOUNT_VALIDATION_EXTERNAL_TRIGGER_REVIEW_REQUIRED';
    end if;
end;
$guard$;

create function pg_temp.account_assert(label text, condition boolean) returns integer
language plpgsql security invoker set search_path='' as $$
begin
    if condition is distinct from true then raise exception 'ACCOUNT_ASSERTION_FAILED: %',label; end if;
    perform set_config('jojo.account_test_count',(coalesce(nullif(current_setting('jojo.account_test_count',true),''),'0')::integer+1)::text,true);
    return 1;
end;
$$;
create function pg_temp.account_denied(label text,command text,expected text) returns integer
language plpgsql security invoker set search_path='' as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=expected then return pg_temp.account_assert(label,true); end if;
        raise exception 'ACCOUNT_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'ACCOUNT_UNEXPECTED_PERMISSION: %',label;
end;
$$;
create function pg_temp.account_ctx() returns jsonb language sql security invoker set search_path='' as $$
    select current_setting('jojo.account_test_ctx')::jsonb;
$$;
create function pg_temp.account_id(name text) returns uuid language sql security invoker set search_path='' as $$
    select (pg_temp.account_ctx()->>name)::uuid;
$$;
create function pg_temp.account_login(which text,anonymous boolean default false) returns void
language plpgsql security invoker set search_path='' as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.account_id(case when which='outsider' then 'outsider' else 'owner' end),
        'session_id',pg_temp.account_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.account_op(key text,value jsonb,scope text default 'account',
    subject uuid default '00000000-0000-0000-0000-000000000000',expected bigint default 0,op uuid default gen_random_uuid())
returns jsonb language sql security invoker set search_path='' as $$
    select jsonb_build_object('operation_id',op,'expected_revision',expected,'scope',scope,'subject',subject,'key',key,'value',value);
$$;
create function pg_temp.account_register(which text) returns jsonb language sql security invoker set search_path='' as $$
    select public.account_settings_register(pg_temp.account_id('device_'||which),repeat(which,64),'androidtv');
$$;
create function pg_temp.account_read(which text,after_revision bigint default 0) returns jsonb language sql security invoker set search_path='' as $$
    select public.account_settings_read(pg_temp.account_id('device_'||which),repeat(which,64),after_revision);
$$;
create function pg_temp.account_write(which text,operations jsonb) returns jsonb language sql security invoker set search_path='' as $$
    select public.account_settings_write(pg_temp.account_id('device_'||which),repeat(which,64),operations);
$$;
create function pg_temp.account_seed(which text) returns jsonb language sql security invoker set search_path='' as $$
    select public.account_settings_seed(pg_temp.account_id('device_'||which),repeat(which,64),pg_temp.account_id('seed_operation'),
        pg_temp.account_ctx()->'seed_ops');
$$;
-- Fingerprints stay server-side; no real profile name or preference is returned.
create function pg_temp.account_fingerprint() returns jsonb language sql security invoker set search_path='' as $$
    select jsonb_build_object(
        'accounts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id)::text,'')) from jojo_account_settings.accounts t),
        'devices',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.id)::text,'')) from jojo_account_settings.installations t),
        'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.id)::text,'')) from jojo_account_settings.profiles t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.scope,t.subject,t.key)::text,'')) from jojo_account_settings.entries t),
        'receipts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.device_id,t.operation_id)::text,'')) from jojo_account_settings.receipts t),
        'pilot_devices',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.id)::text,'')) from jojo_progress_pilot.pilot_devices t),
        'pilot_progress',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.profile_binding_id,t.device_id,t.content_key)::text,'')) from jojo_progress_pilot.pilot_watch_progress t),
        'cloud_progress',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by t.owner_id,t.profile_binding_id,t.key_epoch,t.content_key)::text,'')) from jojo_progress_pilot.cloud_watch_progress t)
    );
$$;
select set_config('jojo.account_test_before',pg_temp.account_fingerprint()::text,true);
savepoint account_fixture;
do $fixture$
declare c jsonb; owner_id uuid:=gen_random_uuid(); outsider_id uuid:=gen_random_uuid(); a uuid:=gen_random_uuid();
    b uuid:=gen_random_uuid(); c_session uuid:=gen_random_uuid(); foreign_session uuid:=gen_random_uuid();
    device_a uuid:=gen_random_uuid(); device_b uuid:=gen_random_uuid(); iptv uuid:=gen_random_uuid(); home uuid:=gen_random_uuid();
begin
    if exists(select 1 from auth.users where id in (owner_id,outsider_id)) then raise exception 'ACCOUNT_FIXTURE_COLLISION'; end if;
    insert into auth.users(id,email,aud,role,is_anonymous) values
        (owner_id,null,'authenticated','authenticated',false),(outsider_id,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values
        (a,owner_id,now()+interval '1 hour'),(b,owner_id,now()+interval '1 hour'),
        (c_session,owner_id,now()+interval '1 hour'),(foreign_session,outsider_id,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session) values
        (owner_id,gen_random_uuid(),a),(outsider_id,gen_random_uuid(),foreign_session);
    c:=jsonb_build_object('owner',owner_id,'outsider',outsider_id,'session_a',a,'session_b',b,'session_c',c_session,
        'session_outsider',foreign_session,'device_a',device_a,'device_b',device_b,'profile_home',home,'profile_iptv',iptv,
        'seed_operation',gen_random_uuid());
    c:=c||jsonb_build_object('seed_ops',jsonb_build_array(
        pg_temp.account_op('profiles.home',jsonb_build_object('name','Profil fictif A','color','#D3A033','avatar','','primary',true),'profile',home),
        pg_temp.account_op('profiles.iptv',jsonb_build_object('name','Profil fictif B','color','#D3A033','avatar','','primary',false),'profile',iptv),
        pg_temp.account_op('home.icon_size','"large"'),pg_temp.account_op('iptv.confirmExit','true'),
        pg_temp.account_op('iptv.audioLanguage','"fr"','profile',iptv),pg_temp.account_op('iptv.bufferSize','"small"','device',device_a)));
    perform set_config('jojo.account_test_ctx',c::text,true);
end;
$fixture$;

set local role authenticated;
select pg_temp.account_login('a');
select pg_temp.account_assert('registration-a',pg_temp.account_register('a')->>'device'=pg_temp.account_id('device_a')::text);
select pg_temp.account_assert('registration-idempotent',pg_temp.account_register('a')->>'schema'='1');
select pg_temp.account_assert('missing-backup-is-not-empty',pg_temp.account_read('a')->>'initialized'='false');
select pg_temp.account_denied('defaults-cannot-seed-by-write',
    $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.icon_size','"small"')))$$,'55000');
select pg_temp.account_login('b');
select pg_temp.account_assert('registration-b',pg_temp.account_register('b')->>'device'=pg_temp.account_id('device_b')::text);
select pg_temp.account_denied('b-cannot-seed', $$select pg_temp.account_seed('b')$$,'42501');
select pg_temp.account_login('a');
select pg_temp.account_assert('reference-seeds-once',pg_temp.account_seed('a')->>'initialized'='true');
select pg_temp.account_assert('seed-replay-idempotent',pg_temp.account_seed('a')->>'initialized'='true');
select pg_temp.account_assert('seed-count',jsonb_array_length(pg_temp.account_read('a')->'rows')=6);
select pg_temp.account_assert('seed-head',pg_temp.account_read('a')->>'head_revision'='6');
select pg_temp.account_denied('second-seed-refused',
    $$select public.account_settings_seed(pg_temp.account_id('device_a'),repeat('a',64),gen_random_uuid(),pg_temp.account_ctx()->'seed_ops')$$,'55000');
select pg_temp.account_assert('name-has-stable-id',exists(select 1 from jsonb_array_elements(pg_temp.account_read('a')->'rows') x
    where x->>'key'='profiles.iptv' and x->>'subject'=pg_temp.account_id('profile_iptv')::text and x->'value'->>'name'='Profil fictif B'));
select pg_temp.account_login('b');
select pg_temp.account_assert('new-device-gets-common-and-profiles',jsonb_array_length(pg_temp.account_read('b')->'rows')=5);
select pg_temp.account_assert('new-device-does-not-get-other-device',not exists(select 1 from jsonb_array_elements(pg_temp.account_read('b')->'rows') x where x->>'scope'='device'));
select pg_temp.account_assert('own-buffer-write',pg_temp.account_write('b',jsonb_build_array(
    pg_temp.account_op('iptv.bufferSize','"large"','device',pg_temp.account_id('device_b'))))->0->>'status'='applied');
select pg_temp.account_assert('own-buffer-read',exists(select 1 from jsonb_array_elements(pg_temp.account_read('b')->'rows') x
    where x->>'key'='iptv.bufferSize' and x->'value'='"large"'));
select pg_temp.account_denied('cannot-overwrite-a-buffer',
    $$select pg_temp.account_write('b',jsonb_build_array(pg_temp.account_op('iptv.bufferSize','"large"','device',pg_temp.account_id('device_a'))))$$,'42501');
select pg_temp.account_denied('cannot-read-a-with-its-uuid',
    $$select public.account_settings_read(pg_temp.account_id('device_a'),repeat('a',64),0)$$,'42501');
select pg_temp.account_denied('cannot-register-a-with-wrong-proof',
    $$select public.account_settings_register(pg_temp.account_id('device_a'),repeat('f',64),'firetv')$$,'42501');
select pg_temp.account_denied('device-key-cannot-be-common',
    $$select pg_temp.account_write('b',jsonb_build_array(pg_temp.account_op('iptv.afrLive','true')))$$,'22023');
select pg_temp.account_denied('unknown-profile',
    $$select pg_temp.account_write('b',jsonb_build_array(pg_temp.account_op('iptv.audioLanguage','"fr"','profile',gen_random_uuid())))$$,'42501');
select pg_temp.account_login('a');
select pg_temp.account_assert('a-keeps-its-buffer',exists(select 1 from jsonb_array_elements(pg_temp.account_read('a')->'rows') x
    where x->>'key'='iptv.bufferSize' and x->'value'='"small"'));
select pg_temp.account_assert('cursor-can-pass-hidden-device-changes',jsonb_array_length(pg_temp.account_read('a',6)->'rows')=0 and pg_temp.account_read('a',6)->>'head_revision'='7');
select pg_temp.account_denied('negative-cursor', $$select pg_temp.account_read('a',-1)$$,'22023');
select pg_temp.account_denied('future-cursor', $$select pg_temp.account_read('a',9223372036854775807)$$,'22023');

-- Exactly the same immutable operation is replayed, then a stale revision is rejected.
select set_config('jojo.account_test_write',jsonb_build_array(pg_temp.account_op('home.icon_size','"small"',expected=>3))::text,true);
select pg_temp.account_assert('cas-applied',(pg_temp.account_write('a',current_setting('jojo.account_test_write')::jsonb)->0->>'status')='applied');
select set_config('jojo.account_test_head',pg_temp.account_read('a')->>'head_revision',true);
select pg_temp.account_assert('replay-applied',(pg_temp.account_write('a',current_setting('jojo.account_test_write')::jsonb)->0->>'status')='applied');
select pg_temp.account_assert('replay-no-new-revision',pg_temp.account_read('a')->>'head_revision'=current_setting('jojo.account_test_head'));
select pg_temp.account_denied('operation-id-cannot-change-body',
    $$select pg_temp.account_write('a',jsonb_set(current_setting('jojo.account_test_write')::jsonb,'{0,value}','"large"'))$$,'22023');
select pg_temp.account_assert('stale-revision-conflict',pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.icon_size','"medium"',expected=>3)))->0->>'status'='conflict');
select pg_temp.account_assert('conflict-keeps-head',pg_temp.account_read('a')->>'head_revision'=current_setting('jojo.account_test_head'));
select pg_temp.account_assert('conflict-keeps-value',exists(select 1 from jsonb_array_elements(pg_temp.account_read('a')->'rows') x
    where x->>'key'='home.icon_size' and x->'value'='"small"'));
select pg_temp.account_assert('reset-is-an-explicit-null',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('home.icon_size','null',expected=>current_setting('jojo.account_test_head')::bigint)))->0->>'status'='applied');
select pg_temp.account_assert('null-is-not-deletion',exists(select 1 from jsonb_array_elements(pg_temp.account_read('a')->'rows') x where x->>'key'='home.icon_size' and x->'value'='null'));
select pg_temp.account_denied('profile-cannot-be-reset-or-deleted',
    $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('profiles.iptv','null','profile',pg_temp.account_id('profile_iptv'),2)))$$,'22023');
select pg_temp.account_assert('rename-preserves-profile-identity',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('profiles.iptv','{"name":"Renomme fictif","color":"#D3A033","avatar":"","primary":false}','profile',pg_temp.account_id('profile_iptv'),2)))->0->>'status'='applied');
select pg_temp.account_assert('future-profile-created',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('profiles.iptv','{"name":"Futur fictif","color":"#D3A033","avatar":"animal:chat","primary":false}','profile',gen_random_uuid())))->0->>'status'='applied');
select pg_temp.account_assert('own-player-menu-order',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('iptv.playerMenuOrder','["audio","subtitles","exit"]','device',pg_temp.account_id('device_a'))))->0->>'status'='applied');
select pg_temp.account_assert('own-external-player-package',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('iptv.externalPlayerPackage','"org.videolan.vlc"','device',pg_temp.account_id('device_a'))))->0->>'status'='applied');
select pg_temp.account_denied('player-menu-no-arbitrary-data', $$select pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('iptv.playerMenuOrder','["https://invalid.test"]','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_denied('external-player-no-url', $$select pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('iptv.externalPlayerPackage','"https://invalid.test"','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_assert('own-home-app-selection',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('home.favorite_apps','["org.videolan.vlc"]','device',pg_temp.account_id('device_a'))))->0->>'status'='applied');
select pg_temp.account_denied('app-favorites-cannot-be-common', $$select pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('home.favorite_apps','["org.videolan.vlc"]')))$$,'22023');
select pg_temp.account_denied('app-favorites-no-secret-data', $$select pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('home.favorite_apps','["https://invalid.test/token"]','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_denied('app-migration-marker-never-exported', $$select pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('home.favorite_apps_version','3','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_assert('emoji-avatar-is-a-catalogue-id',pg_temp.account_write('a',jsonb_build_array(
    pg_temp.account_op('profiles.iptv','{"name":"Emoji fictif","color":"#D3A033","avatar":"emoji:😀","primary":false}','profile',gen_random_uuid())))->0->>'status'='applied');

select pg_temp.account_denied('nas-token-key', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.nas_token','"forbidden"')))$$,'22023');
select pg_temp.account_denied('iptv-url-key', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('iptv.epgSources','"https://invalid.test/token"')))$$,'22023');
select pg_temp.account_denied('parental-pin-key', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('iptv.pinHash','"forbidden"')))$$,'22023');
select pg_temp.account_denied('file-path-key', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.wallpaper_uri','"/private/file"')))$$,'22023');
select pg_temp.account_denied('wrong-value-type', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.home_rows','"2"')))$$,'22023');
select pg_temp.account_denied('wrong-boolean-type', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('iptv.confirmExit','"true"')))$$,'22023');
select pg_temp.account_denied('out-of-range', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.home_rows','1000')))$$,'22023');
select pg_temp.account_denied('unknown-metadata-field', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('profiles.iptv','{"name":"fictif","color":"#D3A033","avatar":"","primary":false,"password":"forbidden"}','profile',gen_random_uuid())))$$,'22023');
select pg_temp.account_denied('avatar-url', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('profiles.iptv','{"name":"fictif","color":"#D3A033","avatar":"https://invalid.test/a","primary":false}','profile',gen_random_uuid())))$$,'22023');
select pg_temp.account_denied('avatar-unknown-id', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('profiles.iptv','{"name":"fictif","color":"#D3A033","avatar":"photo-personnelle.jpg","primary":false}','profile',gen_random_uuid())))$$,'22023');
select pg_temp.account_denied('packages-must-be-identifiers', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.apps_order','["https://invalid.test"]','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_denied('packages-no-duplicates', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.apps_order','["com.example.one","com.example.one"]','device',pg_temp.account_id('device_a'))))$$,'22023');
select pg_temp.account_denied('unexpected-owner-field', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.home_rows','2')||jsonb_build_object('owner_id',pg_temp.account_id('outsider'))))$$,'22023');
select pg_temp.account_denied('empty-batch', $$select pg_temp.account_write('a','[]')$$,'22023');
select pg_temp.account_denied('too-large-batch', $$select pg_temp.account_write('a',(select jsonb_agg(pg_temp.account_op('home.home_rows','2')) from generate_series(1,51)))$$,'22023');
select pg_temp.account_denied('duplicate-operation-in-batch', $$select pg_temp.account_write('a',current_setting('jojo.account_test_write')::jsonb||current_setting('jojo.account_test_write')::jsonb)$$,'22023');

-- One invalid member rolls back every earlier member and its receipt.
select set_config('jojo.account_test_atomic_head',pg_temp.account_read('a')->>'head_revision',true);
select pg_temp.account_denied('whole-batch-atomic', $$select pg_temp.account_write('a',jsonb_build_array(pg_temp.account_op('home.home_rows','2'),pg_temp.account_op('home.smb_password','"forbidden"')))$$,'22023');
select pg_temp.account_assert('failed-batch-did-not-change-head',pg_temp.account_read('a')->>'head_revision'=current_setting('jojo.account_test_atomic_head'));
select pg_temp.account_assert('failed-batch-did-not-publish-first-member',not exists(select 1 from jsonb_array_elements(pg_temp.account_read('a')->'rows') x where x->>'key'='home.home_rows'));

select pg_temp.account_denied('no-direct-select', $$select * from jojo_account_settings.entries$$,'42501');
select pg_temp.account_denied('no-direct-insert', $$insert into jojo_account_settings.entries(owner_id,scope,subject,key,value,revision,last_device)
    values(pg_temp.account_id('owner'),'account','00000000-0000-0000-0000-000000000000','home.home_rows','2',999999,pg_temp.account_id('device_a'))$$,'42501');
select pg_temp.account_denied('no-direct-update', $$update jojo_account_settings.entries set value='null'$$,'42501');
select pg_temp.account_denied('no-direct-delete', $$delete from jojo_account_settings.entries$$,'42501');
select pg_temp.account_denied('no-private-helper', $$select jojo_account_settings.require_device(pg_temp.account_id('device_a'),repeat('a',64))$$,'42501');
select pg_temp.account_denied('no-registration-secret-read', $$select proof_hash from jojo_account_settings.installations$$,'42501');

select pg_temp.account_login('outsider');
select pg_temp.account_denied('other-owner-cannot-read-a', $$select public.account_settings_read(pg_temp.account_id('device_a'),repeat('a',64),0)$$,'42501');
select pg_temp.account_denied('other-owner-cannot-write-a', $$select public.account_settings_write(pg_temp.account_id('device_a'),repeat('a',64),'[]')$$,'42501');
select pg_temp.account_assert('other-owner-may-have-same-device-uuid',public.account_settings_register(pg_temp.account_id('device_a'),repeat('f',64),'firetv')->>'schema'='1');
select pg_temp.account_assert('other-owner-sees-only-own-empty-account',public.account_settings_read(pg_temp.account_id('device_a'),repeat('f',64),0)->>'initialized'='false');
select pg_temp.account_assert('other-owner-cannot-see-profiles',jsonb_array_length(public.account_settings_read(pg_temp.account_id('device_a'),repeat('f',64),0)->'rows')=0);

set local role anon;
select pg_temp.account_login('a',true);
select pg_temp.account_denied('anon-no-rpc-execute', $$select pg_temp.account_read('a')$$,'42501');
set local role authenticated;
select pg_temp.account_denied('anonymous-authenticated-user-refused', $$select pg_temp.account_read('a')$$,'42501');
select pg_temp.account_login('a');
select set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.account_id('outsider'),'session_id',pg_temp.account_id('session_a'),'is_anonymous',false)::text,true);
select pg_temp.account_denied('jwt-owner-session-mismatch', $$select pg_temp.account_read('a')$$,'42501');
select set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.account_id('owner'),'session_id',gen_random_uuid(),'is_anonymous',false)::text,true);
select pg_temp.account_denied('nonexistent-session', $$select pg_temp.account_read('a')$$,'42501');

reset role;
update jojo_account_settings.installations set active=false where owner_id=pg_temp.account_id('owner') and id=pg_temp.account_id('device_a');
set local role authenticated;
select pg_temp.account_login('a');
select pg_temp.account_denied('revoked-device-cannot-read', $$select pg_temp.account_read('a')$$,'42501');
select pg_temp.account_denied('revoked-device-cannot-reregister', $$select pg_temp.account_register('a')$$,'42501');
reset role;
update jojo_account_settings.installations set active=true where owner_id=pg_temp.account_id('owner') and id=pg_temp.account_id('device_a');
update auth.sessions set not_after=now()-interval '1 second' where id=pg_temp.account_id('session_b');
set local role authenticated;
select pg_temp.account_login('b');
select pg_temp.account_denied('expired-auth-session', $$select pg_temp.account_read('b')$$,'42501');
select pg_temp.account_login('c');
select pg_temp.account_assert('proof-allows-same-installation-new-session',public.account_settings_register(pg_temp.account_id('device_b'),repeat('b',64),'firetv')->>'device'=pg_temp.account_id('device_b')::text);
select pg_temp.account_assert('new-session-reads-own-installation',public.account_settings_read(pg_temp.account_id('device_b'),repeat('b',64),0)->>'initialized'='true');

-- RLS itself, exercised as the limited role behind the RPC (not only the RPC guards).
reset role;
-- Test-only SET permission: the existing savepoint/final ROLLBACK undo this grant.
grant jojo_account_settings_rpc to postgres with set true;
set local role jojo_account_settings_rpc;
select pg_temp.account_login('a');
select pg_temp.account_assert('rls-other-owner-hidden',(select count(*)=0 from jojo_account_settings.accounts where owner_id=pg_temp.account_id('outsider')));
select pg_temp.account_assert('rls-other-device-hidden',(select count(*)=0 from jojo_account_settings.entries where scope='device' and subject=pg_temp.account_id('device_b')));
select pg_temp.account_denied('rpc-role-no-delete', $$delete from jojo_account_settings.entries where owner_id=pg_temp.account_id('owner')$$,'42501');
select pg_temp.account_denied('rls-unknown-profile-refused', $$insert into jojo_account_settings.entries(owner_id,scope,subject,key,value,revision,last_device)
    values(pg_temp.account_id('owner'),'profile',gen_random_uuid(),'iptv.audioLanguage','"fr"',999999,pg_temp.account_id('device_a'))$$,'42501');
select pg_temp.account_denied('rls-other-device-insert-refused', $$insert into jojo_account_settings.entries(owner_id,scope,subject,key,value,revision,last_device)
    values(pg_temp.account_id('owner'),'device',pg_temp.account_id('device_b'),'iptv.afrLive','true',999999,pg_temp.account_id('device_a'))$$,'42501');
with changed as (
    update jojo_account_settings.accounts set head_revision=head_revision+1 where owner_id=pg_temp.account_id('outsider') returning 1)
select pg_temp.account_assert('rpc-role-cannot-update-other-owner',(select count(*)=0 from changed));
reset role;
select pg_temp.account_assert('forced-rls-all-tables',(select count(*)=6 and bool_and(c.relrowsecurity and c.relforcerowsecurity)
    from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='jojo_account_settings' and c.relkind='r'));
select pg_temp.account_assert('rpc-role-is-limited',(select not rolcanlogin and not rolsuper and not rolbypassrls from pg_roles where rolname='jojo_account_settings_rpc'));
select pg_temp.account_assert('authenticated-does-not-inherit-rpc',not pg_has_role('authenticated','jojo_account_settings_rpc','MEMBER'));
select pg_temp.account_assert('anon-does-not-inherit-rpc',not pg_has_role('anon','jojo_account_settings_rpc','MEMBER'));
select pg_temp.account_assert('rpc-has-no-auth-schema-access',not has_schema_privilege('jojo_account_settings_rpc','auth','USAGE'));
select pg_temp.account_assert('rpc-functions-safe-owner-and-path',(select count(*)=4 and bool_and(p.prosecdef and p.proconfig @> array['search_path=""'])
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname in
    ('account_settings_register','account_settings_read','account_settings_seed','account_settings_write')
    and p.proowner=(select oid from pg_roles where rolname='jojo_account_settings_rpc')));
select jsonb_build_object('assertions_before_rollback',current_setting('jojo.account_test_count')::integer,'status','CHECKS_PASSED') as checks;
rollback to savepoint account_fixture;
select pg_temp.account_assert('all-fixtures-rolled-back',pg_temp.account_fingerprint()=current_setting('jojo.account_test_before')::jsonb);
select pg_temp.account_assert('postgres-rpc-options-restored',not exists(select 1 from pg_auth_members m
    where m.roleid='jojo_account_settings_rpc'::regrole and m.member='postgres'::regrole and (m.set_option or m.inherit_option)));
select jsonb_build_object('status','PASS','fixtures_rolled_back',true,'network_or_devices_tested',false) as diagnostic;
rollback;
