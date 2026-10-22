-- PROPOSITION NON APPLIQUEE. Autorisation distincte requise avant execution.
-- Projet de test uniquement. Aucun historique ou planning existant n'est importe.
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_setting('jojo.approved_018',true) is distinct from 'YES' then
        raise exception 'APPROVAL_018_REQUIRED';
    end if;
    if not exists(select 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id='2012b04b-1bf1-4421-82d2-a74234bbe81c' and id='2e43435a-eda7-44d0-8d95-476a2a973446')
       or to_regclass('jojo_account_settings.content_rows') is not null
       or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc' and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit)
       then raise exception 'ACCOUNT_CONTENT_PREFLIGHT_FAILED'; end if;
end;
$guard$;

create table jojo_account_settings.content_heads(
    owner_id uuid primary key references jojo_account_settings.accounts(owner_id),
    revision bigint not null default 0 check(revision>=0),
    receipt_count integer not null default 0 check(receipt_count between 0 and 100000)
);
create table jojo_account_settings.content_rows(
    owner_id uuid not null, profile uuid not null,
    kind text not null check(kind in ('favorite','history','plan','command')),
    key text not null,
    creator uuid, reference uuid, base_revision bigint not null default 0 check(base_revision>=0),
    status text not null default '', deleted boolean not null default false,
    sealed text not null default '', revision bigint not null check(revision>0),
    actor uuid not null, received_at timestamptz not null default clock_timestamp(),
    primary key(owner_id,profile,kind,key), unique(owner_id,revision),
    foreign key(owner_id,profile) references jojo_account_settings.profiles(owner_id,id),
    foreign key(owner_id,actor) references jojo_account_settings.installations(owner_id,id),
    foreign key(owner_id,creator) references jojo_account_settings.installations(owner_id,id),
    check((kind in ('favorite','history') and key~'^[0-9a-f]{64}$' and creator is null and reference is null and base_revision=0 and status='')
       or (kind='plan' and key~'^[0-9a-f-]{36}$' and creator is not null and reference is null and base_revision=0 and status='')
       or (kind='command' and key~'^[0-9a-f-]{36}$' and creator is not null and reference is not null and base_revision>0
           and status in ('pending','applied','rejected') and not deleted)),
    check((deleted and sealed='') or (not deleted and length(sealed) between 40 and 24000 and sealed~'^[A-Za-z0-9+/]+={0,2}$'))
);
create table jojo_account_settings.content_receipts(
    owner_id uuid not null, device uuid not null, operation uuid not null,
    body_hash text not null check(body_hash~'^[0-9a-f]{64}$'),
    status text not null check(status in ('applied','conflict')), revision bigint not null check(revision>=0),
    primary key(owner_id,device,operation),
    foreign key(owner_id,device) references jojo_account_settings.installations(owner_id,id)
);

alter table jojo_account_settings.content_heads enable row level security;
alter table jojo_account_settings.content_heads force row level security;
alter table jojo_account_settings.content_rows enable row level security;
alter table jojo_account_settings.content_rows force row level security;
alter table jojo_account_settings.content_receipts enable row level security;
alter table jojo_account_settings.content_receipts force row level security;
create policy content_head_select on jojo_account_settings.content_heads for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy content_head_insert on jojo_account_settings.content_heads for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy content_head_update on jojo_account_settings.content_heads for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy content_row_select on jojo_account_settings.content_rows for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy content_row_insert on jojo_account_settings.content_rows for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and exists(select 1 from jojo_account_settings.profiles p
        where p.owner_id=content_rows.owner_id and p.id=content_rows.profile and p.domain='iptv'));
create policy content_row_update on jojo_account_settings.content_rows for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy content_receipt_select on jojo_account_settings.content_receipts for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner() and exists(select 1 from jojo_account_settings.installations d
        where d.owner_id=content_receipts.owner_id and d.id=content_receipts.device and d.active
            and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())));
create policy content_receipt_insert on jojo_account_settings.content_receipts for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner() and exists(select 1 from jojo_account_settings.installations d
        where d.owner_id=content_receipts.owner_id and d.id=content_receipts.device and d.active
            and d.auth_session_id=(select session_id from jojo_progress_pilot.pilot_request_identity())));
revoke all on jojo_account_settings.content_heads,jojo_account_settings.content_rows,jojo_account_settings.content_receipts
    from public,anon,authenticated;
grant select,insert on jojo_account_settings.content_heads,jojo_account_settings.content_rows,jojo_account_settings.content_receipts to jojo_account_settings_rpc;
grant update(revision,receipt_count) on jojo_account_settings.content_heads to jojo_account_settings_rpc;
grant update(sealed,status,deleted,revision,actor,received_at) on jojo_account_settings.content_rows to jojo_account_settings_rpc;

