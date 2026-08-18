-- Chạy một lần bởi postgres image khi khởi tạo volume lần đầu (docker-entrypoint-initdb.d).
-- core_admin (superuser, chủ database) do POSTGRES_USER tạo sẵn; script này chỉ tạo runtime role.
--   core_admin — migration/DDL (Flyway chạy với credential này qua DB_MIGRATION_USER)
--   core_app   — runtime của application (DB_USER): chỉ CONNECT + DML, không DDL, không owner, không BYPASSRLS
CREATE ROLE core_app WITH LOGIN PASSWORD 'core_app_dev' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
GRANT CONNECT ON DATABASE core_platform TO core_app;
