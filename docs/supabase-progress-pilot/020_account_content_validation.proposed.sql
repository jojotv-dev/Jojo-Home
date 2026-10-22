-- PROPOSITION DE VALIDATION NON EXECUTEE. Apres 018, avec accord distinct.
-- Deux utilisateurs Auth fictifs SANS mot de passe, trois sessions, deux appareils.
-- Aucun utilisateur, historique ou planning existant n'est modifie. ROLLBACK final obligatoire.
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_setting('jojo.approved_020',true) is distinct from 'YES' then raise exception 'APPROVAL_020_REQUIRED'; end if;
    if exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
        where not t.tgisinternal and (n.nspname='jojo_account_settings' or n.nspname='auth' and c.relname in ('users','sessions')))
        then raise exception 'EXTERNAL_TRIGGER_REVIEW_REQUIRED'; end if;
end;
$guard$;
create function pg_temp.c_assert(label text,ok boolean) returns integer language plpgsql as $$
begin if ok is distinct from true then raise exception 'CONTENT_ASSERTION_FAILED: %',label; end if;
    perform set_config('jojo.c_count',(coalesce(nullif(current_setting('jojo.c_count',true),''),'0')::integer+1)::text,true); return 1; end;
$$;
create function pg_temp.c_denied(label text,command text,code text) returns integer language plpgsql as $$
begin begin execute command; exception when others then
    if sqlstate=code then return pg_temp.c_assert(label,true); end if;
    raise exception 'CONTENT_WRONG_REJECTION: %, %',label,sqlstate; end;
    raise exception 'CONTENT_UNEXPECTED_SUCCESS: %',label; end;
$$;
create function pg_temp.c_id(name text) returns uuid language sql as $$ select (current_setting('jojo.c_fixture')::jsonb->>name)::uuid $$;
create function pg_temp.c_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.c_id(case when which='x' then 'other' else 'owner' end),
    'session_id',pg_temp.c_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true); end;
$$;
create function pg_temp.c_read(which text,after_revision bigint default 0) returns jsonb language sql as $$
 select public.account_content_read(pg_temp.c_id('device_'||which),repeat(which,64),after_revision)
$$;
create function pg_temp.c_write(which text,op jsonb) returns jsonb language sql as $$
 select public.account_content_write(pg_temp.c_id('device_'||which),repeat(which,64),jsonb_build_array(op))
$$;
create function pg_temp.c_op(kind text,key text,expected bigint default 0,creator text default '',reference text default '',
    base bigint default 0,state text default '',gone boolean default false) returns jsonb language sql as $$
 select jsonb_build_object('operation_id',gen_random_uuid(),'expected_revision',expected,'value',jsonb_build_object(
    'profile',pg_temp.c_id('profile'),'kind',kind,'key',key,'creator',creator,'reference',reference,
    'base_revision',base,'status',state,'deleted',gone,'sealed',case when gone then '' else repeat('A',40) end))
$$;
create function pg_temp.c_fingerprint() returns jsonb language sql as $$
 select jsonb_build_object('heads',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id)::text,'')) from jojo_account_settings.content_heads t),
    'rows',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,revision)::text,'')) from jojo_account_settings.content_rows t),
    'receipts',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,device,operation)::text,'')) from jojo_account_settings.content_receipts t),
    'accounts',(select count(*) from jojo_account_settings.accounts),'users',(select count(*) from auth.users),'sessions',(select count(*) from auth.sessions))
$$;
select set_config('jojo.c_before',pg_temp.c_fingerprint()::text,true);
savepoint content_fixtures;
do $fixture$
declare own uuid:=gen_random_uuid(); other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); x uuid:=gen_random_uuid(); p uuid:=gen_random_uuid();
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(own,null,'authenticated','authenticated',false),(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),(x,other,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session) values(own,gen_random_uuid(),a),(other,gen_random_uuid(),x);
    insert into jojo_account_settings.profiles(owner_id,id,domain,primary_profile) values(own,p,'iptv',false);
    perform set_config('jojo.c_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_x',x,
        'device_a',gen_random_uuid(),'device_b',gen_random_uuid(),'device_x',gen_random_uuid(),'profile',p,'plan',gen_random_uuid(),'command',gen_random_uuid())::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.c_login('a');