create function jojo_account_settings.content_value(r jojo_account_settings.content_rows) returns jsonb
language sql immutable security invoker set search_path='' as $$
 select jsonb_build_object('profile',r.profile,'kind',r.kind,'key',r.key,'creator',coalesce(r.creator::text,''),
    'reference',coalesce(r.reference::text,''),'base_revision',r.base_revision,'status',r.status,'deleted',r.deleted,'sealed',r.sealed)
$$;
create function public.account_content_read(p_device uuid,p_proof text,p_after bigint) returns jsonb
language plpgsql security definer set search_path='' as $$
declare own uuid; head bigint; next_cursor bigint; rows jsonb;
begin
    own:=jojo_account_settings.require_device(p_device,p_proof);
    insert into jojo_account_settings.content_heads(owner_id) values(own) on conflict do nothing;
    select revision into strict head from jojo_account_settings.content_heads where owner_id=own for share;
    if p_after is null or p_after<0 or p_after>head then raise exception 'ACCOUNT_CONTENT_CURSOR_INVALID'; end if;
    select coalesce(jsonb_agg(jsonb_build_object('value',jojo_account_settings.content_value(r::jojo_account_settings.content_rows),'revision',r.revision) order by r.revision),'[]'),
        coalesce(max(r.revision),head) into rows,next_cursor
    from (select * from jojo_account_settings.content_rows where owner_id=own and revision>p_after order by revision limit 50) r;
    return jsonb_build_object('schema',1,'rows',rows,'next_cursor',next_cursor,'head_revision',head);
