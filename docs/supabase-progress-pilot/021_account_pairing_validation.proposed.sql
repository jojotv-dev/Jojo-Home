-- PROPOSITION NON EXECUTEE. Apres 019, accord distinct obligatoire.
-- Le schema pilote n'autorise qu'un proprietaire : on REUTILISE SON UUID, avec
-- uniquement des sessions, installations, demandes et un espace cloud FICTIFS.
-- Aucun mot de passe ni session reelle n'est lu, modifie ou utilise. Aucune TV commandee.
-- Toutes les nouvelles lignes sont annulees. Aucune ligne existante n'est modifiee.
begin isolation level repeatable read;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_setting('jojo.approved_021',true) is distinct from 'YES' then raise exception 'APPROVAL_021_REQUIRED'; end if;
    if (select count(*) from jojo_progress_pilot.pilot_profile_bindings)<>1
        or not exists(select 1 from jojo_progress_pilot.pilot_profile_bindings where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c')
        or exists(select 1 from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace
            where not t.tgisinternal and (n.nspname='jojo_account_settings'
                or n.nspname='jojo_progress_pilot' and c.relname in ('pilot_devices','cloud_progress_scopes')
                or n.nspname='auth' and c.relname in ('users','sessions')))
        or exists(select 1 from jojo_progress_pilot.pilot_devices where slot in (31,32))
        then raise exception 'PAIR_VALIDATION_PREFLIGHT_FAILED'; end if;
end;
$guard$;
create function pg_temp.p_assert(label text,ok boolean) returns integer language plpgsql as $$
begin if ok is distinct from true then raise exception 'PAIR_ASSERTION_FAILED: %',label; end if;
    perform set_config('jojo.p_count',(coalesce(nullif(current_setting('jojo.p_count',true),''),'0')::integer+1)::text,true); return 1; end;
$$;
create function pg_temp.p_denied(label text,command text,code text) returns integer language plpgsql as $$
begin begin execute command; exception when others then
    if sqlstate=code then return pg_temp.p_assert(label,true); end if;
    raise exception 'PAIR_WRONG_REJECTION: %, %',label,sqlstate; end;
    raise exception 'PAIR_UNEXPECTED_SUCCESS: %',label; end;
$$;
create function pg_temp.p_id(name text) returns uuid language sql as $$select (current_setting('jojo.p_fixture')::jsonb->>name)::uuid$$;
create function pg_temp.p_login(which text,anonymous boolean default false) returns void language plpgsql as $$
begin perform set_config('request.jwt.claims',jsonb_build_object('sub',pg_temp.p_id(case when which='x' then 'other' else 'owner' end),
    'session_id',pg_temp.p_id('session_'||which),'role','authenticated','is_anonymous',anonymous)::text,true); end;
$$;
create function pg_temp.p_start(which text default 'b',nonce uuid default gen_random_uuid()) returns jsonb language sql as $$
 select public.account_pair_request(pg_temp.p_id('device_'||which),repeat(which,64),pg_temp.p_id('target'),pg_temp.p_id('home'),nonce,repeat('A',392))
$$;
create function pg_temp.p_approve(grant_value jsonb) returns jsonb language sql as $$
 select public.account_pair_approve(pg_temp.p_id('device_a'),repeat('a',64),(grant_value->'request'->>'id')::uuid,grant_value)
$$;
create function pg_temp.p_collect(which text default 'b') returns jsonb language sql as $$
 select public.account_pair_collect(pg_temp.p_id('device_'||which),repeat(which,64),(current_setting('jojo.p_request')::jsonb->>'id')::uuid)
$$;
create function pg_temp.p_fingerprint() returns jsonb language sql as $$
 select jsonb_build_object('requests',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.pair_requests t),
    'enrollments',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,device)::text,'')) from jojo_account_settings.enrollments t),
    'devices',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_progress_pilot.pilot_devices t),
    'scopes',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,profile_binding_id,key_epoch)::text,'')) from jojo_progress_pilot.cloud_progress_scopes t),
    'progress',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,profile_binding_id,key_epoch,content_key)::text,'')) from jojo_progress_pilot.cloud_watch_progress t),
    'profiles',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,id)::text,'')) from jojo_account_settings.profiles t),
    'entries',(select md5(coalesce(jsonb_agg(to_jsonb(t) order by owner_id,scope,subject,key)::text,'')) from jojo_account_settings.entries t),
    'accounts',(select count(*) from jojo_account_settings.accounts),'sessions',(select count(*) from auth.sessions),'users',(select count(*) from auth.users))
$$;
select set_config('jojo.p_before',pg_temp.p_fingerprint()::text,true);
savepoint pair_fixtures;
do $fixture$
declare own uuid:='2012b04b-1bf1-4421-82d2-a74234bbe81c'; other uuid:=gen_random_uuid(); a uuid:=gen_random_uuid();
    b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid(); x uuid:=gen_random_uuid(); da uuid:=gen_random_uuid();
    db uuid:=gen_random_uuid(); dc uuid:=gen_random_uuid(); dx uuid:=gen_random_uuid(); target uuid:=gen_random_uuid();
    home uuid; epoch uuid:=gen_random_uuid(); profile uuid; device_a uuid; device_b uuid;
