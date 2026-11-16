# Known limitations and production gates

This project does not yet meet every enterprise acceptance criterion. Outstanding work includes approved real-processor
adapters and webhook verification, durable notification delivery, carrier/delivery/returns adapters, stock deduction at
shipment, administrative resolution audit, historical upcasters, online cross-store projection rebuild, resilience
circuit breakers, managed PKI and workload identity, distributed gateway quotas and strict read/admin response
contracts.

Metrics expose runtime/JVM/HTTP and sampled outbox backlog/age, manual-review and projection-gap gauges. Basic
dashboard/alert assets are supplied; complete business counters, Kafka lag and an operational OTel collector remain
unwired. Helm lacks network policies, topology spreading, production dependency charts, automated secret management and
a tested upgrade/rollback procedure. REST OpenAPI detail and broad contract tests remain incomplete; the new async
payment and operation inspection/retry routes are specified. Multi-AZ/regional failover and 99.99% availability are
unverified. No full 40-scenario chaos campaign or target-scale benchmark is claimed.

Notification is a deduplicated audit sink, not an email/SMS integration. Pricing is USD-only and frozen at checkout.
Inventory warehouse dimension, partial fulfillment, partial cancellation, shipping charges and tax are omitted. Customer
IDs are OIDC subjects, not a separate account database.

Payment now implements a durable external-operation protocol against a separate deterministic HTTP provider with its own
database: commit intent, call outside transactions, reconcile uncertain status by stable identity, fence local commits
and escalate bounded failures. Timeout and process-crash tests exercise this protocol. This does not certify any real
processor's idempotency window, settlement, webhook or dispute behavior. Legacy initial-baseline rows are quarantined
and require an explicit audited opening-balance import. The existing five local-simulator rows were migrated and
verified, without new provider financial operations. Incomplete legacy audit remains blocked. This protocol is
deliberately limited to the original local simulator; real processor balances require independent financial
reconciliation.

The 40-scenario matrix documents intended controls and residual risks; it must not be mistaken for execution evidence.
See verification.md for actual runs.

Three Kafka broker/controller processes, RF3/minISR2, actual partition/controller leader loss, quorum loss/recovery and
rolling CA replacement/old trust retirement were exercised locally. They share one physical host; no multi-host or
multi-AZ resilience is established. Full CA rollback code is supplied but not yet executed.

Reproducible lifecycle, CQRS read and projection-rebuild workloads now produce actual reports. Local HA lifecycle tests
exposed severe shared-VM memory/swap pressure and missed completion targets; a faster poll profile regressed. Broker
resource/timeout recovery restored all 200 accepted orders to COMPLETED and a passing financial/inventory audit. A
smaller 2-order/sec follow-up completed all 21 orders; this does not establish the failed 10-order/sec capacity.
Preserve these failed runs. See [performance results](performance-results.md); 100/1000/5000/10000 orders per second
remain unverified targets.
