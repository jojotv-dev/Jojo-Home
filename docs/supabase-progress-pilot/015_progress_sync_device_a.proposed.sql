-- PROPOSITION NON EXECUTEE : ajout de A (.150) a l'espace cloud deja ouvert pour B/C.
-- Projet jojo-progress-pilot-test / jtdnwdbapkdkpgtceauh uniquement.
-- Une seule ligne UPDATE allowed_devices ; aucune migration, progression importee ou activation locale.
-- B (.119) et C (.163) restent autorises ; aucune commande n'est envoyee a C.
-- Apres accord explicite seulement, ajouter apres BEGIN :
-- SET LOCAL jojo.cloud_scope_project_ref = 'jtdnwdbapkdkpgtceauh';
-- SET LOCAL jojo.cloud_scope_approved = 'APPROVED_ADD_DEVICE_A_015';
begin;
set local statement_timeout = '15s';
set local lock_timeout = '3s';

do $scope$
declare
    v_owner constant uuid := '2012b04b-1bf1-4421-82d2-a74234bbe81c';
    v_profile constant uuid := '2e43435a-eda7-44d0-8d95-476a2a973446';
    v_epoch constant uuid := '290de73c-7b42-4190-bc68-18842688e7d2';
    v_a constant uuid := 'f8648865-cd01-4be7-bd4a-2e2c7b6659ba';
    v_b constant uuid := '12bb7efd-7782-4903-8fd1-052ea5db924d';
    v_c constant uuid := '5b7eb5cb-e6a8-42bc-8214-2178e22b4dd4';
    v_before jojo_progress_pilot.cloud_progress_scopes%rowtype;
    v_after jojo_progress_pilot.cloud_progress_scopes%rowtype;
    v_count integer;
begin
    if current_user <> 'postgres'
       or current_setting('jojo.cloud_scope_project_ref', true) is distinct from 'jtdnwdbapkdkpgtceauh'
       or current_setting('jojo.cloud_scope_approved', true) is distinct from 'APPROVED_ADD_DEVICE_A_015' then
        raise exception 'CLOUD_SCOPE_SEPARATE_APPROVAL_REQUIRED';
    end if;

    select * into strict v_before from jojo_progress_pilot.cloud_progress_scopes
      where owner_id = v_owner and profile_binding_id = v_profile and key_epoch = v_epoch
      for update;
    if not v_before.enabled or v_before.allowed_devices is distinct from array[v_b, v_c]
       or v_before.head_revision <> 15 or v_before.receipt_count <> 15 then
        raise exception 'CLOUD_SCOPE_015_BASELINE_CHANGED';
    end if;
    if not exists (select 1 from jojo_progress_pilot.pilot_profile_bindings
        where owner_id = v_owner and id = v_profile and device_a = v_a and device_b = v_b and device_c = v_c)
       or (select count(*) from jojo_progress_pilot.pilot_devices d
         join auth.sessions s on s.id = d.auth_session_id and s.user_id = d.owner_id
         where d.owner_id = v_owner and d.id = any(array[v_a, v_b]) and d.active
           and (s.not_after is null or s.not_after > now() + interval '2 minutes')) <> 2 then
        raise exception 'CLOUD_SCOPE_015_ASSOCIATION_OR_SESSION_INVALID';
    end if;

    update jojo_progress_pilot.cloud_progress_scopes
       set allowed_devices = v_before.allowed_devices || array[v_a]
     where owner_id = v_owner and profile_binding_id = v_profile and key_epoch = v_epoch;
    get diagnostics v_count = row_count;
    select * into strict v_after from jojo_progress_pilot.cloud_progress_scopes
      where owner_id = v_owner and profile_binding_id = v_profile and key_epoch = v_epoch;
    if v_count <> 1 or v_after.allowed_devices is distinct from array[v_b, v_c, v_a]
       or (to_jsonb(v_before) - 'allowed_devices') is distinct from (to_jsonb(v_after) - 'allowed_devices') then
        raise exception 'CLOUD_SCOPE_015_UNEXPECTED_CHANGE';
    end if;
end;
$scope$;

select key_epoch, head_revision, receipt_count, cardinality(allowed_devices) as allowed_device_count
from jojo_progress_pilot.cloud_progress_scopes
where owner_id = '2012b04b-1bf1-4421-82d2-a74234bbe81c'::uuid
  and profile_binding_id = '2e43435a-eda7-44d0-8d95-476a2a973446'::uuid
  and key_epoch = '290de73c-7b42-4190-bc68-18842688e7d2'::uuid;
commit;
