# Local measurements — 2026-11-15

These are actual local results, including failures. They do not validate the design targets. Hardware: macOS/aarch64, 18
GiB physical host RAM; Docker OrbStack reported 11 CPUs and 9,434,791,936 bytes VM memory. Other development workloads
shared the host and VM and changed during the campaign. They were not stopped. Timed results are observations in that
environment, not a clean hardware-controlled code comparison.

## Checkout lifecycle

Each main run offered 10 authenticated checkouts/sec for 20 seconds, then observed Saga completion for at most 110
seconds per order. The Order API is called directly, with the gateway quota bypassed explicitly. Acknowledgment latency
is not Saga latency. No PostgreSQL/Kafka financial durability settings were relaxed. Three Kafka processes use
replication factor 3 and min ISR 2.

| Run                                                 | Accepted / offered | Completed within deadline | Timed out | Command p95 | Saga p95 among completions | Completed / full wall second |
|-----------------------------------------------------|-------------------:|--------------------------:|----------:|------------:|---------------------------:|-----------------------------:|
| baseline10-isolated, Docker                         |          201 / 201 |                       201 |         0 |    1,345 ms |                   42.085 s |                        3.271 |
| tuned10, Docker, aggressive polling                 |          197 / 200 |                       159 |        38 |    4,411 ms |                  108.835 s |                        1.209 |
| host-baseline10, reconstructed sequential publisher |          200 / 200 |                        23 |       177 |      385 ms |                  105.102 s |                        0.175 |
| final-ready-checkout, host, before broker recovery  |          200 / 201 |                         0 |       200 |    2,505 ms |             no completions |                        0.000 |
| recovered2, host, 2/sec for 10s after recovery      |            21 / 21 |                        21 |         0 |    1,120 ms |                   10.195 s |                        1.577 |

The tuned10 run also had three rejected/failed submissions. All its 197 accepted orders eventually completed outside the
measurement window; that does not erase the 38 measured deadline misses. The earlier Docker baseline recorded 578 Saga
polling HTTP errors because a not-yet-created Saga returned 500; resource-not-found now returns RFC Problem 404. The
host baseline recorded zero polling errors. After the measurement window it drained to 161 COMPLETED and 39 COMPENSATED
after reservation expiry. A read-only cohort audit matched all 200 provider/local balances, order/Saga terminal states
and inventory allocation/release. It is a controlled source variant rebuilt by benchmarks/build-baseline.py, not a saved
historical image.

Initial baseline10 used insufficient preallocated VUs, offered only 150 requests and dropped 50. baseline10-full ran
while verification work was active. Both are retained as diagnostics, not headline comparisons. budget10 aborted during
the pre-load Prometheus scrape and has no throughput measurement. Never treat a preflight abort as a zero-throughput
system result.

## Observed bottlenecks and changes

The original relay waited for every Kafka acknowledgment before sending the next message. It now enqueues a bounded
batch of 32 and waits under one acknowledgment deadline, marking only acknowledged messages. PostgreSQL tests verify
batch enqueue and partial acknowledgments; inbox identity and domain idempotency remain required after lost
acknowledgments. Producer max.block.ms plus the shared deadline bounds network waiting; this is not a guaranteed
three-second transaction duration. Publication timestamps now use clock_timestamp rather than transaction-start now().

One scheduled thread also serialized relay and provider/recovery jobs. The optional tuned profile separates bounded
scheduling and polling, with explicit connection limits. Faster polling increased pressure and regressed the measured
Docker lifecycle, so no positive end-to-end speedup is attributed to it. In that run the VM had approximately 430 MiB
MemAvailable and only 43 MiB free of roughly 10 GiB swap; heavy swap I/O and controller churn dominated. Moving OMS
application JVMs to the host did not remove shared infrastructure contention. Scaling targets require dedicated,
provisioned resources and a new run.

