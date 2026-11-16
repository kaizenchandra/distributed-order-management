# Reproducible measurements

Requires k6, Python 3, the running authenticated OMS, and Docker CLI access for snapshots. These workloads create real
demo orders and financial simulator operations. Use disposable OMS data; never point at a real processor. Keep
build/test/fault jobs separate from timed load runs.

```sh
# Choose the currently running topology, not a fresh transport cluster.
export OMS_COMPOSE_FILE=compose.tuned.yml
python3 benchmarks/run.py --rate 10 --duration 20s --label checkout10
python3 benchmarks/run-query.py --rate 100 --duration 10s --label queries100
python3 benchmarks/run-rebuild.py --label rebuild
python3 benchmarks/summarize.py
```

The lifecycle workload calls the authenticated Order API directly on 8091, bypassing the gateway's per-customer quota
for controlled component measurement. It submits at a constant arrival rate, then polls the protected Saga API until
COMPLETED, failure/manual review, or the 110-second deadline. Acceptance is not completion. The report includes
offered/accepted/completed/failed/timed-out orders, dropped iterations, polling errors, command and Saga percentiles,
and terminal throughput divided by the entire run including drain. Timeouts remain timeouts even if workflows finish
later. Inspect pending workflows before another run; do not reset financial tables to improve results.

Each result directory retains k6 summary/log, deployment image identities, hardware, before/after Prometheus snapshots,
VM swap/memory and container samples, payment queue samples, and measured SQL stage/outbox/provider dwell. Local host
runs also save launch JAR hashes/profile and process RSS. Tokens are passed only through the child environment. Results
and secrets are ignored by source control; selected safe reports are copied to docs/evidence.

Default lifecycle preallocation is 300 VUs. Set VUS and MAX_VUS explicitly for smaller machines; retain any dropped
iterations as a failed offered-load target. Required concurrency is approximately rate × observed lifecycle duration,
not rate × HTTP acknowledgment duration. Query workloads use 50 preallocated VUs after an initial 10-VU run dropped
three iterations. Working set is 50 owned orders, without a client cache; it does not model 100M customers or a large
random working set.

`run-rebuild.py` times a real admin request and checks contiguous projection versions and timeline counts against a
single PostgreSQL snapshot. Rebuild uses buffered integration history in the Query database; it is not a cross-store
event-store recovery exercise.

`build-baseline.py` constructs an isolated comparison variant with the current domain/security code and the earlier
sequential outbox publisher. It is not a saved historical binary. It writes JARs and a source hash under ignored
`.local/baseline-jars`. For a controlled comparison run both variants with identical deployment resources, workload and
warmed infrastructure; changing host/container placement or memory budgets introduces confounding factors. The host
launcher accepts OMS_HOST_PROFILE=baseline|tuned, OMS_JAR_DIR, and OMS_HOST_KAFKA_BOOTSTRAP. It snapshots JARs so
recompilation never changes a running JVM's archive. Stop native OMS processes before starting application containers.

Higher rates are design targets: run 100, 1000, 5000 and 10000 only on provisioned infrastructure after establishing
headroom. The recorded local tests do not establish those capacities. See docs/performance-results.md for actual
successes, failures, bottleneck fixes and environmental limits.

For verification on a saturated Docker control plane, `JAVA_HOME=<Java21> python3 scripts/verify-local-postgres.py` runs
the same tests against newly generated `oms_test_<uuid>` databases on the existing loopback PostgreSQL listener (15433).
Each test class creates and drops only its own database. It never points Flyway clean at an OMS service database.
Explicit local admin-URL validation and opt-in are required. Without these environment settings, normal `mvn verify`
continues to use disposable PostgreSQL Testcontainers. The test-support module is test-scoped and is not packaged into
application images. A tmpfs test container does not establish storage-device crash durability.

After the cohort has drained, `JAVA_HOME=<Java21> python3 benchmarks/run-audit.py --label checkout10` independently
reads the owned databases through the optional loopback PostgreSQL listener. It verifies every labelled order's terminal
Saga/order state, local/provider balance equality, captured/refunded amounts and confirmed/released inventory. This is a
read-only diagnostic audit, not a distributed mutation or atomic cross-service snapshot. The packaged JDBC driver is
extracted to ignored local storage. A timeout in the original latency report remains a timeout after a successful audit;
record terminal compensation separately from completion.

`python3 benchmarks/campaign.py --label final --rate 10 --duration 20s` acquires customer/admin tokens once with bounded
cold-start waits and passes them only in process environment to sequential query and lifecycle runs. Reports contain no
tokens. Authentication preflight failures are recorded separately and are never treated as a load measurement. The
tokens must remain valid for the campaign; an expired token produces ordinary request failures rather than an invented
success.