end;
$$;
create function public.account_content_write(p_device uuid,p_proof text,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare own uuid; head bigint; total integer; e jsonb; v jsonb; op uuid; expected bigint;
    pid uuid; v_kind text; v_key text; v_creator uuid; v_ref uuid; v_base bigint; v_state text; v_gone boolean; v_sealed text;
    hash text; result jsonb:='[]'; outcome text; rev bigint;
    old jojo_account_settings.content_rows; parent jojo_account_settings.content_rows;
    receipt jojo_account_settings.content_receipts; exists_old boolean;
begin
    own:=jojo_account_settings.require_device(p_device,p_proof);
    if jsonb_typeof(p_ops) is distinct from 'array' or jsonb_array_length(p_ops) not between 1 and 50 then
        raise exception 'ACCOUNT_CONTENT_BATCH_INVALID'; end if;
    insert into jojo_account_settings.content_heads(owner_id) values(own) on conflict do nothing;
    select revision,receipt_count into strict head,total from jojo_account_settings.content_heads where owner_id=own for update;
    for e in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(e) is distinct from 'object' or not(e ?& array['operation_id','expected_revision','value'])
            or (select count(*) from jsonb_object_keys(e))<>3 then raise exception 'ACCOUNT_CONTENT_OPERATION_INVALID'; end if;
        op:=(e->>'operation_id')::uuid; expected:=(e->>'expected_revision')::bigint; v:=e->'value';
        if op is null or expected is null or expected<0 or jsonb_typeof(v) is distinct from 'object'
           or not(v ?& array['profile','kind','key','creator','reference','base_revision','status','deleted','sealed'])
           or (select count(*) from jsonb_object_keys(v))<>9 then raise exception 'ACCOUNT_CONTENT_VALUE_INVALID'; end if;
        hash:=encode(sha256(convert_to(e::text,'UTF8')),'hex');
        select * into receipt from jojo_account_settings.content_receipts where owner_id=own and device=p_device and operation=op;
        if found then
            if receipt.body_hash<>hash then raise exception 'ACCOUNT_CONTENT_OPERATION_REUSED'; end if;
            result:=result||jsonb_build_array(jsonb_build_object('operation_id',op,'status',receipt.status,'revision',receipt.revision));
            continue;
        end if;
        pid:=(v->>'profile')::uuid; v_kind:=v->>'kind'; v_key:=v->>'key'; v_creator:=nullif(v->>'creator','')::uuid;
        v_ref:=nullif(v->>'reference','')::uuid; v_base:=(v->>'base_revision')::bigint; v_state:=v->>'status';
        v_gone:=(v->>'deleted')::boolean; v_sealed:=v->>'sealed';
        if pid is null or v_kind is null or v_key is null or v_base is null or v_state is null or v_gone is null or v_sealed is null
           or jsonb_typeof(v->'deleted')<>'boolean' or jsonb_typeof(v->'base_revision')<>'number'
           or not exists(select 1 from jojo_account_settings.profiles p where p.owner_id=own and p.id=pid and p.domain='iptv')
           or not((v_gone and v_sealed='') or (not v_gone and length(v_sealed) between 40 and 24000 and v_sealed~'^[A-Za-z0-9+/]+={0,2}$'))
           then raise exception 'ACCOUNT_CONTENT_VALUE_INVALID'; end if;
        if v_kind in ('favorite','history') then
            if v_key !~ '^[0-9a-f]{64}$' or v_creator is not null or v_ref is not null or v_base<>0 or v_state<>'' then raise exception 'ACCOUNT_CONTENT_ACTIVITY_INVALID'; end if;
        elsif v_kind in ('plan','command') then
            if v_key::uuid is null or v_creator is null then raise exception 'ACCOUNT_CONTENT_PLAN_INVALID'; end if;
        else raise exception 'ACCOUNT_CONTENT_KIND_INVALID'; end if;
        select * into old from jojo_account_settings.content_rows r where r.owner_id=own and r.profile=pid and r.kind=v_kind and r.key=v_key;
        exists_old:=found;
        if v_kind='plan' and (v_creator<>p_device or v_ref is not null or v_base<>0 or v_state<>'' or (exists_old and old.creator<>p_device)) then
            raise exception using errcode='42501',message='ACCOUNT_CONTENT_CREATOR_REQUIRED'; end if;
        if v_kind='command' then
            if v_ref is null or v_base<=0 or v_gone or v_state not in ('pending','applied','rejected') then raise exception 'ACCOUNT_CONTENT_COMMAND_INVALID'; end if;
            select * into parent from jojo_account_settings.content_rows r where r.owner_id=own and r.profile=pid and r.kind='plan' and r.key=v_ref::text;
            if not found or parent.creator<>v_creator then raise exception using errcode='42501',message='ACCOUNT_CONTENT_TARGET_INVALID'; end if;
            if exists_old then
                if p_device<>old.creator or old.status<>'pending' or v_state not in ('applied','rejected') or old.creator<>v_creator
                    or old.reference<>v_ref or old.base_revision<>v_base or old.sealed<>v_sealed then
                    raise exception using errcode='42501',message='ACCOUNT_CONTENT_ACK_DENIED'; end if;
            elsif p_device=v_creator or v_state<>'pending' then raise exception using errcode='42501',message='ACCOUNT_CONTENT_COMMAND_DENIED'; end if;
        end if;
        rev:=case when exists_old then old.revision else 0 end;
        outcome:='conflict';
        if expected=rev and not(v_kind='command' and not exists_old and (parent.deleted or parent.revision<>v_base or exists(
            select 1 from jojo_account_settings.content_rows r where r.owner_id=own and r.profile=pid and r.kind='command'
                and r.reference=v_ref and r.status='pending'))) then
            if not exists_old and (select count(*) from jojo_account_settings.content_rows where owner_id=own)>=10000 then
                raise exception 'ACCOUNT_CONTENT_ROWS_FULL'; end if;
            head:=head+1; rev:=head; outcome:='applied';
            insert into jojo_account_settings.content_rows(owner_id,profile,kind,key,creator,reference,base_revision,status,deleted,sealed,revision,actor)
                values(own,pid,v_kind,v_key,v_creator,v_ref,v_base,v_state,v_gone,v_sealed,head,p_device)
                on conflict(owner_id,profile,kind,key) do update set sealed=excluded.sealed,status=excluded.status,deleted=excluded.deleted,
                    revision=excluded.revision,received_at=clock_timestamp(),actor=case when excluded.kind='command' then content_rows.actor else excluded.actor end;
        end if;
        total:=total+1; if total>100000 then raise exception 'ACCOUNT_CONTENT_RECEIPTS_FULL'; end if;
        insert into jojo_account_settings.content_receipts values(own,p_device,op,hash,outcome,rev);
        result:=result||jsonb_build_array(jsonb_build_object('operation_id',op,'status',outcome,'revision',rev));
    end loop;
    update jojo_account_settings.content_heads set revision=head,receipt_count=total where owner_id=own;
    return result;
end;
$$;

revoke all on function jojo_account_settings.content_value(jojo_account_settings.content_rows) from public,anon,authenticated;
grant execute on function jojo_account_settings.content_value(jojo_account_settings.content_rows) to jojo_account_settings_rpc;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_content_read(uuid,text,bigint) owner to jojo_account_settings_rpc;
alter function public.account_content_write(uuid,text,jsonb) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
-- Les ACL doivent etre gerees par le proprietaire effectif. SET seul n'implique pas INHERIT.
set local role jojo_account_settings_rpc;
revoke all on function public.account_content_read(uuid,text,bigint),public.account_content_write(uuid,text,jsonb) from public,anon,authenticated;
grant execute on function public.account_content_read(uuid,text,bigint),public.account_content_write(uuid,text,jsonb) to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;
do $permissions$
begin
    if exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname='public' and p.proname in ('account_content_read','account_content_write')
            and (has_function_privilege('anon',p.oid,'EXECUTE') or not has_function_privilege('authenticated',p.oid,'EXECUTE')))
        then raise exception 'ACCOUNT_CONTENT_RPC_PERMISSIONS_INVALID'; end if;
end;
$permissions$;
commit;
