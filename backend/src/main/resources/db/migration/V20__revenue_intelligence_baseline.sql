-- Revenue Intelligence business module baseline.
create schema if not exists revenue_intelligence;

create table revenue_intelligence.import_batch(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  dataset_type varchar(24) not null check (dataset_type in ('CUSTOMERS','ORDERS','AD_SPEND','TOUCHPOINTS')),
  source_name varchar(255) not null,
  checksum_sha256 char(64) not null,
  status varchar(32) not null default 'PROCESSING' check (status in ('PROCESSING','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
  total_rows int not null default 0,
  accepted_rows int not null default 0,
  rejected_rows int not null default 0,
  created_by uuid references identity.account(id),
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  unique(tenant_id, dataset_type, checksum_sha256)
);

create table revenue_intelligence.import_error(
  id bigserial primary key,
  tenant_id uuid not null references platform.tenant(id),
  batch_id uuid not null references revenue_intelligence.import_batch(id) on delete cascade,
  row_number int not null,
  error_code varchar(80) not null,
  message varchar(500) not null,
  raw_payload jsonb not null default '{}',
  created_at timestamptz not null default now()
);

create table revenue_intelligence.channel(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  code varchar(80) not null,
  name varchar(160) not null,
  channel_type varchar(20) not null default 'UNKNOWN' check (channel_type in ('PAID','OWNED','ORGANIC','REFERRAL','DIRECT','UNKNOWN')),
  created_at timestamptz not null default now(),
  unique(tenant_id, code)
);

create table revenue_intelligence.campaign(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  channel_id uuid not null references revenue_intelligence.channel(id),
  external_id varchar(160) not null,
  name varchar(240) not null,
  starts_on date,
  ends_on date,
  created_at timestamptz not null default now(),
  unique(tenant_id, channel_id, external_id)
);

create table revenue_intelligence.customer(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  source_system varchar(80) not null,
  external_id varchar(160) not null,
  full_name varchar(200) not null default '',
  email_hash char(64),
  email_masked varchar(254),
  phone_hash char(64),
  phone_masked varchar(40),
  history_complete boolean not null default false,
  lifecycle varchar(20) not null default 'UNKNOWN' check (lifecycle in ('NEW','RETURNING','UNKNOWN')),
  first_order_at timestamptz,
  last_order_at timestamptz,
  valid_order_count int not null default 0,
  merged_into uuid references revenue_intelligence.customer(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(tenant_id, source_system, external_id)
);

create table revenue_intelligence.customer_identity(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  customer_id uuid not null references revenue_intelligence.customer(id),
  identity_type varchar(16) not null check (identity_type in ('EMAIL','PHONE')),
  identity_hash char(64) not null,
  source_system varchar(80) not null,
  verified boolean not null default false,
  created_at timestamptz not null default now(),
  unique(tenant_id, identity_type, identity_hash)
);

create table revenue_intelligence.customer_source_link(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  customer_id uuid not null references revenue_intelligence.customer(id),
  source_system varchar(80) not null,
  external_id varchar(160) not null,
  created_at timestamptz not null default now(),
  unique(tenant_id, source_system, external_id)
);

create table revenue_intelligence.sales_order(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  source_system varchar(80) not null,
  external_id varchar(160) not null,
  customer_id uuid references revenue_intelligence.customer(id),
  customer_source varchar(80),
  customer_external_id varchar(160),
  ordered_at timestamptz not null,
  gross_amount numeric(19,2) not null check (gross_amount >= 0),
  discount_amount numeric(19,2) not null default 0 check (discount_amount >= 0),
  returned_amount numeric(19,2) not null default 0 check (returned_amount >= 0),
  cancelled_amount numeric(19,2) not null default 0 check (cancelled_amount >= 0),
  shipping_amount numeric(19,2) not null default 0 check (shipping_amount >= 0),
  tax_amount numeric(19,2) not null default 0 check (tax_amount >= 0),
  net_revenue numeric(19,2) generated always as (gross_amount-discount_amount-returned_amount-cancelled_amount) stored,
  source_channel varchar(80),
  business_model varchar(16) not null check (business_model in ('WHOLESALE','RETAIL','UNKNOWN')),
  customer_lifecycle varchar(20) not null check (customer_lifecycle in ('NEW','RETURNING','UNKNOWN')),
  status varchar(24) not null check (status in ('COMPLETED','RETURNED','PARTIALLY_RETURNED','CANCELLED')),
  import_batch_id uuid not null references revenue_intelligence.import_batch(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique(tenant_id, source_system, external_id)
);

create table revenue_intelligence.ad_spend(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  source_system varchar(80) not null,
  external_id varchar(160) not null,
  spend_date date not null,
  channel_id uuid not null references revenue_intelligence.channel(id),
  campaign_id uuid references revenue_intelligence.campaign(id),
  amount numeric(19,2) not null check (amount >= 0),
  currency char(3) not null,
  import_batch_id uuid not null references revenue_intelligence.import_batch(id),
  created_at timestamptz not null default now(),
  unique(tenant_id, source_system, external_id)
);

create table revenue_intelligence.touchpoint(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  source_system varchar(80) not null,
  external_id varchar(160) not null,
  customer_id uuid references revenue_intelligence.customer(id),
  occurred_at timestamptz not null,
  channel_id uuid not null references revenue_intelligence.channel(id),
  campaign_id uuid references revenue_intelligence.campaign(id),
  source_medium varchar(160),
  event_type varchar(80) not null,
  import_batch_id uuid not null references revenue_intelligence.import_batch(id),
  created_at timestamptz not null default now(),
  unique(tenant_id, source_system, external_id)
);

create table revenue_intelligence.attribution_result(
  id uuid primary key default gen_random_uuid(),
  tenant_id uuid not null references platform.tenant(id),
  order_id uuid not null references revenue_intelligence.sales_order(id) on delete cascade,
  model varchar(32) not null check (model in ('FIRST_TOUCH','LAST_NON_DIRECT')),
  channel_id uuid not null references revenue_intelligence.channel(id),
  touchpoint_id uuid references revenue_intelligence.touchpoint(id),
  attributed_revenue numeric(19,2) not null,
  computed_at timestamptz not null default now(),
  unique(tenant_id, order_id, model)
);

create table revenue_intelligence.order_revision(
  id bigserial primary key,
  tenant_id uuid not null references platform.tenant(id),
  order_id uuid not null references revenue_intelligence.sales_order(id) on delete cascade,
  revision int not null,
  action varchar(32) not null,
  actor varchar(254) not null,
  snapshot jsonb not null,
  occurred_at timestamptz not null default now(),
  unique(tenant_id, order_id, revision)
);

create index ri_order_period_idx on revenue_intelligence.sales_order(tenant_id, ordered_at, status);
create index ri_touchpoint_customer_time_idx on revenue_intelligence.touchpoint(tenant_id, customer_id, occurred_at desc);
create index ri_spend_period_idx on revenue_intelligence.ad_spend(tenant_id, spend_date, channel_id);
create index ri_attribution_period_idx on revenue_intelligence.attribution_result(tenant_id, model, channel_id);
create index ri_import_error_batch_idx on revenue_intelligence.import_error(tenant_id, batch_id, row_number);
create index ri_customer_source_idx on revenue_intelligence.customer_source_link(tenant_id, source_system, external_id);
create index ri_order_revision_idx on revenue_intelligence.order_revision(tenant_id, order_id, revision desc);

DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['import_batch','import_error','channel','campaign','customer','customer_identity','customer_source_link','sales_order','ad_spend','touchpoint','attribution_result','order_revision']
  LOOP
    EXECUTE format('alter table revenue_intelligence.%I enable row level security', table_name);
    EXECUTE format('alter table revenue_intelligence.%I force row level security', table_name);
    EXECUTE format('create policy tenant_isolation on revenue_intelligence.%I using (tenant_id = platform.current_tenant_id()) with check (tenant_id = platform.current_tenant_id())', table_name);
  END LOOP;
END $$;

insert into platform.resource_descriptor(name,storage_mode,owner_module,record_count,schema_version,resource_type,supported_actions,audit_policy,data_classification)
values
  ('Revenue Customer','DOMAIN','revenue-intelligence',0,'v1','revenue-customer','READ,CREATE,UPDATE','ALWAYS','CONFIDENTIAL'),
  ('Revenue Order','DOMAIN','revenue-intelligence',0,'v1','revenue-order','READ,CREATE','ALWAYS','CONFIDENTIAL')
on conflict (resource_type) do update set schema_version=excluded.schema_version, updated_at=now();

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT USAGE ON SCHEMA revenue_intelligence TO core_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA revenue_intelligence TO core_app;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA revenue_intelligence TO core_app;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_admin') THEN
    ALTER DEFAULT PRIVILEGES FOR ROLE core_admin IN SCHEMA revenue_intelligence GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE core_admin IN SCHEMA revenue_intelligence GRANT USAGE, SELECT ON SEQUENCES TO core_app;
  END IF;
END $$;
