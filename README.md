# Distributed Order Management

Java 21 / Spring Boot 3.5.16 / PostgreSQL 17 / Kafka 3.9.1. Nine OMS services plus a separately deployed
payment-provider simulator. This is a runnable engineering baseline, **not production certified**.
See [verification](docs/verification.md) and [limitations](docs/known-limitations.md).

## Run and verify

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21) # macOS; set Java 21 path elsewhere
mvn -B verify
sh scripts/start-docker.sh
python3 scripts/smoke.py
python3 scripts/payment-contract-test.py
python3 scripts/kafka-security-test.py
python3 scripts/recovery.py
python3 scripts/failure-tests.py
```

`start-docker.sh` builds all images from source in Docker, generates ignored development secrets/TLS material,
provisions the provider database on existing volumes, creates Kafka topics/ACLs, and starts JVM containers sequentially
with readiness checks. Requires Docker Compose, Python 3, OpenSSL and keytool. Ports 8091–8097, 8099, 8100 and 8180 must
be free. Only loopback ports are exposed. The failure tests intentionally stop/kill/recreate only this project's
containers; run against disposable OMS data.

After host `mvn verify`, `OMS_LOCAL_JARS=1 sh scripts/start-docker.sh` uses prebuilt JARs. Default startup is a
multi-stage container source build. Kafka uses TLS/SASL and deny-by-default ACLs in both modes. Do not delete
`docker/kafka.local/` while retaining configured containers: it holds the development CA/trust material and persistent
identities.

Stop with `docker compose down`; `down -v` intentionally erases demo data. Logs and JAR snapshots from the optional host
mode live under ignored `.local/`. To use host mode, stop all OMS application containers including payment-provider, run
infrastructure with `compose.host.yml` (and Kafka bootstrap), then `python3 scripts/host-services.py`. Each host JVM
uses its own generated TLS/SASL properties. Default Docker-only mode is the verified deployment path.

## Customer frontend

The responsive Orbit workspace lives in [`frontend`](frontend/README.md). With the backend running:

```sh
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and sign in with a local customer account. The frontend connects through the existing gateway
and Keycloak using development proxies. It supports catalog browsing, quoted checkout, order history, timelines and
cancellation requests. No backend changes are required.

## Checkout

Gateway is localhost:8099; OIDC is localhost:8180. Local test users `customer` / `other` have password
`local-customer-only`. These are disposable demonstration identities. Internal client credentials are generated into
ignored `.env` and `docker/realm.local.json` by configuration. Do not deploy this development realm publicly.

```sh
TOKEN=$(curl -fsS http://localhost:8180/realms/oms/protocol/openid-connect/token \
 -d grant_type=password -d client_id=oms-demo -d username=customer -d password=local-customer-only | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
curl -i http://localhost:8099/api/v1/orders -H "Authorization: Bearer $TOKEN" \
 -H 'Content-Type: application/json' -H 'Idempotency-Key: example-1' \
 -d '{"items":[{"sku":"SKU-1","quantity":2},{"sku":"SKU-2","quantity":1}]}'
```

Returns 202 with `orderId`, command-side `PENDING` state and version 1. Query asynchronously at
`/api/v1/query/orders/{id}?minVersion=2`; a lagging projection responds 202, absent projection 404. Query lists use
offset bounded to 50 records. Cancellation is a request, not an immediate reversal. Saga inspection runs on localhost:
8095 and requires `oms.admin` scope.

## Ownership

