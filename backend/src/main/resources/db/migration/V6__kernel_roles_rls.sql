-- E2-S01: runtime role chỉ có DML, không có DDL/owner/BYPASSRLS.
-- Role do init script (deploy/postgres/01-core-roles.sql) tạo trước khi Flyway chạy;
-- nếu chưa tồn tại (Testcontainers, DB cũ) thì bỏ qua grant và chỉ dựng RLS.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app') THEN
    GRANT USAGE ON SCHEMA public, identity, platform, audit, async, files, dynamic_resource TO core_app;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity, platform, audit, async, files, dynamic_resource TO core_app;
    RAISE NOTICE 'granted runtime DML to core_app';
  ELSE
    RAISE NOTICE 'role core_app not present; skipping runtime grants (RLS still installed)';
  END IF;
END $$;

-- Table/sequence mới do migration sau này tạo vẫn phải cấp DML cho runtime role.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_admin') THEN
    ALTER DEFAULT PRIVILEGES FOR ROLE core_admin IN SCHEMA identity, platform, audit, async, files, dynamic_resource
      GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO core_app;
  END IF;
END $$;

-- E2-S02: contract đọc tenant hiện tại từ session GUC; GUC rỗng → NULL → policy không khớp dòng nào (fail-closed).
create or replace function platform.current_tenant_id() returns uuid language sql stable as
$$ select nullif(current_setting('core.tenant_id', true), '')::uuid $$;

-- E2-S03: RLS ENABLE + FORCE trên mọi bảng có tenant_id.
-- FORCE để cả table owner cũng không thoát policy; superuser/BYPASSRLS (migration credential) vẫn vượt qua khi migrate.
alter table dynamic_resource.definition enable row level security;
alter table dynamic_resource.definition force row level security;
drop policy if exists tenant_isolation on dynamic_resource.definition;
create policy tenant_isolation on dynamic_resource.definition
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

alter table dynamic_resource.record enable row level security;
alter table dynamic_resource.record force row level security;
drop policy if exists tenant_isolation on dynamic_resource.record;
create policy tenant_isolation on dynamic_resource.record
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

alter table dynamic_resource.revision enable row level security;
alter table dynamic_resource.revision force row level security;
drop policy if exists tenant_isolation on dynamic_resource.revision;
create policy tenant_isolation on dynamic_resource.revision
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());

alter table files.file_object enable row level security;
alter table files.file_object force row level security;
drop policy if exists tenant_isolation on files.file_object;
create policy tenant_isolation on files.file_object
  using (tenant_id = platform.current_tenant_id())
  with check (tenant_id = platform.current_tenant_id());
