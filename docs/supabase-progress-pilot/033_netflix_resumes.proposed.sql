-- PROPOSITION NON EXECUTEE : accord distinct requis avant application.
-- Projet jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Listes de reprise Netflix par profil Netflix : identite HMAC et contenu AES-GCM opaque.
-- Aucun compte Netflix, jeton, lien arbitraire, image privee ou position de session anonyme.
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_033 = 'YES';
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_033',true) is distinct from 'YES' then
        raise exception 'NETFLIX_RESUMES_APPROVAL_REQUIRED';
    end if;
    if to_regclass('jojo_account_settings.netflix_resumes') is not null
       or to_regclass('jojo_account_settings.netflix_heads') is not null
       or to_regprocedure('public.account_netflix_resumes_sync(uuid,text,bigint,jsonb)') is not null
       or to_regprocedure('jojo_account_settings.require_device(uuid,text)') is null
       or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc'
           and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit)
       or has_schema_privilege('jojo_account_settings_rpc','public','CREATE')
       or exists(select 1 from pg_auth_members m join pg_roles r on r.oid=m.roleid join pg_roles p on p.oid=m.member
           where r.rolname='jojo_account_settings_rpc' and p.rolname='postgres' and m.set_option) then
        raise exception 'NETFLIX_RESUMES_PREFLIGHT_FAILED';
    end if;
end;
$guard$;
create table jojo_account_settings.netflix_heads(
    owner_id uuid primary key references jojo_account_settings.accounts(owner_id) on delete cascade,
    revision bigint not null default 0 check(revision>=0)
);
create table jojo_account_settings.netflix_resumes(
    owner_id uuid not null references jojo_account_settings.accounts(owner_id) on delete cascade,
    key text not null check(key ~ '^[0-9a-f]{64}$'),
    stamp bigint not null check(stamp between 1 and 253402300799999),
    writer uuid not null,
    sealed text not null check(length(sealed) between 40 and 87420 and sealed ~ '^[A-Za-z0-9+/]+={0,2}$'),
    revision bigint not null check(revision>0),
    primary key(owner_id,key), unique(owner_id,revision),
    foreign key(owner_id,writer) references jojo_account_settings.installations(owner_id,id)
);
create index netflix_resumes_writer on jojo_account_settings.netflix_resumes(owner_id,writer);
alter table jojo_account_settings.netflix_heads enable row level security;
alter table jojo_account_settings.netflix_heads force row level security;
alter table jojo_account_settings.netflix_resumes enable row level security;
alter table jojo_account_settings.netflix_resumes force row level security;
create policy netflix_head_select on jojo_account_settings.netflix_heads for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy netflix_head_insert on jojo_account_settings.netflix_heads for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy netflix_head_update on jojo_account_settings.netflix_heads for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy netflix_resume_select on jojo_account_settings.netflix_resumes for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy netflix_resume_insert on jojo_account_settings.netflix_resumes for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy netflix_resume_update on jojo_account_settings.netflix_resumes for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
revoke all on jojo_account_settings.netflix_heads,jojo_account_settings.netflix_resumes from public,anon,authenticated,service_role;
grant select,insert on jojo_account_settings.netflix_heads,jojo_account_settings.netflix_resumes to jojo_account_settings_rpc;
grant update(revision) on jojo_account_settings.netflix_heads to jojo_account_settings_rpc;
grant update(stamp,writer,sealed,revision) on jojo_account_settings.netflix_resumes to jojo_account_settings_rpc;