select public.account_settings_register(pg_temp.c_id('device_a'),repeat('a',64),'androidtv');
select pg_temp.c_login('b');
select public.account_settings_register(pg_temp.c_id('device_b'),repeat('b',64),'firetv');
select pg_temp.c_login('a');
select pg_temp.c_assert('empty-on-first-read',pg_temp.c_read('a')->'rows'='[]'::jsonb);
select set_config('jojo.c_add',pg_temp.c_op('favorite',repeat('a',64))::text,true);
select pg_temp.c_assert('favorite-applied',pg_temp.c_write('a',current_setting('jojo.c_add')::jsonb)->0->>'status'='applied');
select pg_temp.c_assert('replay-same-revision',(pg_temp.c_write('a',current_setting('jojo.c_add')::jsonb)->0->>'revision')::bigint=1);
select pg_temp.c_denied('operation-reuse-rejected',$q$select pg_temp.c_write('a',jsonb_set(current_setting('jojo.c_add')::jsonb,'{value,sealed}',to_jsonb(repeat('B',40))))$q$,'P0001');
select pg_temp.c_login('b');
select pg_temp.c_assert('other-device-can-read',jsonb_array_length(pg_temp.c_read('b')->'rows')=1);
select pg_temp.c_assert('stale-favorite-conflict',pg_temp.c_write('b',pg_temp.c_op('favorite',repeat('a',64)))->0->>'status'='conflict');
select pg_temp.c_assert('delete-is-tombstone',pg_temp.c_write('b',pg_temp.c_op('favorite',repeat('a',64),1,gone=>true))->0->>'status'='applied');
select pg_temp.c_login('a');
select pg_temp.c_assert('old-replay-cannot-resurrect',pg_temp.c_write('a',current_setting('jojo.c_add')::jsonb)->0->>'revision'='1');
select pg_temp.c_assert('delete-still-visible',pg_temp.c_read('a')->'rows'->0->'value'->>'deleted'='true');
select pg_temp.c_denied('wrong-device-proof',$q$select public.account_content_read(pg_temp.c_id('device_b'),repeat('a',64),0)$q$,'42501');
select pg_temp.c_denied('wrong-profile-owner',$q$select pg_temp.c_write('a',jsonb_set(pg_temp.c_op('history',repeat('b',64)),'{value,profile}',to_jsonb(gen_random_uuid())))$q$,'P0001');
select pg_temp.c_denied('unexpected-stream-field',$q$select pg_temp.c_write('a',jsonb_set(pg_temp.c_op('history',repeat('b',64)),'{value,stream_url}','"forbidden"'))$q$,'P0001');
select pg_temp.c_assert('creator-publishes-plan',pg_temp.c_write('a',pg_temp.c_op('plan',pg_temp.c_id('plan')::text,creator=>pg_temp.c_id('device_a')::text))->0->>'status'='applied');
select pg_temp.c_login('b');
select pg_temp.c_denied('foreign-plan-update-denied',$q$select pg_temp.c_write('b',pg_temp.c_op('plan',pg_temp.c_id('plan')::text,3,creator=>pg_temp.c_id('device_b')::text))$q$,'42501');
select set_config('jojo.c_command',jsonb_set(pg_temp.c_op('command',pg_temp.c_id('command')::text,creator=>pg_temp.c_id('device_a')::text,
    reference=>pg_temp.c_id('plan')::text,base=>3,state=>'pending'),'{operation_id}',to_jsonb(gen_random_uuid()))::text,true);
