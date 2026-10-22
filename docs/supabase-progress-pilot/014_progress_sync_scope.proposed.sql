-- PROPOSITION NON EXECUTEE : une seule ouverture d'espace vide, apres 012/013 valides.
-- Projet de test jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Ne modifie ni session, ni cle, ni association appareil. Aucune progression importee.
-- Autorise les RPC cloud uniquement pour B (.119) et C (.163) ; A (.150) reste exclue.
-- L'activation locale de chaque appareil requiert encore son accord distinct.
-- Ajouter apres accord dans la meme transaction, avant le premier DO :
-- SET LOCAL jojo.cloud_scope_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.cloud_scope_approved = 'APPROVED_EMPTY_SCOPE_014';
begin;
set local statement_timeout='15s';
set local lock_timeout='3s';
do $scope$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_profile constant uuid := '2e43435a-eda7-44d0-8d95-476a2a973446';
    v_epoch constant uuid := '290de73c-7b42-4190-bc68-18842688e7d2';
    v_devices constant uuid[] := array['12bb7efd-7782-4903-8fd1-052ea5db924d'::uuid,
        '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4'::uuid];
begin
    if current_user<>'postgres'
       or current_setting('jojo.cloud_scope_project_ref',true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.cloud_scope_approved',true) is distinct from 'APPROVED_EMPTY_SCOPE_014' then
        raise exception 'CLOUD_SCOPE_SEPARATE_APPROVAL_REQUIRED';
    end if;
    if not exists (select 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id=v_owner and id=v_profile and device_b=v_devices[1] and device_c=v_devices[2])
       or (select count(*) from jojo_progress_pilot.pilot_devices d
        join auth.sessions s on s.id=d.auth_session_id and s.user_id=d.owner_id
        where d.owner_id=v_owner and d.id=any(v_devices) and d.active
          and (s.not_after is null or s.not_after>now()))<>2
       or exists (select 1 from jojo_progress_pilot.cloud_progress_scopes
        where owner_id=v_owner and profile_binding_id=v_profile) then
        raise exception 'CLOUD_SCOPE_ASSOCIATION_OR_EMPTY_SPACE_PRECONDITION_FAILED';
    end if;
    insert into jojo_progress_pilot.cloud_progress_scopes
        (owner_id,profile_binding_id,key_epoch,enabled,allowed_devices)
        values (v_owner,v_profile,v_epoch,true,v_devices);
    if exists (select 1 from jojo_progress_pilot.cloud_watch_progress
        where owner_id=v_owner and profile_binding_id=v_profile and key_epoch=v_epoch)
       or exists (select 1 from jojo_progress_pilot.cloud_progress_receipts
        where owner_id=v_owner and profile_binding_id=v_profile and key_epoch=v_epoch) then
        raise exception 'CLOUD_SCOPE_MUST_REMAIN_EMPTY';
    end if;
end;
$scope$;
select key_epoch,head_revision,receipt_count,cardinality(allowed_devices) as allowed_device_count
    from jojo_progress_pilot.cloud_progress_scopes where key_epoch='290de73c-7b42-4190-bc68-18842688e7d2'::uuid;
commit;
