# ADR 0001 — one primary checkout coordinator

Accepted: durable Saga orchestration coordinates six participants/steps with local outbox/inbox. Inventory alone exposes
TCC. Order history is event sourced; catalog and stock are ordinary constrained state. Query owns contiguous
projections. No XA, Redis lock or second production choreography coordinator.

Capture follows allocation and precedes business confirmation. This creates recoverable paid-pending exposure rather
than confirmed-unpaid exposure. Fulfillment is last and reversal checks its physical boundary first. Unknown outcomes do
not count as business failures. Timeout retries preserve identity and then escalate.

Polling relay is operationally simple but holds DB locks during Kafka acknowledgment; measure before replacing with CDC.
Snapshots are omitted for short Order streams. Infrastructure library contains no shared aggregate or repository
business rules.
