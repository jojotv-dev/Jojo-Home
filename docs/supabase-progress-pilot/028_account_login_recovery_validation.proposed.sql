-- VALIDATION NON EXECUTEE : accord distinct apres 027, avant mise a jour des TV.
-- Utilise l'UUID du proprietaire existant (singleton du pilote), mais seulement
-- des sessions/installations/appareils et une generation de cle FICTIFS.
-- Aucun mot de passe, jeton, secret reel ou session reelle n'est lu ni modifie.
-- Le coffre du compte doit encore etre vide. Toutes les fixtures, y compris le
-- secret Vault fictif, sont annulees et l'etat initial est compare avant ROLLBACK.
-- Aucun appareil commande. Ajouter APRES accord, dans la transaction avant DO :
-- SET LOCAL jojo.account_project_ref='jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_028='YES';
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_028',true) is distinct from 'YES'
       or to_regclass('jojo_account_settings.recovery_keys') is null then
        raise exception 'ACCOUNT_RECOVERY_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings)<>1
       or exists(select 1 from jojo_account_settings.recovery_keys)
       or exists(select 1 from jojo_progress_pilot.pilot_devices where slot>=29)
       or exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
           where not t.tgisinternal and (n.nspname='jojo_account_settings'
               or n.nspname='jojo_progress_pilot' and c.relname in ('pilot_devices','cloud_progress_scopes')
               or n.nspname='auth' and c.relname in ('users','sessions') or n.nspname='vault')) then
        raise exception 'ACCOUNT_RECOVERY_VALIDATION_PREFLIGHT_FAILED';
    end if;
end;
$guard$;
create temporary sequence recovery_test_counter;
grant usage on sequence pg_temp.recovery_test_counter to authenticated,anon;
create function pg_temp.r_assert(label text,ok boolean) returns integer language plpgsql as $$
begin
    if ok is distinct from true then raise exception 'ACCOUNT_RECOVERY_ASSERTION_FAILED: %',label; end if;
    perform nextval('pg_temp.recovery_test_counter'); return 1;
end;
$$;
create function pg_temp.r_denied(label text,command text,code text) returns integer language plpgsql as $$
begin
    begin execute command;
    exception when others then
        if sqlstate=code then return pg_temp.r_assert(label,true); end if;
        raise exception 'ACCOUNT_RECOVERY_WRONG_REJECTION: %, %',label,sqlstate;
    end;
    raise exception 'ACCOUNT_RECOVERY_UNEXPECTED_SUCCESS: %',label;
end;
$$;
create function pg_temp.r_id(name text) returns uuid language sql as $$
    select (current_setting('jojo.r_fixture')::jsonb->>name)::uuid;
$$;
create function pg_temp.r_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin
    perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.r_id(case when which='x' then 'other' else 'owner' end),
        'session_id',pg_temp.r_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true);
end;
$$;
create function pg_temp.r_key() returns text language sql as $$
    select encode(decode(repeat('01',32),'hex'),'base64');
$$;
create function pg_temp.r_recover(which text,backup boolean default false) returns jsonb language sql as $$
    select public.account_sync_recover(pg_temp.r_id('installation_'||which),repeat(which,64),pg_temp.r_id('device_'||which),
        pg_temp.r_id('home'),pg_temp.r_id('nonce'),case when backup then pg_temp.r_id('epoch') else null::uuid end,
        case when backup then pg_temp.r_key() else null::text end);
$$;
create function pg_temp.r_fingerprint() returns jsonb language sql as $$
    select jsonb_build_object(
        'keys',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.recovery_keys t),
        'vault_count',(select count(*) from vault.secrets),
        'enrollments',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,device)::text,'')) from jojo_account_settings.enrollments t),
        'devices',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_progress_pilot.pilot_devices t),
        'scopes',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,profile_binding_id,key_epoch)::text,'')) from jojo_progress_pilot.cloud_progress_scopes t),
        'progress',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,profile_binding_id,key_epoch,content_key)::text,'')) from jojo_progress_pilot.cloud_watch_progress t),
        'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
        'installations',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.installations t),
        'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions));
