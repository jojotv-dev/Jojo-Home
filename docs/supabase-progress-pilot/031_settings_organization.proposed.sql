-- PROPOSITION NON EXECUTEE : accord distinct avant application dans le projet de test.
-- Trois nouvelles cles partagees ; aucune valeur reelle importee et aucune table ajoutee.
-- Les anciennes APK conservent leur lecture v1 ; la nouvelle APK utilise read_v2.
-- Ajouter APRES accord, apres BEGIN et avant DO :
-- SET LOCAL jojo.account_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.approved_031 = 'YES';
begin;
set local lock_timeout='5s';
set local statement_timeout='60s';
do $guard$
begin
    if current_user<>'postgres'
       or current_setting('jojo.account_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.approved_031',true) is distinct from 'YES' then
        raise exception 'SETTINGS_ORGANIZATION_APPROVAL_REQUIRED';
    end if;
    if to_regprocedure('public.account_settings_read_v2(uuid,text,bigint)') is not null
       or pg_has_role('postgres','jojo_account_settings_rpc','SET')
       or has_schema_privilege('jojo_account_settings_rpc','public','CREATE')
       or (select count(*) from jojo_account_settings.catalog)<>227
       or exists(select 1 from jojo_account_settings.catalog where key in ('home.home_section_order','iptv.sourceOrder','iptv.organization'))
       or md5(pg_get_functiondef('public.account_settings_read(uuid,text,bigint)'::regprocedure))<>'af498fb814411dbcdf3847e2044fc45b'
       or md5(pg_get_functiondef('jojo_account_settings.validate_value(text,text,jsonb)'::regprocedure))<>'becde657e337a8973a35ad1bd7d99bf9' then
        raise exception 'SETTINGS_ORGANIZATION_PREFLIGHT_CHANGED';
    end if;
end;
$guard$;
alter table jojo_account_settings.catalog drop constraint catalog_kind_check;
alter table jojo_account_settings.catalog add constraint catalog_kind_check
    check(kind in ('boolean','integer','enum','language','decimal','package','packages','enum_list','profile','uuid_list','organization'));
insert into jojo_account_settings.catalog(key,scope,kind,choices,minimum,maximum) values
 ('home.home_section_order','account','enum_list','["shortcuts","apps","resumes","movies","series","live"]'::jsonb,0,0),
 ('iptv.sourceOrder','account','uuid_list','[]'::jsonb,0,0),
 ('iptv.organization','account','organization','[]'::jsonb,0,0);

create function jojo_account_settings.organization_valid(p_value jsonb) returns boolean
language plpgsql immutable strict security invoker set search_path='' as $organization$
declare entry record; field text; raw text; list jsonb; item jsonb;
    item_pattern text:='^iptv:[A-Za-z0-9_-]{1,64}:(live|movie|series):[0-9a-f]{64}$';
