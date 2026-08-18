-- E10: sample domain module — code-first typed aggregate (ApprovalRequest), KHÔNG đi qua Generic CRUD
create schema if not exists domain;
create table domain.approval_request(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  title varchar(240) not null,
  description text not null default '',
  status varchar(20) not null default 'DRAFT' check (status in ('DRAFT','SUBMITTED','APPROVED','REJECTED','CANCELLED')),
  requested_by uuid not null references identity.account(id),
  decided_by uuid references identity.account(id),
  decided_at timestamptz,
  decision_note text,
  priority varchar(10) not null default 'MEDIUM' check (priority in ('LOW','MEDIUM','HIGH','URGENT')),
  amount decimal(18,2),
  custom_attributes jsonb not null default '{}',
  version int not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index approval_request_tenant_idx on domain.approval_request(tenant_id, status, updated_at desc);
alter table domain.approval_request enable row level security;
alter table domain.approval_request force row level security;
drop policy if exists tenant_isolation on domain.approval_request;
create policy tenant_isolation on domain.approval_request
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

insert into platform.resource_descriptor(name, storage_mode, owner_module, record_count, schema_version, resource_type)
values ('Approval Request', 'DOMAIN', 'approval-domain', 0, 'v1', 'approval-request')
on conflict (resource_type) do nothing;
insert into platform.module(name, module_key, version, status, description, metric, sort_order)
values ('Approval Domain', 'approval-domain', '1.0.0', 'HEALTHY', 'Code-first sample: typed aggregate với domain invariants', '', 90)
on conflict (module_key) do update set name = excluded.name, description = excluded.description;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT USAGE ON SCHEMA domain TO core_app;
    GRANT SELECT, INSERT, UPDATE ON domain.approval_request TO core_app;
    RAISE NOTICE 'E10 grants ok';
  END IF;
END $$;
