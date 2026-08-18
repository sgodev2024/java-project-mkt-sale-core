alter table audit.event add column if not exists tenant_key varchar(80);
alter table audit.event add column if not exists resource_type varchar(80);
alter table audit.event add column if not exists resource_id varchar(160);
alter table audit.event add column if not exists details jsonb not null default '{}';

create table if not exists platform.setting(
  setting_key varchar(100) primary key,
  setting_value varchar(500) not null,
  updated_by varchar(254),
  updated_at timestamptz not null default now()
);

insert into platform.setting(setting_key,setting_value) values
  ('environment.name','core-production-vn'),
  ('environment.region','Ho Chi Minh City'),
  ('environment.tier','standard'),
  ('environment.publicUrl','https://corejava.sgodata.com'),
  ('security.requireAdminMfa','true'),
  ('maintenance.enabled','false')
on conflict(setting_key) do nothing;

create index if not exists audit_event_occurred_idx on audit.event(occurred_at desc);
create index if not exists activity_occurred_idx on platform.activity(occurred_at desc);