select pg_temp.c_assert('remote-command-accepted',pg_temp.c_write('b',current_setting('jojo.c_command')::jsonb)->0->>'status'='applied');
select pg_temp.c_assert('parallel-command-conflicts',pg_temp.c_write('b',pg_temp.c_op('command',gen_random_uuid()::text,creator=>pg_temp.c_id('device_a')::text,
    reference=>pg_temp.c_id('plan')::text,base=>3,state=>'pending'))->0->>'status'='conflict');
select pg_temp.c_denied('sender-cannot-confirm-command',$q$select pg_temp.c_write('b',pg_temp.c_op('command',pg_temp.c_id('command')::text,4,
    creator=>pg_temp.c_id('device_a')::text,reference=>pg_temp.c_id('plan')::text,base=>3,state=>'applied'))$q$,'42501');
select pg_temp.c_login('a');
select pg_temp.c_assert('only-creator-confirms-command',pg_temp.c_write('a',pg_temp.c_op('command',pg_temp.c_id('command')::text,4,
    creator=>pg_temp.c_id('device_a')::text,reference=>pg_temp.c_id('plan')::text,base=>3,state=>'applied'))->0->>'status'='applied');
select pg_temp.c_assert('creator-can-remove-plan',pg_temp.c_write('a',pg_temp.c_op('plan',pg_temp.c_id('plan')::text,3,
    creator=>pg_temp.c_id('device_a')::text,gone=>true))->0->>'status'='applied');
select pg_temp.c_login('b');
select pg_temp.c_assert('deleted-plan-cannot-be-commanded',pg_temp.c_write('b',pg_temp.c_op('command',gen_random_uuid()::text,
    creator=>pg_temp.c_id('device_a')::text,reference=>pg_temp.c_id('plan')::text,base=>3,state=>'pending'))->0->>'status'='conflict');
select pg_temp.c_login('x');
select public.account_settings_register(pg_temp.c_id('device_x'),repeat('c',64),'androidtv');
select pg_temp.c_assert('other-account-has-no-rows',public.account_content_read(pg_temp.c_id('device_x'),repeat('c',64),0)->'rows'='[]'::jsonb);
select pg_temp.c_denied('other-account-cannot-claim-device',$q$select public.account_content_read(pg_temp.c_id('device_a'),repeat('a',64),0)$q$,'42501');
select pg_temp.c_login('a',true);
select pg_temp.c_denied('anonymous-jwt-denied',$q$select pg_temp.c_read('a')$q$,'42501');
set local role anon;
select pg_temp.c_denied('anon-rpc-denied',$q$select pg_temp.c_read('a')$q$,'42501');
set local role authenticated;
select pg_temp.c_login('a');
select pg_temp.c_denied('direct-select-denied','select * from jojo_account_settings.content_rows','42501');
select pg_temp.c_denied('direct-update-denied','update jojo_account_settings.content_rows set revision=1','42501');
select pg_temp.c_denied('direct-delete-denied','delete from jojo_account_settings.content_rows','42501');
reset role;
select pg_temp.c_assert('forced-rls-all-three',(select count(*)=3 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='jojo_account_settings' and c.relname in ('content_heads','content_rows','content_receipts') and c.relrowsecurity and c.relforcerowsecurity));
select pg_temp.c_assert('no-public-or-anon-rpc',not has_function_privilege('anon','public.account_content_write(uuid,text,jsonb)','EXECUTE'));
select jsonb_build_object('status','CHECKS_PASSED','assertions_before_rollback',current_setting('jojo.c_count')::integer) as checks;
rollback to savepoint content_fixtures;
select pg_temp.c_assert('fixtures-rolled-back',pg_temp.c_fingerprint()=current_setting('jojo.c_before')::jsonb);
select jsonb_build_object('status','PASS',
    'fixtures_rolled_back',true,'network_or_devices_tested',false) as diagnostic;
rollback;
