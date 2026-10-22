-- CORRECTION PROPOSEE NON APPLIQUEE. Accord distinct requis avant execution.
-- Projet exclusif : jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh.
-- Corrige les ACL des quatre RPC de 016 et des deux RPC de 018, deja presentes.
-- Aucun changement de corps de fonction, table, progression, compte, session ou appareil.
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_setting('jojo.approved_022',true) is distinct from 'YES' then
        raise exception 'APPROVAL_022_REQUIRED'; end if;
    if current_user<>'postgres' or not exists(select 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and id='2e43435a-eda7-44d0-8d95-476a2a973446')
        or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc'
            and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit)
        or (select count(*) from pg_proc p where p.oid in (
            to_regprocedure('public.account_settings_register(uuid,text,text)'),
            to_regprocedure('public.account_settings_seed(uuid,text,uuid,jsonb)'),
            to_regprocedure('public.account_settings_read(uuid,text,bigint)'),
            to_regprocedure('public.account_settings_write(uuid,text,jsonb)'),
            to_regprocedure('public.account_content_read(uuid,text,bigint)'),
            to_regprocedure('public.account_content_write(uuid,text,jsonb)'))
            and p.proowner='jojo_account_settings_rpc'::regrole and p.prosecdef)<>6
        then raise exception 'ACCOUNT_RPC_PERMISSIONS_PREFLIGHT_FAILED'; end if;
end;
$guard$;

-- Appartenance technique temporaire : necessaire pour prendre le role proprietaire.
-- INHERIT reste desactive ; SET est retire dans la meme transaction.
grant jojo_account_settings_rpc to postgres with set true;
set local role jojo_account_settings_rpc;
revoke all on function
    public.account_settings_register(uuid,text,text),
    public.account_settings_seed(uuid,text,uuid,jsonb),
    public.account_settings_read(uuid,text,bigint),
    public.account_settings_write(uuid,text,jsonb),
    public.account_content_read(uuid,text,bigint),
    public.account_content_write(uuid,text,jsonb)
    from public,anon,authenticated;
grant execute on function
    public.account_settings_register(uuid,text,text),
    public.account_settings_seed(uuid,text,uuid,jsonb),
    public.account_settings_read(uuid,text,bigint),
    public.account_settings_write(uuid,text,jsonb),
    public.account_content_read(uuid,text,bigint),
    public.account_content_write(uuid,text,jsonb)
    to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;

do $verify$
begin
    if exists(select 1 from pg_proc p where p.oid in (
        to_regprocedure('public.account_settings_register(uuid,text,text)'),
        to_regprocedure('public.account_settings_seed(uuid,text,uuid,jsonb)'),
        to_regprocedure('public.account_settings_read(uuid,text,bigint)'),
        to_regprocedure('public.account_settings_write(uuid,text,jsonb)'),
        to_regprocedure('public.account_content_read(uuid,text,bigint)'),
        to_regprocedure('public.account_content_write(uuid,text,jsonb)'))
        and (has_function_privilege('anon',p.oid,'EXECUTE')
            or not has_function_privilege('authenticated',p.oid,'EXECUTE')
            or exists(select 1 from aclexplode(p.proacl) a where a.grantee=0 and a.privilege_type='EXECUTE')))
        or pg_has_role('authenticated','jojo_account_settings_rpc','MEMBER')
        or pg_has_role('anon','jojo_account_settings_rpc','MEMBER')
        or exists(select 1 from pg_auth_members m where m.roleid='jojo_account_settings_rpc'::regrole
            and m.member='postgres'::regrole and (m.set_option or m.inherit_option))
        then raise exception 'ACCOUNT_RPC_PERMISSIONS_VERIFICATION_FAILED'; end if;
end;
$verify$;
commit;
