-- E9-S01: phiên bản schema + cổng breaking-change phải có migration
alter table dynamic_resource.definition add column if not exists previous_schema jsonb;
alter table dynamic_resource.definition add column if not exists pending_schema jsonb;
alter table dynamic_resource.definition add column if not exists migration_state varchar(20) not null default 'NONE';

-- E9-S04: index do platform sinh (field key whitelisted), KHÔNG nhận SQL từ client
create table if not exists dynamic_resource.managed_index(
  id uuid primary key default gen_random_uuid(),
  definition_id uuid not null references dynamic_resource.definition(id),
  field_key varchar(80) not null,
  index_name varchar(160) not null unique,
  status varchar(20) not null default 'ACTIVE',
  created_at timestamptz not null default now(),
  unique(definition_id, field_key)
);

-- E9-S05: custom field tách riêng, không bao giờ đè typed field của schema
alter table dynamic_resource.record add column if not exists custom_attributes jsonb not null default '{}';

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE ON dynamic_resource.managed_index TO core_app;
    RAISE NOTICE 'E13 grants ok';
  END IF;
END $$;
