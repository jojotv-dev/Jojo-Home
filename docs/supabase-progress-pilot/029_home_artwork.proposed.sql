-- PROPOSITION NON EXECUTEE : accord distinct requis avant application.
-- Projet jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Partage les choix manuels TMDB des programmes Freebox (portrait, hero, episodes).
-- Aucun titre, chemin NAS, lien prive, fichier image ou progression n'est importe.
-- Ajouter APRES accord, dans cette transaction avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_029 = 'YES';
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_029',true) is distinct from 'YES' then
        raise exception 'HOME_ARTWORK_APPROVAL_REQUIRED';
    end if;
    if to_regclass('jojo_account_settings.artwork_choices') is not null
       or to_regprocedure('jojo_account_settings.require_device(uuid,text)') is null
       or not exists(select 1 from pg_roles where rolname='jojo_account_settings_rpc'
           and not rolcanlogin and not rolsuper and not rolbypassrls and not rolinherit) then
        raise exception 'HOME_ARTWORK_PREFLIGHT_FAILED';
    end if;
end;
$guard$;
create table jojo_account_settings.artwork_heads(
    owner_id uuid primary key references jojo_account_settings.accounts(owner_id) on delete cascade,
    revision bigint not null default 0 check(revision>=0)
);
create table jojo_account_settings.artwork_choices(
    owner_id uuid not null references jojo_account_settings.accounts(owner_id) on delete cascade,
    key text not null check(key ~ '^home_videos_(programme_(portrait|hero)_artwork|series_episode_artwork)_[0-9a-f]{1,8}$'),
    image text not null check(image='' or image ~ '^((original|w[0-9]{2,4}):)?[A-Za-z0-9]{1,128}\.(jpg|png|webp)$'),
    tmdb_id integer not null check(tmdb_id>=0),
    media_type text not null,
    stamp bigint not null check(stamp between 1 and 253402300799999),
    revision bigint not null check(revision>0),
    last_device uuid not null,
    primary key(owner_id,key), unique(owner_id,revision),
    check((tmdb_id=0 and media_type='') or (tmdb_id>0 and media_type in ('movie','tv'))),
    check(image<>'' or tmdb_id=0),
    foreign key(owner_id,last_device) references jojo_account_settings.installations(owner_id,id)
);
create index artwork_choices_read on jojo_account_settings.artwork_choices(owner_id,revision);
alter table jojo_account_settings.artwork_heads enable row level security;
alter table jojo_account_settings.artwork_heads force row level security;
alter table jojo_account_settings.artwork_choices enable row level security;
alter table jojo_account_settings.artwork_choices force row level security;
create policy artwork_head_select on jojo_account_settings.artwork_heads for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy artwork_head_insert on jojo_account_settings.artwork_heads for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy artwork_head_update on jojo_account_settings.artwork_heads for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
create policy artwork_choice_select on jojo_account_settings.artwork_choices for select to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner());
create policy artwork_choice_insert on jojo_account_settings.artwork_choices for insert to jojo_account_settings_rpc
    with check(owner_id=jojo_account_settings.request_owner());
create policy artwork_choice_update on jojo_account_settings.artwork_choices for update to jojo_account_settings_rpc
    using(owner_id=jojo_account_settings.request_owner()) with check(owner_id=jojo_account_settings.request_owner());
revoke all on jojo_account_settings.artwork_heads,jojo_account_settings.artwork_choices from public,anon,authenticated,service_role;
grant select,insert on jojo_account_settings.artwork_heads,jojo_account_settings.artwork_choices to jojo_account_settings_rpc;
grant update(revision) on jojo_account_settings.artwork_heads to jojo_account_settings_rpc;
grant update(image,tmdb_id,media_type,stamp,revision,last_device) on jojo_account_settings.artwork_choices to jojo_account_settings_rpc;