$$;
select pg_temp.r_assert('no-anonymous-execute',not has_function_privilege('anon','public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text)','EXECUTE'));
select pg_temp.r_assert('authenticated-execute',has_function_privilege('authenticated','public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text)','EXECUTE'));
select pg_temp.r_assert('helper-not-public',not has_function_privilege('authenticated','jojo_account_settings.recover_account(uuid,text,uuid,uuid,uuid,uuid,text)','EXECUTE'));
select pg_temp.r_assert('no-direct-key-read',not has_table_privilege('authenticated','jojo_account_settings.recovery_keys','SELECT'));
select pg_temp.r_assert('no-direct-key-write',not has_table_privilege('authenticated','jojo_account_settings.recovery_keys','INSERT'));
select pg_temp.r_assert('no-vault-grant-to-rpc',not has_table_privilege('jojo_account_settings_rpc','vault.decrypted_secrets','SELECT'));
select pg_temp.r_assert('key-table-forced-rls',(select relrowsecurity and relforcerowsecurity from pg_class where oid='jojo_account_settings.recovery_keys'::regclass));
select pg_temp.r_assert('wrapper-has-limited-owner',(select not r.rolcanlogin and not r.rolsuper and not r.rolbypassrls
    from pg_proc p join pg_roles r on r.oid=p.proowner where p.oid='public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text)'::regprocedure));
select set_config('jojo.r_before',pg_temp.r_fingerprint()::text,true);
savepoint recovery_fixtures;

do $fixture$
declare
    own uuid; other uuid:=gen_random_uuid(); home uuid; profile uuid;
    sa uuid:=gen_random_uuid(); sb uuid:=gen_random_uuid(); sc uuid:=gen_random_uuid(); sx uuid:=gen_random_uuid(); sb2 uuid:=gen_random_uuid();
    ia uuid:=gen_random_uuid(); ib uuid:=gen_random_uuid(); ic uuid:=gen_random_uuid(); ix uuid:=gen_random_uuid();
    da uuid:=gen_random_uuid(); db uuid:=gen_random_uuid(); dc uuid:=gen_random_uuid(); epoch uuid:=gen_random_uuid();
