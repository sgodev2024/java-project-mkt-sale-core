create table platform.tenant(
  id uuid primary key default gen_random_uuid(),
  tenant_key varchar(80) not null unique,
  name varchar(160) not null,
  status varchar(20) not null default 'ACTIVE',
  created_at timestamptz not null default now()
);
insert into platform.tenant(tenant_key,name) values('default','Core Production') on conflict(tenant_key) do nothing;

alter table identity.account add column tenant_id uuid;
update identity.account set tenant_id=(select id from platform.tenant where tenant_key='default') where tenant_id is null;
alter table identity.account alter column tenant_id set not null;
alter table identity.account add constraint account_tenant_fk foreign key(tenant_id) references platform.tenant(id);
create unique index account_tenant_email_uidx on identity.account(tenant_id,email);

create table identity.role(
  id uuid primary key default gen_random_uuid(), tenant_id uuid not null references platform.tenant(id),
  code varchar(100) not null, name varchar(160) not null, system_role boolean not null default false,
  created_at timestamptz not null default now(), unique(tenant_id,code)
);
create table identity.account_role(
  tenant_id uuid not null references platform.tenant(id), account_id uuid not null references identity.account(id),
  role_id uuid not null references identity.role(id), created_at timestamptz not null default now(),
  primary key(tenant_id,account_id,role_id)
);
create table identity.policy(
  id uuid primary key default gen_random_uuid(), tenant_id uuid not null references platform.tenant(id),
  code varchar(120) not null, resource_type varchar(120) not null, action varchar(80) not null,
  effect varchar(10) not null check(effect in('ALLOW','DENY')), condition_json jsonb not null default '{}',
  version int not null default 1, enabled boolean not null default true, unique(tenant_id,code,version)
);
create table identity.role_policy(
  tenant_id uuid not null references platform.tenant(id), role_id uuid not null references identity.role(id),
  policy_id uuid not null references identity.policy(id), primary key(tenant_id,role_id,policy_id)
);

with t as (select id from platform.tenant where tenant_key='default'),
r as (insert into identity.role(tenant_id,code,name,system_role) select id,'platform-admin','Platform Administrator',true from t returning id,tenant_id),
p as (insert into identity.policy(tenant_id,code,resource_type,action,effect) select id,'platform-admin-all','*','*','ALLOW' from t returning id,tenant_id)
insert into identity.role_policy(tenant_id,role_id,policy_id) select r.tenant_id,r.id,p.id from r,p;
insert into identity.account_role(tenant_id,account_id,role_id)
select a.tenant_id,a.id,r.id from identity.account a join identity.role r on r.tenant_id=a.tenant_id and r.code='platform-admin' where a.role='PLATFORM_ADMIN';

create schema if not exists dynamic_resource;
create table dynamic_resource.definition(
  id uuid primary key default gen_random_uuid(), tenant_id uuid not null references platform.tenant(id),
  resource_key varchar(100) not null, name varchar(160) not null, version int not null default 1,
  schema_json jsonb not null, status varchar(20) not null default 'ACTIVE',
  created_by uuid not null references identity.account(id), created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  unique(tenant_id,resource_key)
);
create table dynamic_resource.record(
  id uuid primary key default gen_random_uuid(), tenant_id uuid not null references platform.tenant(id),
  definition_id uuid not null references dynamic_resource.definition(id), data jsonb not null,
  record_version int not null default 1, owner_subject_id uuid references identity.account(id),
  status varchar(20) not null default 'ACTIVE', created_by uuid not null references identity.account(id),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table dynamic_resource.revision(
  id uuid primary key default gen_random_uuid(), tenant_id uuid not null references platform.tenant(id),
  record_id uuid not null references dynamic_resource.record(id), record_version int not null,
  operation varchar(20) not null, data jsonb not null, actor_id uuid not null references identity.account(id),
  occurred_at timestamptz not null default now(), unique(record_id,record_version)
);
create index dynamic_record_lookup_idx on dynamic_resource.record(tenant_id,definition_id,status,updated_at desc,id);
create index dynamic_record_data_gin_idx on dynamic_resource.record using gin(data);
create index dynamic_revision_lookup_idx on dynamic_resource.revision(tenant_id,record_id,record_version desc);