The row-by-row projection rebuild made several SQL calls per historical event. Its measured baseline was 28.680 seconds
for 1,953 buffered events, with valid versions/timeline afterward. The replacement uses two set-based inserts from
immutable buffered full-state events, selects only contiguous version prefixes, preserves zero-version rows for missing
first events, and suppresses all external side effects. The deployed replacement measured **1.404 seconds for 2,014
events across 1,002 orders**, with zero version mismatches and exactly 2,014 timeline rows. The observed duration fell
by about 95%; shared-host conditions and the slightly different history sizes prevent treating this as a clean
hardware-controlled code-only speedup. A PostgreSQL test covers missing versions, duplicate/reordered delivery and both
historical numeric/current ISO timestamps. Rebuild still takes a local exclusive table lock and is not an online
cross-store rebuild.

## Reproduction and evidence

See benchmarks/README.md for exact commands and methodology. Raw local reports, k6 logs, hardware/image identities,
VM/container samples, outbox/provider dwell, and launch hashes live under ignored benchmarks/results. Safe selected
reports are retained in docs/evidence/benchmarks. Unit and failure evidence is separate from performance evidence.

The initial CQRS query run achieved 998 successful responses in about 10 seconds, zero HTTP errors, p95 61.49 ms, p99
108.06 ms, and three dropped iterations with ten preallocated VUs. That failed its no-drop threshold. It uses a small
50-order working set, not a large random customer population. Follow-up results are recorded separately.

Rates of 100, 1,000, 5,000 and 10,000 orders/sec and 100,000 query RPS remain unverified. No 99.99% availability claim
is made.

The final rollout exposed another measured local bottleneck: starting a Java process for every Docker health probe
repeatedly exceeded its 3-second limit while direct HTTP readiness returned 200. All Compose profiles now use a Bash
builtin HTTP probe instead of starting a JVM; the runtime Dockerfiles no longer compile the Java health helper. Live
positive and non-200 checks passed in 1.375/0.911 seconds including Docker CLI overhead. Under the overloaded VM the
provider cold start took 191 seconds, exceeding the launcher’s 180-second wait; it subsequently became healthy without
ledger changes. A campaign aborted during this readiness preflight created no orders. Host startup now waits for each
service before starting the next.

The final ready CQRS run passed at **100.03 responses/sec**: 1,001 responses, zero HTTP errors and zero dropped
iterations, p95 **34.65 ms**, p99 **119.28 ms** over the 50-order working set. The earlier token-timeout and readiness
preflight aborts remain recorded as non-load failures. Startup launchers now allow a bounded 300-second default local
cold-start wait (OMS_STARTUP_TIMEOUT override), reflecting the observed 191-second provider startup rather than forcing
another restart.

The final-ready checkout run had one failed submission, 200 accepted orders and no completions within the 110-second
observation deadline (zero dropped iterations and polling errors). Kafka logs showed repeated KRaft elections and
2-second quorum RPC timeouts. Recovery restored the previously working 256-MiB broker heap, removed the aggressive
heap-free-ratio settings, assigned bounded relative CPU shares, and configured 10-second quorum request / 30-second
election and fetch budgets. RF3/minISR2 and clean election remained mandatory. All three order-event partitions returned
to ISR=3. All 200 accepted orders subsequently COMPLETED, and the independent cohort audit matched provider/local
balances, order/Saga state and confirmed inventory. This does not change the failed latency result or establish
10-order/sec capacity.

Kafka parameter semantics were checked against
the [official Kafka 3.9 broker configuration](https://kafka.apache.org/39/configuration/broker-configs/). Longer
failure-detection budgets trade slower failover detection for tolerance of the observed local scheduling pauses; they
cannot manufacture CPU, RAM or I/O capacity. The recovery profile is a local configuration, not a production sizing
recommendation.

After recovery, a bounded follow-up at **2 orders/sec for 10 seconds** accepted and completed all **21** iterations with
zero errors, timeouts, drops or polling errors. Saga p95 was **10.195 s** and command p95 **1,120 ms**, full run
including drain 13.314 s. Its cohort audit passed. This smaller workload verifies post-recovery functionality; it is not
evidence that the failed 10-order/sec target now passes.
