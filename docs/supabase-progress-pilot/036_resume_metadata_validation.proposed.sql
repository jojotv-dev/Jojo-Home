-- PROPOSITION NON EXECUTEE : accord distinct 036 obligatoire. Validation en lecture seule apres 035.
-- Ne valide pas un parcours TV ni des echanges Auth HTTP. Aucun contenu personnel n'est lu.
-- Apres accord, ajouter apres BEGIN :
-- SET LOCAL jojo.resume_project_ref='jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.resume_approved='APPROVED_036';
begin read only;
set local statement_timeout='30s';
do $tests$
declare name text; role_name text; fn regprocedure; passed integer:=0;
begin
    if current_user<>'postgres'
       or current_setting('jojo.resume_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.resume_approved',true) is distinct from 'APPROVED_036' then
        raise exception 'RESUME_VALIDATION_APPROVAL_REQUIRED';
    end if;
    if not exists(select 1 from information_schema.columns where table_schema='jojo_progress_pilot'
        and table_name='cloud_watch_progress' and column_name='sealed_metadata' and is_nullable='YES' and data_type='text') then
        raise exception 'RESUME_COLUMN_INVALID';
    end if;
    passed:=passed+1;
    foreach name in array array['cloud_progress_scopes','cloud_watch_progress','cloud_progress_receipts'] loop
        if not exists(select 1 from pg_class where oid=('jojo_progress_pilot.'||name)::regclass
            and relrowsecurity and relforcerowsecurity
            and relowner<>(select oid from pg_roles where rolname='jojo_progress_pilot_rpc')) then
            raise exception 'RESUME_RLS_INVALID: %',name;
        end if;
        passed:=passed+1;
        foreach role_name in array array['anon','authenticated'] loop
            if has_any_column_privilege(role_name,'jojo_progress_pilot.'||name,'SELECT,INSERT,UPDATE,REFERENCES')
               or has_table_privilege(role_name,'jojo_progress_pilot.'||name,'DELETE,TRUNCATE,TRIGGER') then
                raise exception 'RESUME_DIRECT_ACCESS: %, %',role_name,name;
            end if;
            passed:=passed+1;
        end loop;
    end loop;
    foreach name in array array['public.progress_sync_read_v2(uuid,uuid,bigint,text)',
        'public.progress_sync_write_v2(uuid,uuid,uuid,jsonb)'] loop
        fn:=name::regprocedure;
        if not exists(select 1 from pg_proc where oid=fn and prosecdef
            and proowner=(select oid from pg_roles where rolname='jojo_progress_pilot_rpc')
            and proconfig @> array['search_path=""']::text[])
           or not has_function_privilege('authenticated',name,'EXECUTE') then raise exception 'RESUME_RPC_INVALID: %',name; end if;
        passed:=passed+1;
        foreach role_name in array array['anon','service_role'] loop
            if has_function_privilege(role_name,name,'EXECUTE') then raise exception 'RESUME_RPC_EXPOSED: %, %',role_name,name; end if;
            passed:=passed+1;
        end loop;
        if exists(select 1 from pg_proc p cross join lateral aclexplode(coalesce(p.proacl,acldefault('f',p.proowner))) a
            where p.oid=fn and a.grantee=0 and a.privilege_type='EXECUTE') then raise exception 'RESUME_PUBLIC_EXECUTE: %',name; end if;
        passed:=passed+1;
    end loop;
    if not exists(select 1 from pg_roles where rolname='jojo_progress_pilot_rpc' and not rolcanlogin and not rolbypassrls and not rolsuper)
       or has_schema_privilege('jojo_progress_pilot_rpc','public','CREATE') then raise exception 'RESUME_ROLE_INVALID'; end if;
    passed:=passed+1;
    if not has_column_privilege('jojo_progress_pilot_rpc','jojo_progress_pilot.cloud_watch_progress','sealed_metadata','UPDATE') then
        raise exception 'RESUME_COLUMN_GRANT_MISSING'; end if;
    passed:=passed+1;
    raise notice 'RESUME_READ_ONLY_ASSERTIONS_PASSED=%',passed;
end;
$tests$;
rollback;