begin
    if jsonb_typeof(p_value)<>'object' then return false; end if;
    if octet_length(p_value::text)>65536
       or (select count(*) from jsonb_object_keys(p_value))>512 then return false; end if;
    for entry in select key,value from jsonb_each(p_value) loop
        if entry.key !~ '^organization:[0-9a-f]{64}:(hidden|groupName|groupSort|channelSort|itemOrder|(newGroupsVisible|knownGroups|groupOrder):(LIVE|MOVIE|SERIES)|(channelHidden|channelName|channelOriginalName):iptv:[A-Za-z0-9_-]{1,64}:(live|movie|series):[0-9a-f]{64})$'
           or jsonb_typeof(entry.value)<>'string' then return false; end if;
        field:=regexp_replace(entry.key,'^organization:[0-9a-f]{64}:','');
        raw:=entry.value#>>'{}';
        if field='hidden' or field like 'channelHidden:%' or field like 'newGroupsVisible:%' then
            if raw not in ('true','false') then return false; end if;
        elsif field='groupName' or field like 'channelName:%' or field like 'channelOriginalName:%' then
            if length(raw)>160 or raw ~ '[[:cntrl:]]' or position('://' in raw)>0 then return false; end if;
        elsif field='groupSort' then
            if raw not in ('playlist','name','manual') then return false; end if;
        elsif field='channelSort' then
            if raw not in ('playlist','name','nameDescending','added','rating','watchTime','manual') then return false; end if;
        else
            begin list:=raw::jsonb; exception when invalid_text_representation then return false; end;
            if jsonb_typeof(list)<>'array' then return false; end if;
            if jsonb_array_length(list)>512 or (select count(*)<>count(distinct x) from jsonb_array_elements(list) x) then return false; end if;
            for item in select value from jsonb_array_elements(list) loop
                if jsonb_typeof(item)<>'string' then return false; end if;
                if field='itemOrder' then
                    if (item#>>'{}') !~ item_pattern then return false; end if;
                elsif (item#>>'{}') !~ '^[A-Za-z0-9_.:*-]{1,128}$' then return false;
                end if;
            end loop;
        end if;
    end loop;
    return true;
end;
$organization$;
revoke all on function jojo_account_settings.organization_valid(jsonb) from public,anon,authenticated;
grant execute on function jojo_account_settings.organization_valid(jsonb) to jojo_account_settings_rpc;

CREATE OR REPLACE FUNCTION jojo_account_settings.validate_value(p_key text, p_scope text, p_value jsonb)
 RETURNS void
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare spec jojo_account_settings.catalog%rowtype; value_text text; valid boolean:=false;
begin
    select * into spec from jojo_account_settings.catalog where key=p_key and scope=p_scope;
    if not found or p_value is null or octet_length(p_value::text)>65536 then
        raise exception using errcode='22023',message='ACCOUNT_SETTING_NOT_ALLOWED';
    end if;
    value_text:=p_value#>>'{}';
    if p_value='null'::jsonb then valid:=spec.kind<>'profile';
    elsif spec.kind='boolean' then valid:=jsonb_typeof(p_value)='boolean';
    elsif spec.kind='integer' and jsonb_typeof(p_value)='number' and value_text ~ '^-?[0-9]+$' then
        valid:=value_text::numeric between spec.minimum and spec.maximum;
    elsif spec.kind='enum' then valid:=jsonb_typeof(p_value)='string' and spec.choices ? value_text;
    elsif spec.kind='language' then valid:=jsonb_typeof(p_value)='string' and value_text ~ '^(?:[a-z]{2,3}(?:-[A-Za-z]{2,4})?|device|none|system|original|auto|off)$';
    elsif spec.kind='decimal' and jsonb_typeof(p_value)='string' and value_text ~ '^[0-9]+(?:\.[0-9]+)?$' then
        valid:=value_text::numeric between spec.minimum and spec.maximum;
    elsif spec.kind='package' then
        valid:=jsonb_typeof(p_value)='string' and (value_text='' or length(value_text)<=200 and value_text ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$');
    elsif spec.kind='enum_list' and jsonb_typeof(p_value)='array' then
        valid:=jsonb_array_length(p_value)<=jsonb_array_length(spec.choices) and not exists(select 1 from jsonb_array_elements(p_value) x
            where jsonb_typeof(x)<>'string' or not (spec.choices ? (x#>>'{}')))
            and (select count(*)=count(distinct x) from jsonb_array_elements(p_value) x);
    elsif spec.kind='packages' and jsonb_typeof(p_value)='array' then
        valid:=jsonb_array_length(p_value)<=200 and not exists(select 1 from jsonb_array_elements(p_value) x
            where jsonb_typeof(x)<>'string' or length(x#>>'{}')>200 or (x#>>'{}') !~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
            and (select count(*)=count(distinct x) from jsonb_array_elements(p_value) x);
    elsif spec.kind='uuid_list' and jsonb_typeof(p_value)='array' then
        valid:=jsonb_array_length(p_value)<=200 and not exists(select 1 from jsonb_array_elements(p_value) x
            where jsonb_typeof(x)<>'string' or (x#>>'{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
            and (select count(*)=count(distinct x) from jsonb_array_elements(p_value) x);
    elsif spec.kind='organization' then valid:=jojo_account_settings.organization_valid(p_value);
    elsif spec.kind='profile' and jsonb_typeof(p_value)='object' then
        valid:=(select count(*)=4 from jsonb_object_keys(p_value)) and p_value ?& array['name','color','avatar','primary']
            and jsonb_typeof(p_value->'name')='string' and length(p_value->>'name') between 1 and case when p_key='profiles.iptv' then 40 else 64 end
            and btrim(p_value->>'name')<>'' and (p_value->>'name') !~ '[[:cntrl:]]'
            and jsonb_typeof(p_value->'color')='string' and (p_value->>'color') ~ '^#[0-9a-fA-F]{6}$'
            and jsonb_typeof(p_value->'avatar')='string' and spec.choices ? (p_value->>'avatar')
            and jsonb_typeof(p_value->'primary')='boolean';
    end if;
    if valid is distinct from true then raise exception using errcode='22023',message='ACCOUNT_VALUE_INVALID'; end if;
end;
$function$;

-- Le lecteur historique exclut uniquement les nouvelles cles incomprises des anciennes APK.
-- Comme dans 016 : utiliser le proprietaire sans laisser de privilege administratif actif.
grant jojo_account_settings_rpc to postgres with set true;
grant create on schema public to jojo_account_settings_rpc;
set local role jojo_account_settings_rpc;
CREATE OR REPLACE FUNCTION public.account_settings_read(p_device uuid, p_proof text, p_after bigint DEFAULT 0)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare v_owner uuid; account jojo_account_settings.accounts%rowtype; rows jsonb;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    -- Writers lock the same account row; SHARE keeps this head and its rows consistent.
    select * into strict account from jojo_account_settings.accounts where owner_id=v_owner for share;
    if p_after is null or p_after<0 or p_after>account.head_revision then
        raise exception using errcode='22023',message='ACCOUNT_CURSOR_INVALID';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('scope',e.scope,'subject',e.subject,'key',e.key,'value',e.value,'revision',e.revision)
        order by e.revision),'[]'::jsonb) into rows from jojo_account_settings.entries e
        where e.owner_id=v_owner and e.revision>p_after and (e.scope<>'device' or e.subject=p_device) and e.key not in ('home.home_section_order','iptv.sourceOrder','iptv.organization');
    if jsonb_array_length(rows)>2000 then raise exception using errcode='54000',message='ACCOUNT_PAGE_LIMIT'; end if;
    return jsonb_build_object('schema',account.schema_version,'initialized',account.initialized,'head_revision',account.head_revision,'rows',rows);
end;
$function$;

-- Meme verification de compte, session et preuve ; memes filtres d'installation et curseurs.
CREATE OR REPLACE FUNCTION public.account_settings_read_v2(p_device uuid, p_proof text, p_after bigint DEFAULT 0)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare v_owner uuid; account jojo_account_settings.accounts%rowtype; rows jsonb;
begin
    v_owner:=jojo_account_settings.require_device(p_device,p_proof);
    -- Writers lock the same account row; SHARE keeps this head and its rows consistent.
    select * into strict account from jojo_account_settings.accounts where owner_id=v_owner for share;
    if p_after is null or p_after<0 or p_after>account.head_revision then
        raise exception using errcode='22023',message='ACCOUNT_CURSOR_INVALID';
    end if;
    select coalesce(jsonb_agg(jsonb_build_object('scope',e.scope,'subject',e.subject,'key',e.key,'value',e.value,'revision',e.revision)
        order by e.revision),'[]'::jsonb) into rows from jojo_account_settings.entries e
        where e.owner_id=v_owner and e.revision>p_after and (e.scope<>'device' or e.subject=p_device);
    if jsonb_array_length(rows)>2000 then raise exception using errcode='54000',message='ACCOUNT_PAGE_LIMIT'; end if;
    return jsonb_build_object('schema',account.schema_version,'initialized',account.initialized,'head_revision',account.head_revision,'rows',rows);
end;
$function$;

revoke all on function public.account_settings_read_v2(uuid,text,bigint) from public,anon,authenticated;
grant execute on function public.account_settings_read_v2(uuid,text,bigint) to authenticated;
reset role;
revoke create on schema public from jojo_account_settings_rpc;
grant jojo_account_settings_rpc to postgres with set false;
-- Les CREATE OR REPLACE conservent proprietaires et permissions des fonctions existantes.
notify pgrst,'reload schema';
commit;
