# Requirements and acceptance

Customers check out bounded SKU quantities with authoritative prices, inspect owned orders and projections, and request
cancellation. Internal operators inspect/retry workflows; stock, financial state and shipment handoff remain separately
owned.

Invariants: one logical order per customer/key and identical payload; unique event version per order; reserved +
allocated ≤ on-hand; terminal inventory decisions cannot resurrect; captured amount remains fixed; refunded ≤ captured;
customer isolation at every order/query API; only current command replies advance Saga; replay has no integration side
effects.

Local transactions are ACID. The checkout is eventually consistent across databases. There is no global commit or
automatic cross-region failover. At-least-once outbox delivery plus inbox deduplication gives one committed local
handler effect per message ID. Business uniqueness/state validation additionally protects equivalent operations with
different message IDs where implemented. There is no universal exactly-once guarantee.

Acceptance evidence is recorded in verification.md. Unexecuted targets are requirements, not achieved results. Inventory
zero overselling is scoped to this accounting convention and service-controlled mutations. Real provider settlement
correctness is outside the simulator's proof boundary.
