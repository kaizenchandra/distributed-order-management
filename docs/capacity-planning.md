# Capacity model — targets, not measured capability

10M orders/day = 115.74/s average. 10k/s peak is 86.4× average. 100k/s queries at 99% cache hit still leave 1k/s origin
reads; no cache is implemented. Targets require horizontal sharding and measured sizing.

Assume successful checkout: 2 order facts, 6 Saga commands, 6 responses, 2 projection events; projection and
notification each consume 2 facts. 15 distinct published messages/order (domain facts are shared with two consumer
groups), 17 handler inbox records/order (start + 6 responses + 6 participant commands + 4 projection/notification
deliveries). Publication count is 2 facts + 1 start + 6 commands + 6 responses = 15. Measure exact counts with SQL
before sizing. Compensation adds up to 8 command/response messages plus cancellation fact.

At 2 KiB/event, 2 facts × 10M/day ≈ 40 GB/day raw event payload, before indexes, WAL, replicas and backups. At 15 × 2
KiB/message ≈ 300 GB/day broker payload, multiplied by replication and retention. Inbox 17 × 10M × estimated 100 B = 17
GB/day before indexes. Saga history stores about 12 rows/order; with 200 B/row ≈ 24 GB/day plus instance payload and
indexes. Retention is mandatory at scale.

At 10k/s and 15 messages/order, publish target is 150k/s. If a measured partition sustains P messages/s at desired
latency, partitions ≥ ceil(150000/P), with extra headroom and skew testing. No partition throughput is assumed. Relay
capacity per worker is batch / batch elapsed time; slow per-message acknowledgment is a known baseline bottleneck.
Recovery catch-up seconds = backlog / (measured drain rate − incoming rate), only when drain > incoming.

Pool sizing: Little's Law concurrent DB work ≈ throughput × mean DB transaction seconds. Example 100/s × 0.01s = 1
active connection on average, but lock contention and p99 require measurement. Default pool 8 is a local bound, not a
production recommendation. No HPA setting can overcome one hot SKU row or exhausted database capacity.

Run k6 with RATE=100,1000,5000,10000 separately; use a token lifespan covering the run. Gateway's local 50 requests/s
per-subject limit intentionally prevents one-account large tests. Use distributed customer identities or a controlled
isolated load profile. Checkout acceptance latency does not measure Saga completion; poll Saga terminal times
separately. No throughput or SLO is certified.

The separate payment provider adds two normal HTTP operations per successful checkout (authorization and capture), plus
status lookups after ambiguous calls. Payment adds a queue insert, claim update and terminal update for each operation,
alongside the existing financial audit and inbox/outbox writes. The provider owns account/request writes in another
database. Timeout/reconciliation and manual-retry traffic must be included in capacity measurements; the Kafka message
count remains unchanged because only definitive financial outcomes produce Saga responses.
