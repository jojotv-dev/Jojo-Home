-- PROPOSITION NON EXECUTEE : accord explicite 035 obligatoire avant application.
-- jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh. Aucun appareil ni compte reconfigure.
-- Ajoute seulement une presentation AES-GCM opaque aux nouveaux checkpoints.
-- Les RPC historiques restent intactes ; leurs lignes n'exposent pas cette colonne.
-- Apres accord, ajouter apres BEGIN, avant le premier DO :
-- SET LOCAL jojo.resume_project_ref='jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.resume_approved='APPROVED_035';
begin;
set local lock_timeout='3s';
set local statement_timeout='30s';
set local row_security=on;
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.resume_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.resume_approved',true) is distinct from 'APPROVED_035' then
        raise exception 'RESUME_SCHEMA_APPROVAL_REQUIRED';
    end if;
    if to_regprocedure('public.progress_sync_read(uuid,uuid,bigint,text)') is null
       or to_regprocedure('public.progress_sync_write(uuid,uuid,uuid,jsonb)') is null
       or to_regprocedure('public.progress_sync_read_v2(uuid,uuid,bigint,text)') is not null
       or to_regprocedure('public.progress_sync_write_v2(uuid,uuid,uuid,jsonb)') is not null
       or exists(select 1 from information_schema.columns where table_schema='jojo_progress_pilot'
           and table_name='cloud_watch_progress' and column_name='sealed_metadata')
       or not exists(select 1 from pg_roles where rolname='jojo_progress_pilot_rpc'
           and not rolcanlogin and not rolbypassrls and not rolsuper)
       or not exists(select 1 from pg_class where oid='jojo_progress_pilot.cloud_watch_progress'::regclass
           and relrowsecurity and relforcerowsecurity)
       or has_schema_privilege('jojo_progress_pilot_rpc','public','CREATE') then
        raise exception 'RESUME_SCHEMA_PREFLIGHT_FAILED';
    end if;
end;
$guard$;
alter table jojo_progress_pilot.cloud_watch_progress add column sealed_metadata text
    check(sealed_metadata is null or length(sealed_metadata) between 40 and 24000
        and sealed_metadata ~ '^[A-Za-z0-9+/]+={0,2}$');
grant update(sealed_metadata) on jojo_progress_pilot.cloud_watch_progress to jojo_progress_pilot_rpc;
create function public.progress_sync_read_v2(p_profile uuid,p_epoch uuid,p_after bigint,p_content text default null)
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
          'sealed_metadata',w.sealed_metadata,'received_time_ms',floor(extract(epoch from w.received_at)*1000)::bigint) as payload
        from jojo_progress_pilot.cloud_watch_progress w
        where w.owner_id=v_owner and w.profile_binding_id=p_profile and w.key_epoch=p_epoch
          and w.server_revision>p_after order by w.server_revision limit 50
      ) page;
    if jsonb_array_length(v_rows)<50 then v_next:=v_head; end if;
    -- Le point demande ne deplace pas le curseur de pagination : aucune ligne intermediaire perdue.
    if p_content is not null then
        select jsonb_build_object('content_key',w.content_key,'media_kind',w.media_kind,'position_ms',w.position_ms,
            'duration_ms',w.duration_ms,'progress_percent',w.progress_percent,'watch_state',w.watch_state,
            'resume_hidden',w.resume_hidden,'source_time_ms',w.source_time_ms,'server_revision',w.server_revision,
            'sealed_metadata',w.sealed_metadata,'received_time_ms',floor(extract(epoch from w.received_at)*1000)::bigint) into v_point
          from jojo_progress_pilot.cloud_watch_progress w
          where w.owner_id=v_owner and w.profile_binding_id=p_profile and w.key_epoch=p_epoch and w.content_key=p_content;
    end if;
    return jsonb_build_object('rows',v_rows,'next_cursor',coalesce(v_next,p_after),'head_revision',v_head,'point',v_point);
end;
$read$;

create function public.progress_sync_write_v2(p_profile uuid,p_epoch uuid,p_incarnation uuid,p_ops jsonb)
returns jsonb language plpgsql security definer set search_path='' as $write$
declare
    v_owner uuid; v_device uuid; v_head bigint; v_count integer; v_entry jsonb; v_payload jsonb;
    v_seq bigint; v_op uuid; v_expected bigint; v_hash text; v_revision bigint; v_status text;
    v_receipt jojo_progress_pilot.cloud_progress_receipts%rowtype;
    v_result jsonb:='[]'::jsonb;
begin
    if p_incarnation is null or jsonb_typeof(p_ops) is distinct from 'array'
       or jsonb_array_length(p_ops) not between 1 and 50 or octet_length(p_ops::text)>1500000 then raise exception 'CLOUD_BATCH_INVALID'; end if;
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
                 'resume_hidden','source_time_ms','server_revision','received_time_ms','sealed_metadata'))
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
        if v_payload ? 'sealed_metadata' and v_payload->'sealed_metadata'<>'null'::jsonb then
            if jsonb_typeof(v_payload->'sealed_metadata')<>'string'
               or length(v_payload->>'sealed_metadata') not between 40 and 24000
               or (v_payload->>'sealed_metadata') !~ '^[A-Za-z0-9+/]+={0,2}$' then
                raise exception 'CLOUD_METADATA_INVALID';
            end if;
            if octet_length(decode(v_payload->>'sealed_metadata','base64')) not between 29 and 16028 then
                raise exception 'CLOUD_METADATA_INVALID';
            end if;
        else
            -- A persisted v1 operation must retain its original replay hash after an APK upgrade.
            v_payload:=v_payload-'sealed_metadata';
            v_entry:=jsonb_set(v_entry,'{payload}',v_payload);
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
                   progress_percent,watch_state,resume_hidden,source_time_ms,server_revision,last_device_id,operation_id,sealed_metadata)
                values (v_owner,p_profile,p_epoch,v_payload->>'content_key',v_payload->>'media_kind',
                    (v_payload->>'position_ms')::bigint,(v_payload->>'duration_ms')::bigint,
                    (v_payload->>'progress_percent')::real,v_payload->>'watch_state',
                    (v_payload->>'resume_hidden')::boolean,(v_payload->>'source_time_ms')::bigint,
                    v_revision,v_device,v_op,v_payload->>'sealed_metadata')
                on conflict (owner_id,profile_binding_id,key_epoch,content_key) do update set
                    sealed_metadata=coalesce(excluded.sealed_metadata,w.sealed_metadata),
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
grant create on schema public to jojo_progress_pilot_rpc;
alter function public.progress_sync_read_v2(uuid,uuid,bigint,text) owner to jojo_progress_pilot_rpc;
alter function public.progress_sync_write_v2(uuid,uuid,uuid,jsonb) owner to jojo_progress_pilot_rpc;
revoke create on schema public from jojo_progress_pilot_rpc;
revoke all on function public.progress_sync_read_v2(uuid,uuid,bigint,text),
    public.progress_sync_write_v2(uuid,uuid,uuid,jsonb) from public,anon,authenticated,service_role;
grant execute on function public.progress_sync_read_v2(uuid,uuid,bigint,text),
    public.progress_sync_write_v2(uuid,uuid,uuid,jsonb) to authenticated;
notify pgrst,'reload schema';
commit;
