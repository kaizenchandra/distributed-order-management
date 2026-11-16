#!/bin/bash
set -euo pipefail
bin=/opt/kafka/bin
admin=(--bootstrap-server "${KAFKA_BOOTSTRAP:-kafka:9092}" --command-config /security/cli/admin.properties)
for n in $(seq 1 60); do
  if "$bin/kafka-topics.sh" "${admin[@]}" --list >/dev/null 2>&1; then break; fi
  if [ "$n" = 60 ]; then echo 'Kafka TLS/admin readiness failed' >&2; exit 1; fi
  sleep 2
done
for name in order-service inventory-service payment-service fulfillment-service saga-orchestrator order-events; do
 for suffix in '' '.DLT'; do
  "$bin/kafka-topics.sh" "${admin[@]}" --create --if-not-exists --topic "oms.$name$suffix" --partitions 3 --replication-factor "${KAFKA_TOPIC_REPLICATION:-1}" --config "min.insync.replicas=${KAFKA_TOPIC_MIN_ISR:-1}" >/dev/null
 done
done
acl(){ "$bin/kafka-acls.sh" "${admin[@]}" --add --allow-principal "User:$1" "${@:2}" >/dev/null; }
for service in order-service inventory-service payment-service fulfillment-service saga-orchestrator order-query-service notification-service; do
 identity=${service//-/_}
 acl "$identity" --operation Read --group "$service"
 case "$service" in
   order-query-service|notification-service) topic=order-events ;;
   *) topic=$service ;;
 esac
 acl "$identity" --operation Read --operation Describe --topic "oms.$topic"
 acl "$identity" --operation Write --operation Describe --topic "oms.$topic.DLT"
 acl "$identity" --operation IdempotentWrite --cluster
 case "$service" in
  order-service) targets='order-events saga-orchestrator' ;;
  saga-orchestrator) targets='order-service inventory-service payment-service fulfillment-service' ;;
  inventory-service|payment-service|fulfillment-service) targets='saga-orchestrator' ;;
  *) targets='' ;;
 esac
 for target in $targets; do acl "$identity" --operation Write --operation Describe --topic "oms.$target"; done
 echo "Provisioned restricted Kafka identity: $identity"
done
echo 'Kafka TLS topics and ACLs ready'
