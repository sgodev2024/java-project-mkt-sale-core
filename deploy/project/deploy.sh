#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=${APP_ROOT:-/home/ubuntu/crm-mkt-sale-java-core}
DEPLOY_BRANCH=${DEPLOY_BRANCH:-main}
COMPOSE_FILE="$APP_ROOT/deploy/project/docker-compose.yml"
ENV_FILE="$APP_ROOT/.env"

cd "$APP_ROOT"
test -f "$ENV_FILE"
test -z "$(git status --porcelain)"

git fetch origin "$DEPLOY_BRANCH" --tags --prune
git checkout "$DEPLOY_BRANCH"
git merge --ff-only "origin/$DEPLOY_BRANCH"

export APP_VERSION
APP_VERSION=$(git rev-parse --short=12 HEAD)

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans

for attempt in $(seq 1 45); do
  if curl --fail --silent http://127.0.0.1:18180/actuator/health/readiness >/dev/null \
      && curl --fail --silent http://127.0.0.1:18181/ >/dev/null; then
    printf '%s\n' "$APP_VERSION" > "$APP_ROOT/.deployed-version"
    echo "Deployment $APP_VERSION succeeded"
    exit 0
  fi
  sleep 2
done

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
echo "Deployment failed health gate" >&2
exit 1

