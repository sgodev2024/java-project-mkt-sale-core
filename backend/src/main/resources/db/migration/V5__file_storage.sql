alter table files.file_object add column tenant_id uuid references platform.tenant(id);
alter table files.file_object add column storage_key varchar(255);
alter table files.file_object add column owner_subject_id uuid references identity.account(id);
alter table files.file_object add column created_by uuid references identity.account(id);
alter table files.file_object add column created_at timestamptz not null default now();
alter table files.file_object add column deleted_at timestamptz;
update files.file_object set tenant_id=(select id from platform.tenant where tenant_key='default') where tenant_id is null;
alter table files.file_object alter column tenant_id set not null;
create unique index file_storage_key_uidx on files.file_object(storage_key) where storage_key is not null;
create index file_tenant_lookup_idx on files.file_object(tenant_id,status,updated_at desc,id);

insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json)
select id,'file-owner','FILE','*','ALLOW','{"ownerOnly":true}'::jsonb from platform.tenant on conflict(tenant_id,code,version) do nothing;
insert into identity.role_policy(tenant_id,role_id,policy_id)
select r.tenant_id,r.id,p.id from identity.role r join identity.policy p on p.tenant_id=r.tenant_id
where r.code='application-user' and p.code='file-owner' on conflict do nothing;
update identity.permission_revision set revision=revision+1,updated_at=now();
