-- PROPOSITION NON EXECUTEE. Accord distinct requis avant application dans
-- jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh, apres 016/018/019/025.
-- La connexion Auth autorise l'ajout autonome d'une installation du meme compte.
-- La cle existante sera deposee une fois par une TV deja autorisee, dans Vault.
-- Le service serveur peut restituer cette cle apres authentification : ce parcours
-- remplace la dependance obligatoire a une autre TV, pas la cle ni les historiques.
-- Aucune cle reelle, progression ou association n'est importee par ce SQL.
-- Apres accord, ajouter DANS la transaction avant DO :
-- SET LOCAL jojo.account_project_ref='jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_027='YES';
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';

do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_027',true) is distinct from 'YES' then
        raise exception 'ACCOUNT_RECOVERY_APPROVAL_REQUIRED';
    end if;
    if to_regclass('jojo_account_settings.recovery_keys') is not null
       or to_regclass('vault.decrypted_secrets') is null
       or to_regprocedure('vault.create_secret(text,text,text,uuid)') is null
       or to_regprocedure('jojo_account_settings.require_device(uuid,text)') is null
       or to_regclass('jojo_account_settings.enrollments') is null
       or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc'
            and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit)
       or pg_has_role('authenticated','jojo_account_settings_rpc','MEMBER')
       or pg_has_role('anon','jojo_account_settings_rpc','MEMBER') then
        raise exception 'ACCOUNT_RECOVERY_PREFLIGHT_FAILED';
    end if;
end;
$guard$;

create table jojo_account_settings.recovery_keys(
    owner_id uuid primary key references jojo_account_settings.accounts(owner_id) on delete cascade,
    home_profile uuid not null,
    profile uuid not null,
    key_epoch uuid not null,
    secret_id uuid not null unique references vault.secrets(id),
    key_hash text not null check(key_hash ~ '^[0-9a-f]{64}$'),
    created_by uuid not null,
    foreign key(owner_id,home_profile) references jojo_account_settings.profiles(owner_id,id),
    foreign key(owner_id,profile,key_epoch) references jojo_progress_pilot.cloud_progress_scopes(owner_id,profile_binding_id,key_epoch),
    foreign key(owner_id,created_by) references jojo_account_settings.installations(owner_id,id)
);
alter table jojo_account_settings.recovery_keys enable row level security;
alter table jojo_account_settings.recovery_keys force row level security;
revoke all on jojo_account_settings.recovery_keys from public,anon,authenticated,service_role,jojo_account_settings_rpc;
-- NULL = ajout apres authentification du compte. Les anciens ajouts par code
-- conservent leur reference FK et les RPC anciennes restent compatibles.
alter table jojo_account_settings.enrollments alter column request drop not null;

-- Acces administratif etroitement borne a la session Auth ET a la preuve de
-- l'installation. Le role RPC n'obtient aucun droit de lecture globale sur Vault.
-- Le wrapper public a un role sans LOGIN/BYPASSRLS ; seul ce helper prive peut
-- lire Vault ou mettre a jour les associations historiques A/B/C apres reconnexion.
create function jojo_account_settings.recover_account(
    p_device uuid,p_proof text,p_target uuid,p_home_profile uuid,p_nonce uuid,
    p_key_epoch uuid default null,p_secret text default null
) returns jsonb language plpgsql security definer set search_path='' as $$
declare
    own uuid:=jojo_account_settings.require_device(p_device,p_proof);
    sess uuid; saved jojo_account_settings.recovery_keys;
    binding jojo_progress_pilot.pilot_profile_bindings;
    target jojo_progress_pilot.pilot_devices;
    enrolled jojo_account_settings.enrollments;
    home uuid; generation uuid; hash text; clear_key text; vault_id uuid; chosen_slot smallint;
    target_exists boolean; enrollment_exists boolean;