create function public.account_home_artwork_sync(p_device uuid,p_proof text,p_after bigint,p_ops jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare
    own uuid:=jojo_account_settings.require_device(p_device,p_proof);
    item jsonb; choice_key text; choice_image text; choice_id integer; choice_type text; choice_stamp bigint;
    previous jojo_account_settings.artwork_choices%rowtype;
    head bigint:=0; next_revision bigint; result jsonb;
begin
    if p_after is null or p_after<0 or jsonb_typeof(p_ops) is distinct from 'array'
       or jsonb_array_length(p_ops)>50 or octet_length(p_ops::text)>32768 then
        raise exception using errcode='22023',message='HOME_ARTWORK_BATCH_INVALID';
    end if;
    perform 1 from jojo_account_settings.accounts where owner_id=own and initialized for update;
    if not found then raise exception using errcode='42501',message='HOME_ARTWORK_ACCOUNT_NOT_READY'; end if;
    if jsonb_array_length(p_ops)>0 then
        insert into jojo_account_settings.artwork_heads(owner_id) values(own) on conflict do nothing;
    end if;
    for item in select value from jsonb_array_elements(p_ops) loop
        if jsonb_typeof(item)<>'object' or (select count(*) from jsonb_object_keys(item))<>5
           or not item ?& array['key','image','tmdb_id','media_type','stamp']
           or jsonb_typeof(item->'key')<>'string'
           or (item->>'key') !~ '^home_videos_(programme_(portrait|hero)_artwork|series_episode_artwork)_[0-9a-f]{1,8}$'
           or jsonb_typeof(item->'image')<>'string'
           or ((item->>'image')<>'' and (item->>'image') !~ '^((original|w[0-9]{2,4}):)?[A-Za-z0-9]{1,128}\.(jpg|png|webp)$')
           or jsonb_typeof(item->'media_type')<>'string'
           or jsonb_typeof(item->'tmdb_id')<>'number' or (item->>'tmdb_id') !~ '^[0-9]+$'
           or jsonb_typeof(item->'stamp')<>'number' or (item->>'stamp') !~ '^[0-9]+$' then
            raise exception using errcode='22023',message='HOME_ARTWORK_INVALID';
        end if;
        if (item->>'stamp')::numeric not between 1 and 253402300799999
           or (item->>'tmdb_id')::numeric>2147483647 then
            raise exception using errcode='22023',message='HOME_ARTWORK_NUMBER_INVALID';
        end if;
        choice_key:=item->>'key'; choice_image:=item->>'image'; choice_id:=(item->>'tmdb_id')::integer;
        choice_type:=item->>'media_type'; choice_stamp:=(item->>'stamp')::bigint;
        if not ((choice_id=0 and choice_type='') or (choice_id>0 and choice_type in ('movie','tv')))
           or (choice_image='' and choice_id<>0) then
            raise exception using errcode='22023',message='HOME_ARTWORK_METADATA_INVALID';
        end if;
        select * into previous from jojo_account_settings.artwork_choices where owner_id=own and key=choice_key;
        if found and (previous.stamp,previous.image collate "C",previous.tmdb_id,previous.media_type collate "C")
            >= (choice_stamp,choice_image collate "C",choice_id,choice_type collate "C") then continue; end if;
        if previous.owner_id is null and (select count(*) from jojo_account_settings.artwork_choices where owner_id=own)>=2000 then
            raise exception using errcode='54000',message='HOME_ARTWORK_LIMIT';
        end if;
        update jojo_account_settings.artwork_heads set revision=revision+1 where owner_id=own returning revision into strict next_revision;
        insert into jojo_account_settings.artwork_choices(owner_id,key,image,tmdb_id,media_type,stamp,revision,last_device)
            values(own,choice_key,choice_image,choice_id,choice_type,choice_stamp,next_revision,p_device)
            on conflict(owner_id,key) do update set image=excluded.image,tmdb_id=excluded.tmdb_id,media_type=excluded.media_type,
                stamp=excluded.stamp,revision=excluded.revision,last_device=excluded.last_device;
    end loop;
    select revision into head from jojo_account_settings.artwork_heads where owner_id=own;
    head:=coalesce(head,0);
    if p_after>head then raise exception using errcode='22023',message='HOME_ARTWORK_CURSOR_INVALID'; end if;
    select coalesce(jsonb_agg(jsonb_build_object('key',key,'image',image,'tmdb_id',tmdb_id,'media_type',media_type,
            'stamp',stamp,'revision',revision) order by revision),'[]'::jsonb),coalesce(max(revision),p_after)
        into result,next_revision from (select key,image,tmdb_id,media_type,stamp,revision
            from jojo_account_settings.artwork_choices where owner_id=own and revision>p_after order by revision limit 500) page_rows;
    return jsonb_build_object('schema',1,'next_cursor',next_revision,'head_revision',head,'rows',result);
end;
$$;
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
alter function public.account_home_artwork_sync(uuid,text,bigint,jsonb) owner to jojo_account_settings_rpc;
revoke create on schema public from jojo_account_settings_rpc;
set local role jojo_account_settings_rpc;
revoke all on function public.account_home_artwork_sync(uuid,text,bigint,jsonb) from public,anon,authenticated,service_role;
grant execute on function public.account_home_artwork_sync(uuid,text,bigint,jsonb) to authenticated;
reset role;
grant jojo_account_settings_rpc to postgres with set false;
do $permissions$
begin
    if has_function_privilege('anon','public.account_home_artwork_sync(uuid,text,bigint,jsonb)','EXECUTE')
       or not has_function_privilege('authenticated','public.account_home_artwork_sync(uuid,text,bigint,jsonb)','EXECUTE')
       or has_table_privilege('authenticated','jojo_account_settings.artwork_choices','SELECT') then
        raise exception 'HOME_ARTWORK_PERMISSIONS_INVALID';
    end if;
end;
$permissions$;
notify pgrst,'reload schema';
commit;
