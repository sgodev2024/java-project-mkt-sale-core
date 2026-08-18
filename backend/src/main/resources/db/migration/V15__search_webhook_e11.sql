-- E11-S01: PostgreSQL full-text search trên dynamic records
alter table dynamic_resource.record add column if not exists search_vector tsvector
  generated always as (
    to_tsvector('simple', coalesce(data::text, ''))
  ) stored;
create index if not exists dynamic_record_fts_idx on dynamic_resource.record using gin(search_vector);

-- E11-S02: CSV import idempotent — batch tracking chống duplicate khi retry
create table if not exists dynamic_resource.import_batch(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  definition_id uuid not null references dynamic_resource.definition(id),
  batch_key varchar(200) not null,
  imported_count int not null default 0,
  failed_count int not null default 0,
  created_at timestamptz not null default now(),
  unique(tenant_id, definition_id, batch_key)
);

-- E11-S03: webhook delivery với SSRF guard
create table if not exists platform.webhook_endpoint(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  url varchar(500) not null,
  event_types text[] not null default '{}',
  secret_hash char(64),
  status varchar(20) not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
  last_delivery_at timestamptz,
  last_status varchar(20),
  failure_count int not null default 0,
  created_at timestamptz not null default now()
);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE ON dynamic_resource.import_batch TO core_app;
    GRANT SELECT, INSERT, UPDATE ON platform.webhook_endpoint TO core_app;
    RAISE NOTICE 'E11 grants ok';
  END IF;
END $$;