begin
    select session_id into strict sess from jojo_progress_pilot.pilot_request_identity();
    if p_target is null or p_target='00000000-0000-0000-0000-000000000000'::uuid
       or p_nonce is null or p_nonce='00000000-0000-0000-0000-000000000000'::uuid
       or p_home_profile is null then
        raise exception using errcode='22023',message='ACCOUNT_RECOVERY_REQUEST_INVALID';
    end if;
    -- Un seul verrou par compte serialise les ajouts et l'initialisation du coffre.
    perform 1 from jojo_account_settings.accounts where owner_id=own and initialized for update;
    if not found then raise exception using errcode='42501',message='ACCOUNT_RECOVERY_ACCOUNT_REQUIRED'; end if;
    select id into home from jojo_account_settings.profiles
        where owner_id=own and domain='home' and primary_profile and id=p_home_profile;
    if home is null or not exists(select 1 from jojo_account_settings.entries e
        where e.owner_id=own and e.scope='profile' and e.subject=home and e.key='profiles.home'
            and e.value is not null and e.value<>'null'::jsonb) then
        raise exception using errcode='42501',message='ACCOUNT_RECOVERY_PROFILE_DENIED';
    end if;
    select * into target from jojo_progress_pilot.pilot_devices where owner_id=own and id=p_target;
    target_exists:=found;
    if target_exists and not target.active then
        raise exception using errcode='42501',message='ACCOUNT_RECOVERY_DEVICE_REVOKED';
    end if;
    select * into enrolled from jojo_account_settings.enrollments
        where owner_id=own and (device=p_target or installation=p_device);
    enrollment_exists:=found;
    if enrollment_exists and (enrolled.device<>p_target or enrolled.installation<>p_device) then
        raise exception using errcode='42501',message='ACCOUNT_RECOVERY_IDENTITY_CHANGED';
    end if;
    select * into saved from jojo_account_settings.recovery_keys where owner_id=own;

    if p_secret is not null then
        -- Seule une session DEJA associee a cette TV peut initialiser le coffre.
        if not target_exists or (target.auth_session_id<>sess and not(enrollment_exists and saved.owner_id is not null)) or p_key_epoch is null
           or length(p_secret)<>44 or p_secret!~'^[A-Za-z0-9+/]{43}=$'
           or octet_length(decode(p_secret,'base64'))<>32
           or encode(decode(p_secret,'base64'),'base64')<>p_secret then
            raise exception using errcode='42501',message='ACCOUNT_RECOVERY_BACKUP_DENIED';
        end if;
        select b.* into binding from jojo_progress_pilot.pilot_profile_bindings b
        join jojo_progress_pilot.cloud_progress_scopes s on s.owner_id=b.owner_id and s.profile_binding_id=b.id
            where b.owner_id=own and s.key_epoch=p_key_epoch and s.enabled and p_target=any(s.allowed_devices)
                and (p_target=case target.slot when 1 then b.device_a when 2 then b.device_b when 3 then b.device_c else null::uuid end
                    or target.slot>=4 and enrollment_exists and enrolled.profile=b.id);
        if not found then raise exception using errcode='42501',message='ACCOUNT_RECOVERY_SCOPE_DENIED'; end if;
        generation:=p_key_epoch;
        hash:=encode(sha256(decode(p_secret,'base64')),'hex');
        if saved.owner_id is not null then
            if saved.home_profile<>home or saved.profile<>binding.id or saved.key_epoch<>generation or saved.key_hash<>hash then
                raise exception using errcode='40900',message='ACCOUNT_RECOVERY_KEY_CHANGED';
            end if;
        else
            vault_id:=vault.create_secret(p_secret,'jojohome-sync-'||own::text,'Jojo Home account synchronization key');
            insert into jojo_account_settings.recovery_keys(owner_id,home_profile,profile,key_epoch,secret_id,key_hash,created_by)
                values(own,home,binding.id,generation,vault_id,hash,p_device);
            select * into strict saved from jojo_account_settings.recovery_keys where owner_id=own;
        end if;
    else
        if p_key_epoch is not null then raise exception using errcode='22023',message='ACCOUNT_RECOVERY_REQUEST_INVALID'; end if;
        if saved.owner_id is null then raise exception using errcode='55000',message='ACCOUNT_RECOVERY_NOT_READY'; end if;
        if saved.home_profile<>home then raise exception using errcode='42501',message='ACCOUNT_RECOVERY_PROFILE_DENIED'; end if;
        select * into strict binding from jojo_progress_pilot.pilot_profile_bindings where owner_id=own and id=saved.profile;
        generation:=saved.key_epoch;
        if not exists(select 1 from jojo_progress_pilot.cloud_progress_scopes
            where owner_id=own and profile_binding_id=binding.id and key_epoch=generation and enabled) then
            raise exception using errcode='42501',message='ACCOUNT_RECOVERY_SCOPE_DENIED';
        end if;
        -- Une TV existante ne peut changer de session que depuis son installation
        -- deja liee et sa preuve locale ; connaitre son UUID ne suffit pas.
        if target_exists and not enrollment_exists then
            raise exception using errcode='42501',message='ACCOUNT_RECOVERY_IDENTITY_CHANGED';
        end if;
    end if;
    if enrollment_exists and enrolled.profile<>binding.id then
        raise exception using errcode='42501',message='ACCOUNT_RECOVERY_IDENTITY_CHANGED';
    end if;
    if exists(select 1 from jojo_progress_pilot.pilot_devices
        where owner_id=own and auth_session_id=sess and id<>p_target) then
        raise exception using errcode='42501',message='ACCOUNT_RECOVERY_SESSION_ASSIGNED';
    end if;
    if not target_exists then
        if p_target in (binding.device_a,binding.device_b,binding.device_c) then
            raise exception using errcode='42501',message='ACCOUNT_RECOVERY_IDENTITY_CHANGED';
        end if;
        select min(n)::smallint into chosen_slot from generate_series(4,32) n where not exists(
            select 1 from jojo_progress_pilot.pilot_devices d where d.owner_id=own and d.slot=n);
        if chosen_slot is null then raise exception using errcode='54000',message='ACCOUNT_RECOVERY_DEVICE_LIMIT'; end if;
    else
        chosen_slot:=target.slot;
        -- Le retrait d'une TV de la liste autorisee constitue une revocation.
        if not exists(select 1 from jojo_progress_pilot.cloud_progress_scopes where owner_id=own
            and profile_binding_id=binding.id and key_epoch=generation and p_target=any(allowed_devices)) then
            raise exception using errcode='42501',message='ACCOUNT_RECOVERY_DEVICE_REVOKED';
        end if;
    end if;
    select decrypted_secret into strict clear_key from vault.decrypted_secrets where id=saved.secret_id;
    if encode(sha256(decode(clear_key,'base64')),'hex')<>saved.key_hash then
        raise exception using errcode='55000',message='ACCOUNT_RECOVERY_VAULT_INVALID';
    end if;
    if not enrollment_exists then
        insert into jojo_account_settings.enrollments(owner_id,device,installation,profile,request,approver_session)
            values(own,p_target,p_device,binding.id,null,sess);
    end if;
    if not target_exists then
        insert into jojo_progress_pilot.pilot_devices(owner_id,id,slot,auth_session_id,active)
            values(own,p_target,chosen_slot,sess,true);
        update jojo_progress_pilot.cloud_progress_scopes set allowed_devices=array_append(allowed_devices,p_target)
            where owner_id=own and profile_binding_id=binding.id and key_epoch=generation and not(p_target=any(allowed_devices));
    elsif target.auth_session_id<>sess then
        update jojo_progress_pilot.pilot_devices set auth_session_id=sess where owner_id=own and id=p_target;
    end if;
    -- La reponse n'est renvoyee qu'a cette session via TLS ; aucune cle n'est
    -- placee dans les parametres partages, les journaux ou les metadonnees Auth.
    return jsonb_build_object('schema',1,'owner',own,'installation',p_device,'device',p_target,'session',sess,
        'home_profile',home,'nonce',p_nonce,'profile',binding.id,'key_epoch',generation,
        'device_a',binding.device_a,'device_b',binding.device_b,
        'device_c',case when chosen_slot=3 then binding.device_c else null::uuid end,'slot',chosen_slot,'secret',clear_key);
end;
$$;
revoke all on function jojo_account_settings.recover_account(uuid,text,uuid,uuid,uuid,uuid,text)
    from public,anon,authenticated,service_role;
grant execute on function jojo_account_settings.recover_account(uuid,text,uuid,uuid,uuid,uuid,text) to jojo_account_settings_rpc;

create function public.account_sync_recover(p_device uuid,p_proof text,p_target uuid,p_home_profile uuid,p_nonce uuid,
    p_key_epoch uuid default null,p_secret text default null) returns jsonb
language sql security definer set search_path=''
begin atomic
    select jojo_account_settings.recover_account(p_device,p_proof,p_target,p_home_profile,p_nonce,p_key_epoch,p_secret);
end;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
set local role jojo_account_settings_rpc;
revoke all on function public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text) from public,anon,authenticated,service_role;
grant execute on function public.account_sync_recover(uuid,text,uuid,uuid,uuid,uuid,text) to authenticated;
reset role;
commit;