| Service              |     Port | Owned behavior                                                                            |
|----------------------|---------:|-------------------------------------------------------------------------------------------|
| order-service        |     8091 | Pure aggregate replay, immutable events, expected-version appends, customer checkout keys |
| inventory-service    |     8092 | TCC, tombstones, reservations, stock constraints and expiry                               |
| payment-service      |     8093 | Durable provider operations, timeout reconciliation, local financial ledger               |
| payment-provider     |     8100 | Independent deterministic HTTP simulator and durable provider ledger                      |
| fulfillment-service  |     8094 | Preparation, cancellation, irreversible handoff boundary                                  |
| saga-orchestrator    |     8095 | Durable checkout cursor, causal replies, compensation, bounded recovery                   |
| order-query-service  |     8096 | CQRS, version gap buffering, timeline, local history rebuild                              |
| catalog-service      |     8097 | Authoritative product pricing, bounded quote calculation                                  |
| notification-service | internal | Deduplicated notification audit sink                                                      |
| api-gateway          |     8099 | JWT, bounded proxy concurrency, local rate limit, size limit                              |

Each has its own PostgreSQL database and role. PostgreSQL is shared only as local infrastructure.
`libraries/event-contracts` holds transport/security plumbing, no service domain entities. Infrastructure outbox/inbox
migrations are copied per service; shared library does not own their data.

Primary Saga: OrderCreated → Inventory.Try → Payment.Authorize → Inventory.Confirm → Payment.Capture → Order.Confirm →
Fulfillment.Start. Reverse workflow: Fulfillment.Cancel → Payment.Reverse → Inventory.Release → Order.Cancel. A shipped
order stops compensation for manual return handling.
See [architecture](docs/architecture.md), [protocols](docs/saga-orchestration.md), [failure matrix](docs/failure-recovery.md), [architecture review](docs/architecture-review.md), [capacity](docs/capacity-planning.md).

Payment writes return 202 with an operation ID. See [payment reconciliation](docs/payment-reconciliation.md) for lookup,
fencing, manual recovery and simulator fault scenarios. Kafka uses per-service identities over
TLS; [security verification](scripts/kafka-security-test.py) proves denied credentials, topic publishing and
consumer-group access.

## Continuation verification

Audited migration of the initial local payment ledger, a three-broker Kafka HA profile, resumable dual-trust CA
rotation, real failure scripts and reproducible k6 lifecycle/read/rebuild workloads are implemented.
See [legacy migration](docs/legacy-payment-migration.md), [Kafka HA and rotation](docs/kafka-ha-and-rotation.md)
and [measured performance](docs/performance-results.md).

```sh
OMS_COMPOSE_FILE=compose.ha.yml sh scripts/start-ha.sh
OMS_COMPOSE_FILE=compose.ha.yml python3 scripts/ha-test.py
OMS_COMPOSE_FILE=compose.ha.yml python3 scripts/rotate-kafka-certificates.py
# Use the active topology consistently; tuned is an experimental local resource profile.
OMS_COMPOSE_FILE=compose.ha.yml python3 scripts/legacy-migrate.py
OMS_COMPOSE_FILE=compose.ha.yml python3 scripts/legacy-verify.py
OMS_COMPOSE_FILE=compose.ha.yml python3 benchmarks/run.py --rate 10 --duration 20s --label checkout10
```

HA transport has its own persisted cluster, not a transparent migration of existing single-broker logs. Drain existing
workflows before switching profiles. Startup refuses to collide with this project's native JVMs.
`docker compose -f compose.ha.yml down` stops HA containers while retaining their data. The optional CI HA/rotation job
is workflow-dispatch only; local execution evidence does not imply that remote CI or Kubernetes deployment ran.

For the resource-isolated benchmark layout, OMS application JVMs run natively against the retained Docker PostgreSQL,
Keycloak, three Kafka nodes and payment provider. `compose.host-ha.yml` adds only loopback database/Kafka listeners.
Stop native processes before returning to Docker-only application deployment:

```sh
python3 scripts/host-services.py stop
OMS_COMPOSE_FILE=compose.tuned.yml OMS_LOCAL_JARS=1 sh scripts/start-ha.sh
```

Run `mvn -B package -DskipTests` first when selecting local JAR builds. The default launcher still builds from source
inside Docker. Local startup wait is bounded by OMS_STARTUP_TIMEOUT (default 300 seconds); actual steady-state
request/provider timeouts are separate.
