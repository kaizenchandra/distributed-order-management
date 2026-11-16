# Durable payment timeout reconciliation

Payment now calls a separate HTTP provider simulator with its own PostgreSQL database. The simulator records operation
identity, payload and result atomically with its financial ledger. It exposes lookup and indefinite stable-key
idempotency. These are explicit simulator capabilities, not assumptions about arbitrary processors.

## Transaction boundaries

1. Payment commits inbox identity and a PENDING provider operation together. HTTP commands return 202 with an operation
   ID.
2. A worker claims one eligible operation using a ten-second lease and random fencing token, then commits. Earlier
   unresolved operations block later work for that payment.
3. Outside any database transaction, submit once; after uncertainty or a reclaimed lease, look up the same operation
   first. A definitive absence permits submission with the same idempotency ID. A concurrent delayed submit remains safe
   under this provider's durable uniqueness model.
4. A matching definitive result is committed with the local ledger, operation status and response outbox. The worker
   must still own an unexpired fence. Wrong operation/payment/action identities are treated as uncertain.
5. A timeout, unavailable status lookup, invalid response, or failure to commit the local result remains UNKNOWN.
   Exponential retry delays are bounded at 30 seconds. After eight attempts the operation becomes MANUAL_REVIEW. No
   business rejection or speculative reversal is emitted.

A crash after provider commit but before local commit is recovered by lookup. Stale workers cannot overwrite newer local
state; provider idempotency protects external calls beyond the fencing boundary. Pending reverse/refund operations
cannot pass an unresolved capture. A late matching Saga reply can resolve Saga manual review without reissuing a
financial operation.

## APIs and operations

- GET /api/v1/payments/operations/{operationId}: service-scoped operation status.
- GET /api/v1/admin/payments/{paymentId}/operations: operator inspection.
- POST /api/v1/admin/payments/operations/{operationId}/retry: resume lookup of the same identity from MANUAL_REVIEW.
- Existing authorization/capture/void/refund POST endpoints now acknowledge a durable operation with 202; they do not
  promise immediate settlement.

The provider requires a generated X-Provider-Key shared only with Payment and local test tooling. Its internal HTTP API
is isolated from the public gateway. A real deployment should supply TLS and a real-provider adapter with verified
lookup, retention and idempotency semantics. Refund eligibility is checked by both ledgers under payment-level locks.

## Reproducible faults

Checkout paymentScenario can select authorize-timeout, capture-timeout, refund-timeout, decline, or capture-unknown.
Timeout faults commit a provider result before delaying the initial response beyond Payment's timeout. capture-unknown
hides a committed result until the protected simulator reveal endpoint is used. These are simulator test controls, not
customer payment-provider features.

## Migration boundary

Legacy local-simulator rows from the initial baseline do not automatically become external-provider accounts. Existing
paid legacy rows require explicit migration/reconciliation before financial operations. The new adapter refuses to
silently accept a provider/local amount divergence. Fresh checkouts use the separate provider from their first
authorization.

Financial input validation rejects fractional/overflow/nonpositive minor units before a provider operation can be
queued; the provider independently rejects malformed money. Spring error dispatches retain their intended 400/404/503
status codes, so an authenticated absent lookup can be distinguished from uncertainty.
