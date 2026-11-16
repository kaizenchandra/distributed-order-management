#!/bin/sh
# Start sequentially so JVM boot peaks do not overwhelm a small Docker VM.
set -eu
cd "$(dirname "$0")/.."
python3 scripts/check-no-host.py
sh scripts/configure-local.sh
compose() {
  if [ "${OMS_LOCAL_JARS:-0}" = 1 ]; then
    docker compose -f compose.yml -f compose.local.yml "$@"
  else
    docker compose -f compose.yml "$@"
  fi
}
if [ "${OMS_SKIP_BUILD:-0}" != 1 ]; then COMPOSE_PARALLEL_LIMIT=2 compose build; fi
compose up -d --wait postgres
compose exec -T postgres sh < docker/ensure-provider-db.sh
compose up -d kafka keycloak kafka-init
compose wait kafka-init
for service in payment-provider catalog-service inventory-service order-service payment-service fulfillment-service saga-orchestrator order-query-service notification-service api-gateway; do
  compose up -d --no-deps --wait --wait-timeout "${OMS_STARTUP_TIMEOUT:-300}" "$service"
  echo "Healthy Docker service: $service"
done
