-- PROPOSITION NON EXECUTEE : revue et accord explicite avant application.
-- Projet de test uniquement : jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh.
-- Apres 001/003/009/010/011. Aucun historique, compte ou appareil importe/modifie.
-- Cree un contrat de progression distinct du miroir. Aucun espace cloud n'est ouvert
-- ici : son INSERT administratif, avec la generation partagee de cle, reste a approuver.
-- Apres accord uniquement, ajouter dans CETTE transaction avant le premier DO :
-- SET LOCAL jojo.cloud_progress_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.cloud_progress_approved = 'APPROVED_SCHEMA_012';
begin;
set local lock_timeout = '3s';
set local statement_timeout = '30s';
set local row_security = on;
do $guard$
begin
    if current_setting('jojo.cloud_progress_project_ref', true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.cloud_progress_approved', true) is distinct from 'APPROVED_SCHEMA_012' then
        raise exception 'CLOUD_SCHEMA_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if current_user <> 'postgres'
       or not exists (select 1 from pg_roles where rolname = current_user and rolbypassrls)
       or not exists (select 1 from pg_roles where rolname = 'jojo_progress_pilot_rpc'
            and not rolsuper and not rolbypassrls and not rolcanlogin)
       or pg_has_role('authenticated', 'jojo_progress_pilot_rpc', 'MEMBER')
       or pg_has_role('anon', 'jojo_progress_pilot_rpc', 'MEMBER')
       or has_schema_privilege('jojo_progress_pilot_rpc', 'auth', 'USAGE') then
        raise exception 'CLOUD_SCHEMA_ADMIN_OR_ROLE_INVALID';
    end if;
    if to_regprocedure('jojo_progress_pilot.pilot_request_identity()') is null
       or to_regclass('jojo_progress_pilot.cloud_progress_scopes') is not null then
        raise exception 'CLOUD_SCHEMA_PRECONDITION_FAILED';
    end if;
    if (select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
        where n.nspname='jojo_progress_pilot' and c.relname in
            ('pilot_profile_bindings','pilot_devices','pilot_watch_progress')
        and c.relrowsecurity and c.relforcerowsecurity
        and c.relowner <> (select oid from pg_roles where rolname='jojo_progress_pilot_rpc')) <> 3 then
        raise exception 'CLOUD_SCHEMA_EXISTING_RLS_CHANGED';
    end if;
end;
$guard$;

create table jojo_progress_pilot.cloud_progress_scopes (
    owner_id uuid not null,
    profile_binding_id uuid not null,
    key_epoch uuid not null,
    enabled boolean not null default false,
    allowed_devices uuid[] not null check (cardinality(allowed_devices) between 1 and 3),
    head_revision bigint not null default 0 check (head_revision >= 0),
    receipt_count integer not null default 0 check (receipt_count between 0 and 100000),
    primary key (owner_id, profile_binding_id, key_epoch),
    foreign key (owner_id, profile_binding_id)
        references jojo_progress_pilot.pilot_profile_bindings(owner_id,id) on delete cascade
);
create table jojo_progress_pilot.cloud_watch_progress (
    owner_id uuid not null,
    profile_binding_id uuid not null,
    key_epoch uuid not null,
    content_key text not null check (content_key ~ '^[0-9a-f]{64}$'),
    media_kind text not null check (media_kind in ('film','episode','file')),
    position_ms bigint not null check (position_ms >= 0),
    duration_ms bigint not null check (duration_ms >= 0),
    progress_percent real check (progress_percent between 0 and 100),
    watch_state text not null check (watch_state in ('unseen','in_progress','completed')),
    resume_hidden boolean not null,
    source_time_ms bigint not null check (source_time_ms > 0),
    server_revision bigint not null check (server_revision > 0),
    received_at timestamptz not null default clock_timestamp(),
    last_device_id uuid not null,
    operation_id uuid not null,
    primary key (owner_id, profile_binding_id, key_epoch, content_key),
    unique (owner_id, profile_binding_id, key_epoch, server_revision),
    foreign key (owner_id, profile_binding_id, key_epoch)
        references jojo_progress_pilot.cloud_progress_scopes on delete cascade,
    foreign key (owner_id,last_device_id) references jojo_progress_pilot.pilot_devices(owner_id,id)
);
create table jojo_progress_pilot.cloud_progress_receipts (
    owner_id uuid not null,
    profile_binding_id uuid not null,
    key_epoch uuid not null,
    device_id uuid not null,
    incarnation uuid not null,
    sequence bigint not null check (sequence > 0),
    operation_id uuid not null,
    body_hash text not null check (body_hash ~ '^[0-9a-f]{64}$'),
    status text not null check (status in ('applied','conflict')),
    server_revision bigint not null check (server_revision >= 0),
    primary key (owner_id,profile_binding_id,key_epoch,device_id,operation_id),
    unique (owner_id,profile_binding_id,key_epoch,device_id,incarnation,sequence),
    foreign key (owner_id,profile_binding_id,key_epoch)
        references jojo_progress_pilot.cloud_progress_scopes on delete cascade,
    foreign key (owner_id,device_id) references jojo_progress_pilot.pilot_devices(owner_id,id)
);

-- SECURITY INVOKER : ne contourne pas les RLS des associations/session du pilote.
-- Corps SQL analyse a la creation, selon le meme contrat que le correctif 003.
create function jojo_progress_pilot.cloud_profile_allowed(p_owner uuid,p_profile uuid)
returns boolean language sql stable security invoker set search_path=''
begin atomic
    select exists (select 1 from jojo_progress_pilot.pilot_request_identity() i
        join jojo_progress_pilot.pilot_profile_bindings b on b.owner_id=i.owner_id
        join jojo_progress_pilot.pilot_devices d on d.owner_id=b.owner_id
        where b.owner_id=p_owner and b.id=p_profile and d.active and d.auth_session_id=i.session_id);
end;
revoke all on function jojo_progress_pilot.cloud_profile_allowed(uuid,uuid) from public,anon,authenticated;
grant execute on function jojo_progress_pilot.cloud_profile_allowed(uuid,uuid) to jojo_progress_pilot_rpc;

alter table jojo_progress_pilot.cloud_progress_scopes enable row level security;
alter table jojo_progress_pilot.cloud_progress_scopes force row level security;
alter table jojo_progress_pilot.cloud_watch_progress enable row level security;
alter table jojo_progress_pilot.cloud_watch_progress force row level security;
alter table jojo_progress_pilot.cloud_progress_receipts enable row level security;
alter table jojo_progress_pilot.cloud_progress_receipts force row level security;
revoke all on jojo_progress_pilot.cloud_progress_scopes,jojo_progress_pilot.cloud_watch_progress,
    jojo_progress_pilot.cloud_progress_receipts from public,anon,authenticated;
grant select on jojo_progress_pilot.cloud_progress_scopes,jojo_progress_pilot.cloud_watch_progress,
    jojo_progress_pilot.cloud_progress_receipts to jojo_progress_pilot_rpc;
grant update (head_revision,receipt_count) on jojo_progress_pilot.cloud_progress_scopes to jojo_progress_pilot_rpc;
grant insert on jojo_progress_pilot.cloud_watch_progress,jojo_progress_pilot.cloud_progress_receipts to jojo_progress_pilot_rpc;
grant update (media_kind,position_ms,duration_ms,progress_percent,watch_state,resume_hidden,source_time_ms,
    server_revision,received_at,last_device_id,operation_id)
    on jojo_progress_pilot.cloud_watch_progress to jojo_progress_pilot_rpc;
create policy cloud_scope_select on jojo_progress_pilot.cloud_progress_scopes for select to jojo_progress_pilot_rpc
    using (enabled and jojo_progress_pilot.cloud_profile_allowed(owner_id,profile_binding_id)
        and exists (select 1 from jojo_progress_pilot.pilot_devices d
          where d.owner_id=cloud_progress_scopes.owner_id and d.id=any(cloud_progress_scopes.allowed_devices)));
create policy cloud_scope_update on jojo_progress_pilot.cloud_progress_scopes for update to jojo_progress_pilot_rpc
    using (enabled and jojo_progress_pilot.cloud_profile_allowed(owner_id,profile_binding_id)
        and exists (select 1 from jojo_progress_pilot.pilot_devices d
          where d.owner_id=cloud_progress_scopes.owner_id and d.id=any(cloud_progress_scopes.allowed_devices)))
    with check (enabled and jojo_progress_pilot.cloud_profile_allowed(owner_id,profile_binding_id)
        and exists (select 1 from jojo_progress_pilot.pilot_devices d
          where d.owner_id=cloud_progress_scopes.owner_id and d.id=any(cloud_progress_scopes.allowed_devices)));
create policy cloud_progress_select on jojo_progress_pilot.cloud_watch_progress for select to jojo_progress_pilot_rpc
    using (exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
        where s.owner_id=cloud_watch_progress.owner_id and s.profile_binding_id=cloud_watch_progress.profile_binding_id
          and s.key_epoch=cloud_watch_progress.key_epoch));
