# Executed verification — 2026-11-15

This records actual execution, including failures and corrective reruns. Local success does not establish production
readiness, multi-AZ availability or target-scale performance.

## Current Java verification

All ten Spring Boot application artifacts (nine OMS services plus the independent provider) and both infrastructure/test
libraries package successfully under Java 21 / Spring Boot 3.5.16. `mvn -B package -DskipTests` completed successfully
after the final fixes.

Current per-suite results total **39 tests, zero remaining failures/errors/skips**, across the real-PostgreSQL run and
targeted correction rerun. The full run passed 37 tests and exposed two timestamp compatibility errors in the new
set-based projection rebuild. The corrected two projection tests then passed with both historical numeric epoch
timestamps and current ISO timestamps, version gaps, duplicates and no publication during replay.
See [exact suite counts](evidence/current-test-summary.txt).

The new tests cover audited legacy opening balances, incomplete audit rejection/quarantine, classifier migration,
old-key replay, stale importer fences, native-provider conflict, cross-account import/execution identity races, batched
outbox enqueue/partial acknowledgments and inbox effect deduplication. Existing tests cover PostgreSQL stock
contention/TCC races/expiry, event append conflicts, Saga recovery/stale replies, refund limits, real HTTP lost
responses, provider commit followed by process failure, reconciliation/fencing/claims, absence versus uncertainty,
blocked reversal, strict minor units and HTTP error contracts.

Two attempts to start fresh Testcontainers PostgreSQL databases on the overloaded shared VM failed/stalled during
initialization. The earlier 37-test campaign used Testcontainers successfully. The final run uses
`scripts/verify-local-postgres.py`: the same tests create/drop uniquely named disposable databases on the existing
loopback PostgreSQL instance. No service schema is cleaned. This requires explicit local-admin opt-in and does not
bypass transaction/concurrency assertions. Default `mvn verify` continues to use Testcontainers; test-support
dependencies are test-scoped. Temporary in-memory test storage does not prove device-level crash durability.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -B verify
# Alternative when local OMS PostgreSQL is already exposed at loopback:15433:
python3 scripts/verify-local-postgres.py
# Targeted rerun actually used after the projection correction:
python3 scripts/verify-local-postgres.py -pl services/order-query-service -am \
  -Dtest=ProjectionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Legacy balances, Kafka HA and rotation

Five pre-provider local-simulator payment balances were frozen, audited and imported as opening balances with explicit
provenance. A repeat migration found zero candidates. Local/provider balances and historical capture replay matched;
each imported provider account had zero newly executed financial operations. Invalid/incomplete evidence remains
quarantined. V5 also fails closed for unaudited rows; V6 quarantines mixed local/provider histories and dispatch
excludes blocked origins. All 13 Payment tests passed again after that final guard.

Three independently persisted broker/controller processes use TLS/SASL ACLs, RF3/minISR2 and disabled unclean election.
The executed HA campaign passed all four checks: actual partition-leader kill with five checkouts; actual
active-controller kill with five checkouts; two-node quorum/ISR outage with locally committed PENDING order/outbox;
restored quorum/ISR draining that order. This was the Docker HA deployment before the subsequent relay optimization.
See [HA evidence](evidence/kafka-ha-tests.txt).

Resumable CA rotation passed dual trust, all three rolling leaf replacements with checkout canaries, new-only trust and
old-CA retirement. Fresh hostname-verified sockets checked the live leaf certificates. The public output contains
fingerprints, not private keys. Full rollback code exists but has not been exercised.
See [rotation evidence](evidence/kafka-certificate-rotation.txt).

## Previously executed Docker boundary/failure campaign

The Docker-only stack built source images and passed authenticated checkout, authoritative price, checkout
idempotency/payload conflicts, ownership, capture/projection, cancellation/refund/release, decline compensation and
projection rebuild. Payment/Saga stop/restart recovered an in-flight checkout with a single capture.

Six security checks passed: valid TLS admin access; wrong password, untrusted CA, unauthorized command publish, stolen
consumer group and anonymous access rejected. Payment REST tests covered async operation acknowledgment/polling, scopes,
money validation, replay/conflicts and capture/refund timeout recovery.

Twelve failure scenarios passed: missing provider credentials, definitive lookup absence, provider outage, lost
authorization/capture/refund responses, Payment killed after provider commit, ambiguous capture/manual
recovery/cancellation, Kafka outage/outbox recovery, duplicate commands, broker recreation with retained offsets/ACLs
and poison event DLT recovery. Original evidence remains under evidence/. These prior results are not represented as a
fresh full campaign of every later source change.

## Measurements and infrastructure

The deployed set-based projection rebuild took 1.404 seconds for 2,014 events, versus the earlier 28.680 seconds for
1,953 events; versions and timeline counts passed. Actual k6 lifecycle, CQRS read and timed projection-rebuild results,
including deadline failures and dropped load, are in [performance results](performance-results.md). The initial
aggressive polling profile regressed. The final 100-RPS read workload passed 1,001 responses with no errors/drops and
p95 34.65 ms. The final 10-order/sec workload missed every accepted order’s completion deadline; after explicit broker
recovery all 200 accepted orders completed and passed the cohort financial/inventory audit, and order-event partitions
recovered ISR=3. The subsequent 2-order/sec run completed all 21 orders with no errors/drops/timeouts and passed the
financial/inventory audit. No 100/1000/5000/10000 order/sec or 100,000-query-RPS target was established.

The updated provider image built and its preserved ledger restarted successfully, although a 191-second cold start
exceeded the launcher wait. Lightweight Docker readiness probes passed positive and non-200 tests; the earlier
Java-per-probe timeout issue was corrected. A campaign aborted before load during startup is retained as a preflight
failure. Helm lint/template rendering succeeded with current manifests. Python compilation and Compose/workflow YAML
parsing succeeded. CI has an explicit workflow-dispatch HA/rotation campaign; remote CI execution and Kubernetes rollout
have not been performed here. Real processor settlement/webhooks, managed PKI, multi-host/multi-AZ/regional failures and
99.99% availability remain unverified.

## Final follow-up campaign

The corrected broker settings and final native OMS artifacts passed partition-leader loss and active-controller loss
(five checkouts each) and failed closed during two-node quorum loss. The long full attempt then exhausted its initial
300-second admin token while inspecting an order that had already completed. Its error log is preserved. The harness now
refreshes credentials between phases and before completion checks, and journals order IDs. A targeted quorum-only rerun
passed outage and restoration, with ISR3 and a single recovered order whose local/provider capture was 7,500 and
provider operation count exactly two. These four scenarios are verified across the recorded attempt and correction
rerun, not represented as one uninterrupted clean run. See [current HA summary](evidence/final-ha-summary.txt).

Final durable state: 1,176 COMPLETED and 59 COMPENSATED historical/demo Sagas; no RUNNING or MANUAL_REVIEW Sagas.
Provider operations: 2,477 SUCCEEDED and seven intentional scenario rejections; no PENDING, SUBMITTING, UNKNOWN or
MANUAL_REVIEW operations. All five migrated balances/old-key replay verified again under V6.

The live resource-isolated layout remains nine native OMS JVMs against Docker PostgreSQL/OIDC/provider and three Kafka
nodes. This differs from the earlier fully Docker-only campaign. README contains explicit stop/transition commands, and
container-disruption scripts refuse to collide with native JVMs. No Kubernetes rollout or remote CI execution was
performed.