begin
    select owner_id,id into strict own,profile from jojo_progress_pilot.pilot_profile_bindings;
    select id into strict home from jojo_account_settings.profiles where owner_id=own and domain='home' and primary_profile;
    if not exists(select 1 from jojo_account_settings.accounts where owner_id=own and initialized) then
        raise exception 'ACCOUNT_RECOVERY_EXISTING_SETTINGS_REQUIRED';
    end if;
    insert into auth.users(id,email,aud,role,is_anonymous) values(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(sa,own,now()+interval '1 hour'),(sb,own,now()+interval '1 hour'),
        (sc,own,now()+interval '1 hour'),(sx,other,now()+interval '1 hour'),(sb2,own,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session) values(other,gen_random_uuid(),sx);
    insert into jojo_account_settings.installations(owner_id,id,proof_hash,auth_session_id,platform) values
        (own,ia,encode(sha256(convert_to(repeat('a',64),'UTF8')),'hex'),sa,'androidtv'),
        (own,ib,encode(sha256(convert_to(repeat('b',64),'UTF8')),'hex'),sb,'firetv'),
        (own,ic,encode(sha256(convert_to(repeat('c',64),'UTF8')),'hex'),sc,'androidtv'),
        (other,ix,encode(sha256(convert_to(repeat('d',64),'UTF8')),'hex'),sx,'androidtv');
    insert into jojo_progress_pilot.pilot_devices(owner_id,id,slot,auth_session_id,active) values(own,da,29,sa,true);
    insert into jojo_account_settings.enrollments(owner_id,device,installation,profile,request,approver_session)
        values(own,da,ia,profile,null,sa);
    insert into jojo_progress_pilot.cloud_progress_scopes(owner_id,profile_binding_id,key_epoch,enabled,allowed_devices)
        values(own,profile,epoch,true,array[da]);
    perform set_config('jojo.r_fixture',jsonb_build_object('owner',own,'other',other,'home',home,'profile',profile,'epoch',epoch,
        'session_a',sa,'session_b',sb,'session_c',sc,'session_x',sx,'session_b2',sb2,
        'installation_a',ia,'installation_b',ib,'installation_c',ic,'installation_x',ix,
        'device_a',da,'device_b',db,'device_c',dc,'nonce',gen_random_uuid())::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.r_login('b');
select pg_temp.r_denied('empty-coffer-does-not-invent-key',$q$select pg_temp.r_recover('b')$q$,'55000');
select pg_temp.r_denied('new-device-cannot-seed-key',$q$select pg_temp.r_recover('b',true)$q$,'42501');
select pg_temp.r_denied('direct-table-read-denied',$q$select * from jojo_account_settings.recovery_keys$q$,'42501');
select pg_temp.r_denied('private-helper-denied',$q$select jojo_account_settings.recover_account(pg_temp.r_id('installation_b'),repeat('b',64),pg_temp.r_id('device_b'),pg_temp.r_id('home'),gen_random_uuid())$q$,'42501');
select pg_temp.r_login('a');
do $backup$
declare reply jsonb;
begin
    reply:=pg_temp.r_recover('a',true);
    perform pg_temp.r_assert('trusted-backup-returns-existing-key',reply->>'secret'=pg_temp.r_key());
    perform pg_temp.r_assert('response-bound-to-account',reply->>'owner'=pg_temp.r_id('owner')::text);
    perform pg_temp.r_assert('response-bound-to-session',reply->>'session'=pg_temp.r_id('session_a')::text);
    perform pg_temp.r_assert('response-bound-to-installation',reply->>'installation'=pg_temp.r_id('installation_a')::text);
    perform pg_temp.r_assert('response-bound-to-fresh-request',reply->>'nonce'=pg_temp.r_id('nonce')::text);
    perform pg_temp.r_assert('response-has-no-extra-fields',(select count(*) from jsonb_object_keys(reply))=14);
    perform pg_temp.r_assert('backup-is-idempotent',pg_temp.r_recover('a',true)->>'secret'=reply->>'secret');
end;
$backup$;
select pg_temp.r_denied('wrong-key-cannot-replace-vault',$q$select public.account_sync_recover(pg_temp.r_id('installation_a'),repeat('a',64),pg_temp.r_id('device_a'),pg_temp.r_id('home'),gen_random_uuid(),pg_temp.r_id('epoch'),encode(decode(repeat('02',32),'hex'),'base64'))$q$,'40900');
select pg_temp.r_denied('wrong-proof-denied',$q$select public.account_sync_recover(pg_temp.r_id('installation_a'),repeat('f',64),pg_temp.r_id('device_a'),pg_temp.r_id('home'),gen_random_uuid())$q$,'42501');
select pg_temp.r_denied('wrong-home-profile-denied',$q$select public.account_sync_recover(pg_temp.r_id('installation_a'),repeat('a',64),pg_temp.r_id('device_a'),gen_random_uuid(),gen_random_uuid())$q$,'42501');
select pg_temp.r_denied('null-nonce-denied',$q$select public.account_sync_recover(pg_temp.r_id('installation_a'),repeat('a',64),pg_temp.r_id('device_a'),pg_temp.r_id('home'),null)$q$,'22023');
select pg_temp.r_denied('epoch-without-backup-denied',$q$select public.account_sync_recover(pg_temp.r_id('installation_a'),repeat('a',64),pg_temp.r_id('device_a'),pg_temp.r_id('home'),gen_random_uuid(),pg_temp.r_id('epoch'))$q$,'22023');
select pg_temp.r_login('b');
select pg_temp.r_denied('cannot-take-another-device-identity',$q$select public.account_sync_recover(pg_temp.r_id('installation_b'),repeat('b',64),pg_temp.r_id('device_a'),pg_temp.r_id('home'),gen_random_uuid())$q$,'42501');
do $new_device$
declare reply jsonb;
begin
    reply:=pg_temp.r_recover('b');
    perform pg_temp.r_assert('account-login-gets-same-key',reply->>'secret'=pg_temp.r_key());
    perform pg_temp.r_assert('account-login-keeps-cloud-generation',reply->>'key_epoch'=pg_temp.r_id('epoch')::text);
    perform pg_temp.r_assert('new-device-has-dynamic-slot',(reply->>'slot')::integer between 4 and 28);
    perform pg_temp.r_assert('reply-does-not-require-another-tv',reply->'device_c'='null'::jsonb);
    perform pg_temp.r_assert('new-device-can-read-progress',public.progress_sync_read(pg_temp.r_id('profile'),pg_temp.r_id('epoch'),0) is not null);
    perform pg_temp.r_assert('repeat-login-is-idempotent',pg_temp.r_recover('b')->>'secret'=reply->>'secret');
end;
$new_device$;
select pg_temp.r_denied('one-installation-cannot-claim-second-device',$q$select public.account_sync_recover(pg_temp.r_id('installation_b'),repeat('b',64),gen_random_uuid(),pg_temp.r_id('home'),gen_random_uuid())$q$,'42501');
select pg_temp.r_login('x');
select pg_temp.r_denied('other-account-cannot-use-installation',$q$select pg_temp.r_recover('b')$q$,'42501');
select pg_temp.r_login('a',true);
select pg_temp.r_denied('anonymous-auth-account-denied',$q$select pg_temp.r_recover('a')$q$,'42501');
set local role anon;
select pg_temp.r_denied('anon-role-cannot-call-rpc',$q$select pg_temp.r_recover('a')$q$,'42501');
reset role;
select pg_temp.r_assert('one-fake-key-only',(select count(*) from jojo_account_settings.recovery_keys where owner_id=pg_temp.r_id('owner'))=1);
select pg_temp.r_assert('vault-key-is-encrypted-at-rest',(select s.secret<>pg_temp.r_key() from vault.secrets s
    join jojo_account_settings.recovery_keys k on k.secret_id=s.id where k.owner_id=pg_temp.r_id('owner')));
select pg_temp.r_assert('new-enrollment-has-no-code-request',(select request is null from jojo_account_settings.enrollments
    where owner_id=pg_temp.r_id('owner') and device=pg_temp.r_id('device_b')));

-- Reconnexion de LA MEME installation avec une nouvelle session Auth fictive.
set local role authenticated;
select pg_temp.r_login('b2');
select public.account_settings_register(pg_temp.r_id('installation_b'),repeat('b',64),'firetv');
select pg_temp.r_assert('new-session-recovers-without-a-tv',pg_temp.r_recover('b')->>'session'=pg_temp.r_id('session_b2')::text);
select pg_temp.r_assert('new-session-reads-progress',public.progress_sync_read(pg_temp.r_id('profile'),pg_temp.r_id('epoch'),0) is not null);
select pg_temp.r_login('b');
select pg_temp.r_denied('old-session-loses-device-proof',$q$select pg_temp.r_recover('b')$q$,'42501');
select pg_temp.r_denied('old-session-loses-progress-access',$q$select public.progress_sync_read(pg_temp.r_id('profile'),pg_temp.r_id('epoch'),0)$q$,'P0002');
reset role;
select pg_temp.r_assert('reconnect-did-not-add-another-device',(select count(*) from jojo_progress_pilot.pilot_devices
    where owner_id=pg_temp.r_id('owner') and id=pg_temp.r_id('device_b'))=1);
update jojo_progress_pilot.pilot_devices set active=false where owner_id=pg_temp.r_id('owner') and id=pg_temp.r_id('device_b');
set local role authenticated;
select pg_temp.r_login('b2');
select pg_temp.r_denied('revoked-device-stays-revoked',$q$select pg_temp.r_recover('b')$q$,'42501');
reset role;
-- Une revocation de l'espace cloud interdit egalement la restitution de la cle.
update jojo_progress_pilot.cloud_progress_scopes set enabled=false where owner_id=pg_temp.r_id('owner') and key_epoch=pg_temp.r_id('epoch');
set local role authenticated;
select pg_temp.r_login('a');
select pg_temp.r_denied('disabled-scope-denies-recovery',$q$select pg_temp.r_recover('a')$q$,'42501');
select pg_temp.r_denied('disabled-scope-denies-backup',$q$select pg_temp.r_recover('a',true)$q$,'42501');
reset role;
rollback to savepoint recovery_fixtures;
select pg_temp.r_assert('all-existing-state-restored',pg_temp.r_fingerprint()=current_setting('jojo.r_before')::jsonb);
select jsonb_build_object('assertions',(select last_value from pg_temp.recovery_test_counter),
    'existing_state_restored',true,'committed_test_writes',0,'real_key_used',false) as diagnostic;
rollback;