create policy cloud_progress_insert on jojo_progress_pilot.cloud_watch_progress for insert to jojo_progress_pilot_rpc
    with check (exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
        where s.owner_id=cloud_watch_progress.owner_id and s.profile_binding_id=cloud_watch_progress.profile_binding_id
          and s.key_epoch=cloud_watch_progress.key_epoch)
        and exists (select 1 from jojo_progress_pilot.pilot_devices d
          where d.owner_id=cloud_watch_progress.owner_id and d.id=cloud_watch_progress.last_device_id));
create policy cloud_progress_update on jojo_progress_pilot.cloud_watch_progress for update to jojo_progress_pilot_rpc
    using (exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
        where s.owner_id=cloud_watch_progress.owner_id and s.profile_binding_id=cloud_watch_progress.profile_binding_id
          and s.key_epoch=cloud_watch_progress.key_epoch))
    with check (exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
        where s.owner_id=cloud_watch_progress.owner_id and s.profile_binding_id=cloud_watch_progress.profile_binding_id
          and s.key_epoch=cloud_watch_progress.key_epoch)
        and exists (select 1 from jojo_progress_pilot.pilot_devices d
          where d.owner_id=cloud_watch_progress.owner_id and d.id=cloud_watch_progress.last_device_id));
create policy cloud_receipt_select on jojo_progress_pilot.cloud_progress_receipts for select to jojo_progress_pilot_rpc
    using (exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id=cloud_progress_receipts.owner_id and d.id=cloud_progress_receipts.device_id)
        and exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
          where s.owner_id=cloud_progress_receipts.owner_id and s.profile_binding_id=cloud_progress_receipts.profile_binding_id
            and s.key_epoch=cloud_progress_receipts.key_epoch));
