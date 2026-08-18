-- E6-S02: outbox đủ metadata cho relay (tenant, aggregate, lease, retry, lỗi cuối)
alter table async.outbox_event add column if not exists tenant_key varchar(80);
alter table async.outbox_event add column if not exists aggregate_type varchar(120);
alter table async.outbox_event add column if not exists aggregate_id varchar(160);
alter table async.outbox_event add column if not exists occurred_at timestamptz;
alter table async.outbox_event add column if not exists locked_by varchar(120);
alter table async.outbox_event add column if not exists locked_at timestamptz;
alter table async.outbox_event add column if not exists delivered_at timestamptz;
alter table async.outbox_event add column if not exists last_error varchar(400);
alter table async.outbox_event add column if not exists schema_version varchar(20) not null default 'v1';
alter table async.outbox_event add column if not exists event_id uuid;
update async.outbox_event set tenant_key='default', occurred_at=created_at, event_id=gen_random_uuid() where tenant_key is null or occurred_at is null or event_id is null;
create index if not exists outbox_dispatch_idx on async.outbox_event(status, available_at, created_at);
create index if not exists outbox_event_id_idx on async.outbox_event(event_id);

-- E6-S04: inbox idempotent per (consumer, event)
create table if not exists async.inbox_event(
  consumer_id varchar(160) not null,
  event_id uuid not null references async.outbox_event(id),
  consumed_at timestamptz not null default now(),
  status varchar(20) not null default 'CONSUMED',
  primary key(consumer_id, event_id)
);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE ON async.outbox_event TO core_app;
    GRANT SELECT, INSERT ON async.inbox_event TO core_app;
    RAISE NOTICE 'granted E6 tables to core_app';
  ELSE
    RAISE NOTICE 'role core_app not present; skipping E6 grants';
  END IF;
END $$;
