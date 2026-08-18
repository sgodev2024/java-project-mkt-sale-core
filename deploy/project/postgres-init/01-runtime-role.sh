#!/bin/sh
set -eu

: "${CORE_APP_DB_PASSWORD:?CORE_APP_DB_PASSWORD is required}"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=runtime_password="$CORE_APP_DB_PASSWORD" \
  --set=database_name="$POSTGRES_DB" <<'SQL'
SELECT format(
  'CREATE ROLE core_app LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
  :'runtime_password'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'core_app')
\gexec

ALTER ROLE core_app PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE :"database_name" TO core_app;
SQL

