create table identity.permission_revision(
  tenant_id uuid primary key references platform.tenant(id),
  revision bigint not null default 1,
  updated_at timestamptz not null default now()
);
insert into identity.permission_revision(tenant_id) select id from platform.tenant on conflict do nothing;

insert into identity.role(tenant_id,code,name,system_role)
select id,'application-user','Application User',true from platform.tenant on conflict(tenant_id,code) do nothing;
insert into identity.policy(tenant_id,code,resource_type,action,effect,condition_json)
select id,'dynamic-record-owner','DYNAMIC_RECORD','*','ALLOW','{"ownerOnly":true}'::jsonb from platform.tenant on conflict(tenant_id,code,version) do nothing;
insert into identity.role_policy(tenant_id,role_id,policy_id)
select r.tenant_id,r.id,p.id from identity.role r join identity.policy p on p.tenant_id=r.tenant_id
where r.code='application-user' and p.code='dynamic-record-owner' on conflict do nothing;

create index if not exists account_role_account_idx on identity.account_role(tenant_id,account_id);
create index if not exists role_policy_role_idx on identity.role_policy(tenant_id,role_id);
create index if not exists policy_evaluation_idx on identity.policy(tenant_id,resource_type,action,enabled,effect);
