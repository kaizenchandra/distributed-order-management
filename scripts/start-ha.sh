#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/check-no-host.py
sh scripts/configure-local.sh
python3 scripts/configure-kafka-ha.py
export OMS_KAFKA_HA_CLUSTER_ID=$(cat docker/kafka.local/ha/cluster-id)
export OMS_COMPOSE_FILE=${OMS_COMPOSE_FILE:-compose.ha.yml}
compose(){
 if [ "${OMS_LOCAL_JARS:-0}" = 1 ]; then
  docker compose -f "$OMS_COMPOSE_FILE" -f compose.local.yml "$@"
 else
  docker compose -f "$OMS_COMPOSE_FILE" "$@"
 fi
}
if [ "${OMS_SKIP_BUILD:-0}" != 1 ]; then COMPOSE_PARALLEL_LIMIT=2 compose build; fi
compose up -d --wait postgres
compose exec -T postgres sh < docker/ensure-provider-db.sh
compose up -d kafka-a kafka-b kafka-c keycloak kafka-init
compose wait kafka-init
for name in payment-provider catalog-service inventory-service order-service payment-service fulfillment-service saga-orchestrator order-query-service notification-service api-gateway; do
 compose up -d --no-deps --wait --wait-timeout "${OMS_STARTUP_TIMEOUT:-300}" "$name"
done
# The old single broker remains available for rollback, but is stopped to release local memory.
docker compose -f compose.yml stop kafka