create function public.account_netflix_resumes_sync(p_device uuid,p_proof text,p_after bigint,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
    own uuid:=jojo_account_settings.require_device(p_device,p_proof);
    item jsonb; resume_key text; resume_stamp bigint; resume_writer uuid; resume_sealed text;
    previous jojo_account_settings.netflix_resumes%rowtype;
    head bigint:=0; next_revision bigint; result jsonb;
begin
    if p_after is null or p_after<0 or jsonb_typeof(p_ops) is distinct from 'array'
       or jsonb_array_length(p_ops)>1 or octet_length(p_ops::text)>90000 then
        raise exception using errcode='22023',message='NETFLIX_RESUMES_BATCH_INVALID';
    end if;
    perform 1 from jojo_account_settings.accounts where owner_id=own and initialized for update;
    if not found then raise exception using errcode='42501',message='NETFLIX_RESUMES_ACCOUNT_NOT_READY'; end if;
    select revision into head from jojo_account_settings.netflix_heads where owner_id=own;
    head:=coalesce(head,0);
    if p_after>head then raise exception using errcode='22023',message='NETFLIX_RESUMES_CURSOR_INVALID'; end if;
    for item in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(item)<>'object' or (select count(*) from jsonb_object_keys(item))<>4
           or not item ?& array['key','stamp','writer','sealed']
           or jsonb_typeof(item->'key')<>'string' or (item->>'key') !~ '^[0-9a-f]{64}$'
           or jsonb_typeof(item->'stamp')<>'number' or (item->>'stamp') !~ '^[0-9]+$'
           or jsonb_typeof(item->'writer')<>'string' or (item->>'writer') is distinct from p_device::text
           or jsonb_typeof(item->'sealed')<>'string' or length(item->>'sealed') not between 40 and 87420
           or (item->>'sealed') !~ '^[A-Za-z0-9+/]+={0,2}$' then
            raise exception using errcode='22023',message='NETFLIX_RESUMES_VALUE_INVALID';
        end if;
        if (item->>'stamp')::numeric not between 1 and 253402300799999 then
            raise exception using errcode='22023',message='NETFLIX_RESUMES_CLOCK_INVALID';
        end if;
        resume_key:=item->>'key'; resume_stamp:=(item->>'stamp')::bigint;
        resume_writer:=p_device; resume_sealed:=item->>'sealed';
        if octet_length(decode(resume_sealed,'base64')) not between 29 and 65564 then
            raise exception using errcode='22023',message='NETFLIX_RESUMES_SIZE_INVALID';
        end if;
        select * into previous from jojo_account_settings.netflix_resumes where owner_id=own and key=resume_key;
        if found then
            if (previous.stamp,previous.writer)>(resume_stamp,resume_writer) then continue; end if;
            if (previous.stamp,previous.writer)=(resume_stamp,resume_writer) then
                if previous.sealed<>resume_sealed then
                    raise exception using errcode='22023',message='NETFLIX_RESUMES_REPLAY_MISMATCH';
                end if;
                continue;
            end if;
        elsif (select count(*) from jojo_account_settings.netflix_resumes where owner_id=own)>=100 then
            raise exception using errcode='54000',message='NETFLIX_RESUMES_LIMIT';
        end if;
        insert into jojo_account_settings.netflix_heads(owner_id) values(own) on conflict do nothing;
        update jojo_account_settings.netflix_heads set revision=revision+1 where owner_id=own returning revision into strict next_revision;
        insert into jojo_account_settings.netflix_resumes(owner_id,key,stamp,writer,sealed,revision)
            values(own,resume_key,resume_stamp,resume_writer,resume_sealed,next_revision)
            on conflict(owner_id,key) do update set stamp=excluded.stamp,writer=excluded.writer,sealed=excluded.sealed,revision=excluded.revision;
    end loop;
    select revision into head from jojo_account_settings.netflix_heads where owner_id=own;
    head:=coalesce(head,0);
    select coalesce(jsonb_agg(jsonb_build_object('key',key,'stamp',stamp,'writer',writer,'sealed',sealed,'revision',revision)
            order by revision),'[]'::jsonb),coalesce(max(revision),p_after)
        into result,next_revision from (select key,stamp,writer,sealed,revision
            from jojo_account_settings.netflix_resumes where owner_id=own and revision>p_after order by revision limit 20) page_rows;
    return jsonb_build_object('schema',1,'next_cursor',next_revision,'head_revision',head,'rows',result);
end;
$$;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_netflix_resumes_sync(uuid,text,bigint,jsonb) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
set local role jojo_account_settings_rpc;
revoke all on function public.account_netflix_resumes_sync(uuid,text,bigint,jsonb) from public,anon,authenticated,service_role;
grant execute on function public.account_netflix_resumes_sync(uuid,text,bigint,jsonb) to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;
do $permissions$
begin
    if has_function_privilege('anon','public.account_netflix_resumes_sync(uuid,text,bigint,jsonb)','EXECUTE')
       or not has_function_privilege('authenticated','public.account_netflix_resumes_sync(uuid,text,bigint,jsonb)','EXECUTE')
       or has_table_privilege('authenticated','jojo_account_settings.netflix_resumes','SELECT')
       or has_schema_privilege('jojo_account_settings_rpc','public','CREATE') then
        raise exception 'NETFLIX_RESUMES_PERMISSIONS_INVALID';
    end if;
end;
$permissions$;
notify pgrst,'reload schema';
commit;