create policy cloud_receipt_insert on jojo_progress_pilot.cloud_progress_receipts for insert to jojo_progress_pilot_rpc
    with check (exists (select 1 from jojo_progress_pilot.pilot_devices d
        where d.owner_id=cloud_progress_receipts.owner_id and d.id=cloud_progress_receipts.device_id)
        and exists (select 1 from jojo_progress_pilot.cloud_progress_scopes s
          where s.owner_id=cloud_progress_receipts.owner_id and s.profile_binding_id=cloud_progress_receipts.profile_binding_id
            and s.key_epoch=cloud_progress_receipts.key_epoch));
-- Aucun INSERT/DELETE d'espace cloud, aucun DELETE de progression ou de recu via RPC.

create function public.progress_sync_read(p_profile uuid,p_epoch uuid,p_after bigint,p_content text default null)
returns jsonb language plpgsql security definer set search_path='' as $read$
declare
    v_owner uuid; v_device uuid; v_head bigint; v_rows jsonb; v_next bigint; v_point jsonb;
begin
    if p_after is null or p_after < 0 then raise exception 'CLOUD_CURSOR_INVALID'; end if;
    if p_content is not null and p_content !~ '^[0-9a-f]{64}$' then raise exception 'CLOUD_KEY_INVALID'; end if;
    select s.owner_id,d.id,s.head_revision into strict v_owner,v_device,v_head
      from jojo_progress_pilot.cloud_progress_scopes s
      join jojo_progress_pilot.pilot_devices d on d.owner_id=s.owner_id
      where s.profile_binding_id=p_profile and s.key_epoch=p_epoch and s.enabled for share of s;
    if p_after > v_head then raise exception 'CLOUD_CURSOR_AHEAD'; end if;
    select coalesce(jsonb_agg(page.payload order by page.revision),'[]'::jsonb),max(page.revision)
      into v_rows,v_next from (
        select w.server_revision as revision,jsonb_build_object(
          'content_key',w.content_key,'media_kind',w.media_kind,'position_ms',w.position_ms,
          'duration_ms',w.duration_ms,'progress_percent',w.progress_percent,'watch_state',w.watch_state,
          'resume_hidden',w.resume_hidden,'source_time_ms',w.source_time_ms,'server_revision',w.server_revision,
          'received_time_ms',floor(extract(epoch from w.received_at)*1000)::bigint) as payload
        from jojo_progress_pilot.cloud_watch_progress w
        where w.owner_id=v_owner and w.profile_binding_id=p_profile and w.key_epoch=p_epoch
          and w.server_revision>p_after order by w.server_revision limit 200
      ) page;
    if jsonb_array_length(v_rows)<200 then v_next:=v_head; end if;
    -- Le point demande ne deplace pas le curseur de pagination : aucune ligne intermediaire perdue.
    if p_content is not null then
        select jsonb_build_object('content_key',w.content_key,'media_kind',w.media_kind,'position_ms',w.position_ms,
            'duration_ms',w.duration_ms,'progress_percent',w.progress_percent,'watch_state',w.watch_state,
            'resume_hidden',w.resume_hidden,'source_time_ms',w.source_time_ms,'server_revision',w.server_revision,
            'received_time_ms',floor(extract(epoch from w.received_at)*1000)::bigint) into v_point
          from jojo_progress_pilot.cloud_watch_progress w
          where w.owner_id=v_owner and w.profile_binding_id=p_profile and w.key_epoch=p_epoch and w.content_key=p_content;
    end if;
    return jsonb_build_object('rows',v_rows,'next_cursor',coalesce(v_next,p_after),'head_revision',v_head,'point',v_point);
