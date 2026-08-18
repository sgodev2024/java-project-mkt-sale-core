#!/usr/bin/env bash
set -euo pipefail

APP_ROOT=/opt/core-platform
REPOSITORY=git@github.com:sgodev2024/java-core.git
BRANCH=${DEPLOY_BRANCH:-main}
RELEASE_ID=$(date -u +%Y%m%d%H%M%S)
RELEASE_DIR="$APP_ROOT/releases/$RELEASE_ID"

mkdir -p "$APP_ROOT/releases"
git clone --depth 1 --branch "$BRANCH" "$REPOSITORY" "$RELEASE_DIR"
cd "$RELEASE_DIR/backend"
mvn -B clean verify

PREVIOUS_TARGET=$(readlink -f "$APP_ROOT/current" || true)
ln -sfn "$RELEASE_DIR" "$APP_ROOT/current"
systemctl restart core-platform

for attempt in $(seq 1 30); do
  if curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness >/dev/null; then
    find "$APP_ROOT/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -nr | tail -n +6 | cut -d' ' -f2- | xargs -r rm -rf
    echo "Deployment $RELEASE_ID succeeded"
    exit 0
  fi
  sleep 2
done

if [[ -n "$PREVIOUS_TARGET" && -d "$PREVIOUS_TARGET" ]]; then
  ln -sfn "$PREVIOUS_TARGET" "$APP_ROOT/current"
  systemctl restart core-platform
fi
echo "Deployment failed; previous release restored" >&2
exit 1
