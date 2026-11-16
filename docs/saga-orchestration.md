# Durable Saga protocol

`STARTED → RUNNING(step 0..5) → COMPLETED`, or `RUNNING(compensating, step 0..3) → COMPENSATED`. Repeated timeout or
compensation rejection → `MANUAL_REVIEW`. State and step history live in the Saga database. This intentionally uses
fewer states than the illustrative master prompt.

Each dispatch and durable cursor/deadline update commits with an outbox record. Message identities derive from saga ID,
direction and step. Replies carry the dispatch identity as causation ID. A reply to any earlier command cannot advance
the current cursor. A late definitive response matching the current command may resolve a timeout-induced MANUAL_REVIEW;
it is not discarded as stale. Inbox deduplication and row locks serialize handling across replicas.

A 20-second deadline triggers the same message identity again, up to five attempts. All sends and cursor transitions
occur while holding the Saga row lock; no network call happens from Saga workers, and no stale lease holder exists. This
lock-based fencing avoids adding a second lease coordination layer. Participants commit effects/replies together.
Uncertain timeouts escalate rather than starting blind financial compensation.

Capture precedes business confirmation. This avoids confirmed-but-unpaid orders but permits paid-but-pending orders. A
known rejection starts controlled reversal; a timeout retries the durable command and then requires reconciliation.
Confirmation before capture would improve provisional UX but requires an explicit payment-pending order state. Capture
before inventory allocation would increase refund exposure; allocation first is selected.

Cancellation during a running step sets a flag and waits for that step's definitive response. After completion it starts
reverse steps. Fulfillment cancellation runs first; if physically shipped, refund and inventory release do not run
automatically. Payment.Reverse voids an authorization or refunds remaining capture. Confirmed inventory uses Release,
not TCC Cancel. Compensation is a new business operation rather than erasing history.

Administrative retry only reopens the same pending command from MANUAL_REVIEW. Inspect participant records before retry;
ambiguous states cannot be manually forced into compensation through this API. Full audited operator resolution remains
a production gap.
