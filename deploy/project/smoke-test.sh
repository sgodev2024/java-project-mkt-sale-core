#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=${APP_ROOT:-/home/ubuntu/crm-mkt-sale-java-core}
BASE_URL=${BASE_URL:-https://crm-mkt-sale.sgodata.com}
SEED_DEMO=${SEED_DEMO:-false}
ENV_FILE=${ENV_FILE:-$APP_ROOT/.env}

cd "$APP_ROOT"
test -f "$ENV_FILE"

read_env() {
  local key=$1
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

ADMIN_EMAIL=${CORE_SMOKE_ADMIN_EMAIL:-admin@core.local}
ADMIN_PASSWORD=${CORE_SMOKE_ADMIN_PASSWORD:-$(read_env CORE_BOOTSTRAP_ADMIN_PASSWORD)}
test -n "$ADMIN_PASSWORD"

LOGIN_PAYLOAD=$(CORE_SMOKE_EMAIL="$ADMIN_EMAIL" CORE_SMOKE_PASSWORD="$ADMIN_PASSWORD" python3 -c '
import json, os
print(json.dumps({"email": os.environ["CORE_SMOKE_EMAIL"], "password": os.environ["CORE_SMOKE_PASSWORD"], "remember": False}))
')
LOGIN_RESPONSE=$(curl --fail --silent --show-error \
  -H "Content-Type: application/json" \
  --data "$LOGIN_PAYLOAD" \
  "$BASE_URL/api/v1/auth/login")

ACCESS_TOKEN=$(printf '%s' "$LOGIN_RESPONSE" | python3 -c '
import json, sys
body = json.load(sys.stdin)
assert body.get("mfaRequired") is False, "MFA must be disabled for this test environment"
token = body.get("session", {}).get("accessToken", "")
assert token, "Login response does not contain an access token"
print(token)
')

AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"
ME_RESPONSE=$(curl --fail --silent --show-error -H "$AUTH_HEADER" "$BASE_URL/api/v1/auth/me")
printf '%s' "$ME_RESPONSE" | python3 -c '
import json, sys
body = json.load(sys.stdin)
assert body.get("email") == "admin@core.local"
print("Authentication: OK (admin@core.local, MFA disabled)")
'

curl --fail --silent --show-error -H "$AUTH_HEADER" \
  "$BASE_URL/api/v1/navigation/me" >/dev/null
echo "Navigation registry API: OK"

if [[ "$SEED_DEMO" == "true" ]]; then
  for dataset in customers orders ad-spend touchpoints; do
    response=$(curl --fail --silent --show-error \
      -H "$AUTH_HEADER" \
      -F "file=@$APP_ROOT/samples/input/$dataset.csv;type=text/csv" \
      "$BASE_URL/api/v1/revenue-intelligence/imports/$dataset")
    printf '%s' "$response" | python3 -c '
import json, sys
body = json.load(sys.stdin)
print("Import {}: accepted={}, rejected={}, duplicate={}".format(body.get("dataset"), body.get("acceptedRows"), body.get("rejectedRows"), body.get("duplicate")))
'
  done

  REBUILD_RESPONSE=$(curl --fail --silent --show-error \
    -X POST -H "$AUTH_HEADER" \
    "$BASE_URL/api/v1/revenue-intelligence/attribution/rebuild?from=2026-01-01&to=2026-12-31")
  printf '%s' "$REBUILD_RESPONSE" | python3 -c '
import json, sys
body = json.load(sys.stdin)
print("Attribution rebuild: orders={}, results={}".format(body.get("ordersProcessed"), body.get("resultsWritten")))
'
fi

DASHBOARD_RESPONSE=$(curl --fail --silent --show-error \
  -H "$AUTH_HEADER" \
  "$BASE_URL/api/v1/revenue-intelligence/dashboard?from=2026-01-01&to=2026-12-31")
printf '%s' "$DASHBOARD_RESPONSE" | python3 -c '
import json, sys
body = json.load(sys.stdin)
assert "kpis" in body and "channels" in body
print("Revenue dashboard API: OK")
'

curl --fail --silent --show-error -X POST -H "$AUTH_HEADER" \
  "$BASE_URL/api/v1/auth/logout" >/dev/null
echo "Smoke test completed successfully"
