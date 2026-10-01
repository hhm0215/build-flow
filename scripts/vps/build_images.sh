#!/usr/bin/env bash
# Build one application image at a time on the 8 GiB private-pilot VPS.
set -euo pipefail

cd "$(dirname "$0")/../.."
test -f .env || { echo 'Missing server .env' >&2; exit 2; }

compose=(docker compose -f docker-compose.yml -f docker-compose.app.yml -f docker-compose.vps.yml)
"${compose[@]}" config --quiet

for service in \
  eureka-server config-server gateway-server auth-service \
  estimate-service site-service purchase-service tax-service \
  notification-service chat-service frontend; do
  echo "BUILD_START:${service}"
  "${compose[@]}" build "$service"
  echo "BUILD_OK:${service}"
done