begin
    insert into auth.users(id,email,aud,role,is_anonymous) values(other,null,'authenticated','authenticated',false);
    insert into auth.sessions(id,user_id,not_after) values(a,own,now()+interval '1 hour'),(b,own,now()+interval '1 hour'),
        (c,own,now()+interval '1 hour'),(x,other,now()+interval '1 hour');
    insert into jojo_account_settings.accounts(owner_id,seed_legacy_device,seed_auth_session) values(other,gen_random_uuid(),x);
    insert into jojo_account_settings.installations(owner_id,id,proof_hash,auth_session_id,platform) values
        (own,da,encode(sha256(convert_to(repeat('a',64),'UTF8')),'hex'),a,'androidtv'),
        (own,db,encode(sha256(convert_to(repeat('b',64),'UTF8')),'hex'),b,'androidtv'),
        (own,dc,encode(sha256(convert_to(repeat('c',64),'UTF8')),'hex'),c,'firetv'),
        (other,dx,encode(sha256(convert_to(repeat('d',64),'UTF8')),'hex'),x,'androidtv');
    select id into home from jojo_account_settings.profiles where owner_id=own and domain='home' and primary_profile;
    if home is null then
        home:=gen_random_uuid(); insert into jojo_account_settings.profiles values(own,home,'home',true);
        insert into jojo_account_settings.entries(owner_id,scope,subject,key,value,revision,last_device) values
            (own,'profile',home,'profiles.home',jsonb_build_object('name','Profil fictif','color','#D3A033','avatar','','primary',true),
                (select coalesce(max(revision),0)+1 from jojo_account_settings.entries where owner_id=own),da);
    end if;
    select p.id,p.device_a,p.device_b into strict profile,device_a,device_b from jojo_progress_pilot.pilot_profile_bindings p where p.owner_id=own;
    -- Approbateur fictif reserve au test, jamais une session .119/.150/.163.
    insert into jojo_progress_pilot.pilot_devices(owner_id,id,slot,auth_session_id,active) values(own,da,31,a,true);
    insert into jojo_progress_pilot.cloud_progress_scopes(owner_id,profile_binding_id,key_epoch,enabled,allowed_devices)
        values(own,profile,epoch,true,array[da]);
    perform set_config('jojo.p_fixture',jsonb_build_object('owner',own,'other',other,'session_a',a,'session_b',b,'session_c',c,'session_x',x,
        'device_a',da,'device_b',db,'device_c',dc,'device_x',dx,'target',target,'home',home,'profile',profile,'epoch',epoch,
        'legacy_a',device_a,'legacy_b',device_b)::text,true);
end;
$fixture$;
set local role authenticated;
select pg_temp.p_login('b');
select set_config('jojo.p_request',pg_temp.p_start()::text,true);
select pg_temp.p_assert('request-bound-to-target',current_setting('jojo.p_request')::jsonb->>'device'=pg_temp.p_id('target')::text);
select pg_temp.p_assert('request-bound-to-session',current_setting('jojo.p_request')::jsonb->>'session'=pg_temp.p_id('session_b')::text);
select pg_temp.p_assert('nonce-is-idempotent',pg_temp.p_start(nonce=>(current_setting('jojo.p_request')::jsonb->>'nonce')::uuid)=current_setting('jojo.p_request')::jsonb);
select pg_temp.p_denied('unapproved-cannot-collect',$q$select pg_temp.p_collect()$q$,'42501');
select pg_temp.p_denied('new-device-cannot-list-before-approval',$q$select public.account_pair_list(pg_temp.p_id('device_b'),repeat('b',64))$q$,'P0002');
select pg_temp.p_login('a');
select pg_temp.p_assert('trusted-tv-can-list',jsonb_array_length(public.account_pair_list(pg_temp.p_id('device_a'),repeat('a',64)))=1);
select set_config('jojo.p_grant',jsonb_build_object('request',current_setting('jojo.p_request')::jsonb,'profile',pg_temp.p_id('profile'),
    'key_epoch',pg_temp.p_id('epoch'),'device_a',pg_temp.p_id('legacy_a'),'device_b',pg_temp.p_id('legacy_b'),'packet',repeat('B',342)||'==')::text,true);
