# Operations

Start with the failed order ID and authenticated Saga inspect/steps APIs. Compare current command ID, direction,
attempts and deadline to participant inbox, outbox and domain rows using that service's database credentials. Inspect
secured logs and trace IDs; IDs must not become metric labels.

Kafka outage: preserve DB outbox; restore broker, watch pending age and consumer lag. Relay retries; never delete
unacknowledged records. Poison envelope: inspect corresponding .DLT topic, fix contract or consumer, then replay
deliberately preserving event ID. Gap projection: compare missing aggregate version to Order event history; restore
transport or admin rebuild after checking retained buffer completeness.

MANUAL_REVIEW: inspect participant durable operation and outcome. Retry endpoint repeats the same operation identity.
Never substitute a new capture/refund identity to escape uncertainty. A shipped-order rejection requires a return
workflow and human review; automatic payment reversal is blocked at fulfillment cancellation.

PostgreSQL outage: stop accepting new business commands when persistence is unavailable. Recovery assumes database
durability; local Compose has one node and no failover. Restore backups plus WAL only with coordinated reconciliation of
already published/external effects. Disaster recovery and cross-region fencing are not implemented.

Reservation expiry is automatic only for TRIED. Investigate confirmed allocations without terminal orders.
Outbox/inbox/terminal Saga history currently has no purge job; monitor disk. `docker compose restart saga-orchestrator`
exercises restart; no in-memory cursor is required.

Deployment: build each module image with SERVICE build arg, push tagged images, provision databases, migrations, OIDC,
Kafka ACL/TLS and per-service secrets, then helm upgrade --install oms helm. Review known-limitations before exposing
traffic. K8s manifests are templates, not evidence of a deployed cluster.

## Payment uncertainty and Kafka credentials

Inspect `/api/v1/admin/payments/{paymentId}/operations` when a financial step is pending. UNKNOWN retries status lookup;
MANUAL_REVIEW stops automatic progress for that payment. Never create a replacement operation ID to escape uncertainty.
After resolving provider visibility/availability, use the admin operation retry endpoint to look up the same operation.
A queued refund/reverse cannot bypass unresolved capture. The local simulator's protected reveal endpoint is only a
deterministic fault-test control.

Kafka clients must mount their own generated credential file and truststore; anonymous/plaintext clients are rejected.
Check `kafka-init` logs for the completed bootstrap. Use `scripts/kafka-security-test.py` after credential/ACL changes.
Broker recreation must retain the kafka data volume; do not erase it as an outage-recovery tactic. Production TLS
expiration, credential rotation and controller quorum recovery require deployment-specific procedures.
