# Outbox

A local state change, inbox (when applicable) and outbound intents commit together. Relay claims at most 32 eligible
rows with FOR UPDATE SKIP LOCKED, waits for Kafka acknowledgment, then marks published_at. Failure increments attempts
and delays ten seconds; publisher retries indefinitely at bounded throughput. Process death rolls back claim locks.
Crash after broker acknowledgment duplicates the event. Published rows are retained; no premature deletion occurs.

Outbox event identity includes topic-independent logical command/event identity. Topics are stable per participant.
Kafka aggregate key partitions order-related traffic. Pending index supports lag scans. Production needs adaptive
backoff/jitter, alerting, retention/archive, pool isolation and per-aggregate publication fencing for applications
requiring strict source order. Version-aware query and causal Saga checks handle the baseline's possible reordering.

The bounded relay enqueues the batch before awaiting futures, rather than paying one Kafka round trip per row. It
retains per-record acknowledgment outcomes, a shared deadline, producer blocking bounds, retry backoff and
transaction-held claims. `clock_timestamp()` records actual publication time; PostgreSQL `now()` would record
transaction start and understate relay latency. Batching does not make PostgreSQL and Kafka one atomic commit and does
not guarantee source order across multiple relays. See the real PostgreSQL MessagingTest and measured performance
report.
