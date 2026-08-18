-- Workspace-based dynamic navigation: per-account favorites/recent/current workspace.
create table platform.navigation_preference(
  tenant_id uuid not null references platform.tenant(id),
  account_id uuid not null references identity.account(id) on delete cascade,
  favorite_keys jsonb not null default '[]'::jsonb check (jsonb_typeof(favorite_keys) = 'array'),
  recent_keys jsonb not null default '[]'::jsonb check (jsonb_typeof(recent_keys) = 'array'),
  last_workspace_key varchar(80) not null default '',
  updated_at timestamptz not null default now(),
  primary key(tenant_id, account_id)
);
create index navigation_preference_account_idx on platform.navigation_preference(account_id);

alter table platform.navigation_preference enable row level security;
alter table platform.navigation_preference force row level security;
drop policy if exists tenant_isolation on platform.navigation_preference;
create policy tenant_isolation on platform.navigation_preference
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON platform.navigation_preference TO core_app;
    RAISE NOTICE 'Navigation preference grants ok';
  END IF;
END $$;