end;
$read$;

create function public.progress_sync_write(p_profile uuid,p_epoch uuid,p_incarnation uuid,p_ops jsonb)
returns jsonb language plpgsql security definer set search_path='' as $write$
declare
    v_owner uuid; v_device uuid; v_head bigint; v_count integer; v_entry jsonb; v_payload jsonb;
    v_seq bigint; v_op uuid; v_expected bigint; v_hash text; v_revision bigint; v_status text;
    v_receipt jojo_progress_pilot.cloud_progress_receipts%rowtype;
    v_result jsonb:='[]'::jsonb;
begin
    if p_incarnation is null or jsonb_typeof(p_ops) is distinct from 'array'
       or jsonb_array_length(p_ops) not between 1 and 50 then raise exception 'CLOUD_BATCH_INVALID'; end if;
    -- Un seul verrou par espace, conserve jusqu'au commit : ordre de commit et curseur coherents.
    select s.owner_id,d.id,s.head_revision,s.receipt_count into strict v_owner,v_device,v_head,v_count
      from jojo_progress_pilot.cloud_progress_scopes s
      join jojo_progress_pilot.pilot_devices d on d.owner_id=s.owner_id
      where s.profile_binding_id=p_profile and s.key_epoch=p_epoch and s.enabled for update of s;
    for v_entry in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(v_entry) is distinct from 'object'
           or not (v_entry ?& array['operation_id','sequence','expected_revision','payload'])
           or exists (select 1 from jsonb_object_keys(v_entry) x(k)
                where k not in ('operation_id','sequence','expected_revision','payload')) then
            raise exception 'CLOUD_OPERATION_INVALID';
        end if;
        v_op:=(v_entry->>'operation_id')::uuid; v_seq:=(v_entry->>'sequence')::bigint;
        v_expected:=(v_entry->>'expected_revision')::bigint; v_payload:=v_entry->'payload';
        if v_op is null or v_seq is null or v_seq<=0 or v_expected is null or v_expected<0
           or jsonb_typeof(v_payload) is distinct from 'object'
           or not (v_payload ?& array['content_key','media_kind','position_ms','duration_ms','progress_percent',
                'watch_state','resume_hidden','source_time_ms','server_revision','received_time_ms'])
           or exists (select 1 from jsonb_object_keys(v_payload) x(k) where k not in
                ('content_key','media_kind','position_ms','duration_ms','progress_percent','watch_state',
                 'resume_hidden','source_time_ms','server_revision','received_time_ms'))
           or (v_payload->>'server_revision')::bigint is distinct from 0
           or (v_payload->>'received_time_ms')::bigint is distinct from 0 then
            raise exception 'CLOUD_PAYLOAD_INVALID';
        end if;
        if coalesce(v_payload->>'content_key','') !~ '^[0-9a-f]{64}$'
           or jsonb_typeof(v_entry->'sequence') is distinct from 'number'
           or jsonb_typeof(v_entry->'expected_revision') is distinct from 'number'
           or jsonb_typeof(v_entry->'operation_id') is distinct from 'string'
           or jsonb_typeof(v_payload->'content_key') is distinct from 'string'
           or coalesce(v_payload->>'media_kind','') not in ('film','episode','file')
           or coalesce(v_payload->>'watch_state','') not in ('unseen','in_progress','completed')
           or jsonb_typeof(v_payload->'position_ms') is distinct from 'number'
           or jsonb_typeof(v_payload->'duration_ms') is distinct from 'number'
           or jsonb_typeof(v_payload->'source_time_ms') is distinct from 'number'
           or jsonb_typeof(v_payload->'resume_hidden') is distinct from 'boolean'
           or (v_payload->>'position_ms')::bigint<0 or (v_payload->>'duration_ms')::bigint<0
           or (v_payload->>'source_time_ms')::bigint<=0
           or (jsonb_typeof(v_payload->'progress_percent') not in ('null','number'))
           or (v_payload->>'progress_percent')::real not between 0 and 100 then
            raise exception 'CLOUD_PAYLOAD_INVALID';
        end if;
        v_hash:=encode(sha256(convert_to(jsonb_build_object('incarnation',p_incarnation,'op',v_entry)::text,'UTF8')),'hex');
        select * into v_receipt from jojo_progress_pilot.cloud_progress_receipts r
          where r.owner_id=v_owner and r.profile_binding_id=p_profile and r.key_epoch=p_epoch
            and r.device_id=v_device and r.operation_id=v_op;
        if found then
            if v_receipt.body_hash<>v_hash then raise exception 'CLOUD_OPERATION_BODY_CHANGED'; end if;
            v_status:=case when v_receipt.status='applied' then 'duplicate' else 'conflict' end;
            v_revision:=v_receipt.server_revision;
        else
            if v_count>=100000 then raise exception 'CLOUD_RECEIPT_LIMIT_REVIEW_REQUIRED'; end if;
            if exists (select 1 from jojo_progress_pilot.cloud_progress_receipts r
                where r.owner_id=v_owner and r.profile_binding_id=p_profile and r.key_epoch=p_epoch
                  and r.device_id=v_device and r.incarnation=p_incarnation and r.sequence>=v_seq) then
                raise exception 'CLOUD_SEQUENCE_REUSED_OR_REGRESSED';
            end if;
            select w.server_revision into v_revision from jojo_progress_pilot.cloud_watch_progress w
              where w.owner_id=v_owner and w.profile_binding_id=p_profile and w.key_epoch=p_epoch
                and w.content_key=v_payload->>'content_key';
            v_revision:=coalesce(v_revision,0);
            if v_expected<>v_revision then v_status:='conflict';
            else
                v_head:=v_head+1; v_revision:=v_head; v_status:='applied';
                insert into jojo_progress_pilot.cloud_watch_progress as w
                  (owner_id,profile_binding_id,key_epoch,content_key,media_kind,position_ms,duration_ms,
                   progress_percent,watch_state,resume_hidden,source_time_ms,server_revision,last_device_id,operation_id)
                values (v_owner,p_profile,p_epoch,v_payload->>'content_key',v_payload->>'media_kind',
                    (v_payload->>'position_ms')::bigint,(v_payload->>'duration_ms')::bigint,
                    (v_payload->>'progress_percent')::real,v_payload->>'watch_state',
                    (v_payload->>'resume_hidden')::boolean,(v_payload->>'source_time_ms')::bigint,
                    v_revision,v_device,v_op)
                on conflict (owner_id,profile_binding_id,key_epoch,content_key) do update set
                    media_kind=excluded.media_kind,position_ms=excluded.position_ms,duration_ms=excluded.duration_ms,
                    progress_percent=excluded.progress_percent,watch_state=excluded.watch_state,
                    resume_hidden=excluded.resume_hidden,source_time_ms=excluded.source_time_ms,
                    server_revision=excluded.server_revision,received_at=clock_timestamp(),
                    last_device_id=excluded.last_device_id,operation_id=excluded.operation_id;
            end if;
            insert into jojo_progress_pilot.cloud_progress_receipts
              (owner_id,profile_binding_id,key_epoch,device_id,incarnation,sequence,operation_id,body_hash,status,server_revision)
              values (v_owner,p_profile,p_epoch,v_device,p_incarnation,v_seq,v_op,v_hash,v_status,v_revision);
            v_count:=v_count+1;
        end if;
        v_result:=v_result||jsonb_build_array(jsonb_build_object('operation_id',v_op,'status',v_status,'server_revision',v_revision));
    end loop;
    update jojo_progress_pilot.cloud_progress_scopes set head_revision=v_head,receipt_count=v_count
      where owner_id=v_owner and profile_binding_id=p_profile and key_epoch=p_epoch;
    return v_result;
end;
$write$;
-- CREATE est retire avant commit ; aucun droit direct nouveau pour authenticated/anon.
grant create on schema public to jojo_progress_pilot_rpc;
alter function public.progress_sync_read(uuid,uuid,bigint,text) owner to jojo_progress_pilot_rpc;
alter function public.progress_sync_write(uuid,uuid,uuid,jsonb) owner to jojo_progress_pilot_rpc;
revoke all on function public.progress_sync_read(uuid,uuid,bigint,text),public.progress_sync_write(uuid,uuid,uuid,jsonb)
    from public,anon,authenticated;
grant execute on function public.progress_sync_read(uuid,uuid,bigint,text),public.progress_sync_write(uuid,uuid,uuid,jsonb)
    to authenticated;
revoke create on schema public from jojo_progress_pilot_rpc;
commit;