select pg_temp.p_denied('wrong-recipient-refused',$q$select pg_temp.p_approve(jsonb_set(current_setting('jojo.p_grant')::jsonb,'{request,device}',to_jsonb(gen_random_uuid())))$q$,'P0001');
select pg_temp.p_denied('wrong-cloud-scope-refused',$q$select pg_temp.p_approve(jsonb_set(current_setting('jojo.p_grant')::jsonb,'{key_epoch}',to_jsonb(gen_random_uuid())))$q$,'42501');
select pg_temp.p_denied('null-packet-refused',$q$select pg_temp.p_approve(jsonb_set(current_setting('jojo.p_grant')::jsonb,'{packet}','null'))$q$,'P0001');
select pg_temp.p_assert('trusted-tv-can-approve',pg_temp.p_approve(current_setting('jojo.p_grant')::jsonb)->>'approved'='true');
select pg_temp.p_assert('same-approval-idempotent',pg_temp.p_approve(current_setting('jojo.p_grant')::jsonb)->>'approved'='true');
select pg_temp.p_denied('different-packet-cannot-replace-approval',$q$select pg_temp.p_approve(jsonb_set(current_setting('jojo.p_grant')::jsonb,'{packet}',to_jsonb(repeat('C',342)||'==')))$q$,'P0001');
select pg_temp.p_denied('approver-cannot-collect-target-packet',$q$select pg_temp.p_collect('a')$q$,'42501');
select pg_temp.p_login('c');
select pg_temp.p_denied('other-installation-cannot-collect',$q$select pg_temp.p_collect('c')$q$,'42501');
select pg_temp.p_login('b');
select pg_temp.p_assert('target-can-collect',pg_temp.p_collect()=current_setting('jojo.p_grant')::jsonb);
select pg_temp.p_assert('collection-retry-same-packet',pg_temp.p_collect()=current_setting('jojo.p_grant')::jsonb);
select pg_temp.p_assert('enrolled-session-can-read-cloud',public.progress_sync_read(pg_temp.p_id('profile'),pg_temp.p_id('epoch'),0,null)->'rows'='[]'::jsonb);
select pg_temp.p_login('a',true);
select pg_temp.p_denied('anonymous-account-denied',$q$select public.account_pair_list(pg_temp.p_id('device_a'),repeat('a',64))$q$,'42501');
set local role anon;
select pg_temp.p_denied('anon-rpc-denied',$q$select pg_temp.p_collect()$q$,'42501');
set local role authenticated;
select pg_temp.p_login('b');
select pg_temp.p_denied('direct-enrollment-select-denied','select * from jojo_account_settings.enrollments','42501');
select pg_temp.p_denied('direct-device-impersonation-denied','update jojo_progress_pilot.pilot_devices set auth_session_id=gen_random_uuid()','42501');
select pg_temp.p_denied('direct-enrollment-delete-denied','delete from jojo_account_settings.enrollments','42501');
reset role;
update jojo_account_settings.pair_requests set expires_at=now()-interval '1 second' where owner_id=pg_temp.p_id('owner') and id=(current_setting('jojo.p_request')::jsonb->>'id')::uuid;
set local role authenticated;
select pg_temp.p_login('b');
select pg_temp.p_denied('expired-collection-denied',$q$select pg_temp.p_collect()$q$,'42501');
select pg_temp.p_login('x');
select pg_temp.p_denied('other-account-cannot-collect',$q$select public.account_pair_collect(pg_temp.p_id('device_x'),repeat('d',64),(current_setting('jojo.p_request')::jsonb->>'id')::uuid)$q$,'P0002');
reset role;
select pg_temp.p_assert('pairing-does-not-write-progress',(select count(*)=0 from jojo_progress_pilot.cloud_watch_progress where key_epoch=pg_temp.p_id('epoch')));
select pg_temp.p_assert('both-pair-tables-force-rls',(select count(*)=2 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname='jojo_account_settings' and c.relname in ('pair_requests','enrollments') and c.relrowsecurity and c.relforcerowsecurity));
select pg_temp.p_assert('authenticated-cannot-assume-private-role',not pg_has_role('authenticated','jojo_account_settings_rpc','MEMBER'));
select pg_temp.p_assert('rpc-owner-and-search-path',(select count(*)=4 and bool_and(p.prosecdef and p.proconfig @> array['search_path=""'])
    from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public' and p.proname in
    ('account_pair_request','account_pair_list','account_pair_approve','account_pair_collect') and p.proowner='jojo_account_settings_rpc'::regrole));
select jsonb_build_object('status','CHECKS_PASSED','assertions_before_rollback',current_setting('jojo.p_count')::integer) as checks;
rollback to savepoint pair_fixtures;
select pg_temp.p_assert('all-fixtures-rolled-back',pg_temp.p_fingerprint()=current_setting('jojo.p_before')::jsonb);
select pg_temp.p_assert('postgres-private-role-options-restored',not exists(select 1 from pg_auth_members m
    where m.roleid='jojo_account_settings_rpc'::regrole and m.member='postgres'::regrole and (m.set_option or m.inherit_option)));
select jsonb_build_object('status','PASS','fixtures_rolled_back',true,'network_or_devices_tested',false) as diagnostic;
rollback;
