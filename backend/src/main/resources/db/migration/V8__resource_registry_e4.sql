-- E4-S01: Resource Registry — descriptor có identity riêng (resource_type) để phát hiện drift
alter table platform.resource_descriptor add column if not exists resource_type varchar(120);
alter table platform.resource_descriptor add column if not exists supported_actions varchar(240) not null default 'READ,CREATE,UPDATE,DELETE';
alter table platform.resource_descriptor add column if not exists audit_policy varchar(60) not null default 'ALWAYS';
alter table platform.resource_descriptor add column if not exists data_classification varchar(30);
update platform.resource_descriptor set resource_type = lower(regexp_replace(trim(trailing '-' from regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g')), '-', '', 'g'))
  || '-' || left(owner_module, 12) where resource_type is null;
create unique index if not exists resource_descriptor_type_uidx on platform.resource_descriptor(resource_type);

-- E4-S05: classification gate cho dynamic definition — thiếu classification thì không ACTIVE
alter table dynamic_resource.definition add column if not exists data_classification varchar(30);
-- definition ACTIVE hiện có được coi là đã duyệt (legacy), definition mới phải qua gate
update dynamic_resource.definition set data_classification = 'INTERNAL' where data_classification is null and status = 'ACTIVE';
