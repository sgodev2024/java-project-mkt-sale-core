-- E7: job queue có lease/heartbeat/backoff + scheduler leader election
alter table async.job add column if not exists tenant_key varchar(80);
alter table async.job add column if not exists leased_by varchar(120);
alter table async.job add column if not exists leased_at timestamptz;
alter table async.job add column if not exists lease_until timestamptz;
alter table async.job add column if not exists heartbeat_at timestamptz;
alter table async.job add column if not exists available_at timestamptz not null default now();
alter table async.job add column if not exists last_error varchar(400);
alter table async.job add column if not exists idempotency_key varchar(160);
alter table async.job add column if not exists cancelled_at timestamptz;
-- E7-S01: job bắt buộc thuộc tenant — không có job "mồ côi" tenant
update async.job set tenant_key='default' where tenant_key is null;
alter table async.job alter column tenant_key set not null;
-- bỏ các dòng demo placeholder
delete from async.job where job_type like 'demo.%';
create index if not exists job_claim_idx on async.job(status, available_at);
create unique index if not exists job_idempotency_uidx on async.job(idempotency_key) where idempotency_key is not null;

-- E7-S04: leader election cho scheduler — đúng một instance tạo job theo lịch
create table if not exists async.scheduler_lock(
  id int primary key default 1 check (id = 1),
  leader_id varchar(120),
  lease_until timestamptz
);
insert into async.scheduler_lock(id) values (1) on conflict do nothing;

create table if not exists async.job_schedule(
  id uuid primary key default gen_random_uuid(),
  tenant_key varchar(80) not null,
  job_type varchar(120) not null,
  payload jsonb not null default '{}',
  interval_seconds int not null check (interval_seconds >= 1),
  misfire_grace_seconds int not null default 60,
  enabled boolean not null default true,
  last_fired_at timestamptz,
  created_at timestamptz not null default now()
);

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE ON async.job TO core_app;
    GRANT SELECT, INSERT, UPDATE ON async.job_schedule TO core_app;
    GRANT SELECT, UPDATE ON async.scheduler_lock TO core_app;
    RAISE NOTICE 'granted E7 tables to core_app';
  ELSE
    RAISE NOTICE 'role core_app not present; skipping E7 grants';
  END IF;
END $$;
