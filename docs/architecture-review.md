# Architecture review — 50 questions

All answers share the local-ACID/distributed-eventual boundary described in requirements.md. No answer establishes
production readiness or external-provider correctness. Performance targets remain unmeasured.

## 1. How do distributed transactions work?

Each participant commits locally; the Saga drives explicit next actions and compensations. Durable outbox/inbox and
idempotent state machines bridge partial failures without a global commit.

## 2. Why is traditional ACID insufficient across independently owned services?

A PostgreSQL transaction cannot atomically commit a separate service database or arbitrary payment provider. XA would
couple resource availability and ownership and is deliberately excluded.

## 3. How does Saga orchestration work?

The Saga stores a cursor, command ID, deadline and history before sending. Only the current command’s causal response
advances it; timeout repeats the same identity.

## 4. How does choreography work?

Participants react to facts and emit new facts. The isolated ChoreographyDemoTest demonstrates the chain without
touching production orders; notifications are a noncritical event reaction.

## 5. When prefer orchestration?

Checkout has explicit financial compensation and operator visibility needs. Central durable progress makes diagnosis
clearer while coupling the coordinator to participant contracts.

## 6. What is compensation?

A new business action offsets a completed action, such as void, refund or release. It must have its own idempotency
identity and recoverable state.

## 7. Why is compensation not rollback?

Refunds leave financial history and shipped parcels cannot be unshipped. Reverse steps preserve facts and can stop for
manual returns handling.

## 8. How recover crashed orchestrator?

Read persisted cursor/deadline and reopen the same outbox command. Row locks serialize recovery and live response
handling; no JVM-only cursor exists.

## 9. How make steps idempotent?

Stable command IDs, transactional inbox, participant business uniqueness and state checks. This protects local SQL
effects; external effects additionally need provider semantics.

## 10. How prevent stuck Sagas?

Persist deadlines, scan due work and bound retries. Uncertain or failed compensation becomes MANUAL_REVIEW, which needs
an operational response SLA.

## 11. What is TCC?

Try grants provisional stock ownership; Confirm commits allocation and Cancel releases provisional ownership. Only
Inventory participates in this protocol.

## 12. How does Try differ from Confirm?

Try increases reserved stock and sets a TTL. Confirm transfers reserved to allocated under the same reservation lock and
expiry check.

## 13. Cancel before Try?

Insert a terminal cancellation tombstone. Delayed Try locks the row and rejects resurrection.

## 14. Duplicate Confirm/Cancel?

Terminal matching decisions are no-ops. Opposing decisions cannot override a newer terminal state, and confirmed
allocation uses a distinct release action.

## 15. Reservation expiration?

A bounded SKIP LOCKED sweep cancels overdue TRIED reservations. Confirm checks expiry itself so correctness does not
rely on sweep punctuality.

## 16. Can payment gateways support TCC?

Only if their authorization/capture/void semantics match the business ownership and expiry requirements. This project
does not label the simulator or an arbitrary provider as TCC.

## 17. Prevent overselling?

Available is on-hand minus reserved minus allocated. Conditional SQL updates and database constraints prevent negative
availability; SKU locks are acquired in sorted order.

## 18. What is CQRS?

Order commands validate authoritative history while Query owns a disposable read model. Consumers apply contiguous
versions and never authorize stock or financial mutation from projections.

## 19. When is CQRS useful?

History/timeline and read scaling justify separate projections here. It would be removed if ordinary indexed
command-side queries met requirements.

## 20. Handle stale projections?

Command response contains local version; clients poll with minVersion. Lag yields 202, and absence/unknown ownership
yields 404 without pretending immediate consistency.

## 21. Rebuild read models?

Replay locally buffered immutable projection events inside an exclusive-lock transaction. At scale use a shadow
generation and high-watermark catch-up; that mechanism is not implemented.

## 22. What is Event Sourcing?

Order history is authoritative and append-only. Other service tables remain ordinary transactional state rather than
forced event stores.

## 23. Reconstruct aggregate?

Read events ordered by aggregate version and apply a pure transition function. Replay never invokes Messaging, provider
calls or shipment creation.

## 24. Why optimistic concurrency?

Two commands can both read version N. Unique aggregate/version permits only one N+1 append, forcing the loser to
reconstruct before deciding again.

## 25. Event schema evolution?

Schema1 is the supported contract; unknown envelope versions reject. New versions require backward fixtures/upcasters
rather than rewriting historical meaning.

## 26. Why replay without effects?

Replaying a fact is not a new business command. Pure reconstruction prevents duplicate charges and reservations while
enabling audits.

## 27. Domain versus integration events?

Order domain facts govern aggregate state. Integration envelopes transport facts across ownership boundaries; Saga
commands request work and are not authoritative Order facts.

## 28. What is Outbox?

A committed database record of publication intent written with state. Relay retries after broker acknowledgment gaps,
accepting duplicates.

## 29. Why independent PostgreSQL/Kafka writes unsafe?

Either write may succeed while the other fails. Outbox moves the atomic boundary into one database and makes transport
recovery explicit.

## 30. Polling versus Debezium?

Polling is simpler to run but consumes queries/locks and can contend with application work. CDC adds connectors,
replication slots and WAL operations while reducing application publication polling.

## 31. Why duplicate outbox delivery?

Broker acknowledgment can precede the database published marker. A crash in between causes a safe repeat with the same
identity.

## 32. What is Inbox?

A unique consumed-message record committed with local effects and response intents. Offset acknowledgment happens
afterward.

## 33. Idempotent Kafka consumer?

Validate envelope, insert inbox, mutate under local transaction, emit outbox, commit, then acknowledge. A rollback also
removes the inbox claim so a real retry remains possible.

## 34. Kafka exactly-once business guarantee?

No. Kafka transactional semantics do not wrap PostgreSQL or an arbitrary provider; local deduplication and external
idempotency remain necessary.

## 35. Ordering across partitions?

No global ordering is promised. Aggregate keys localize relevant order, while version checks and causal IDs handle
relay/retry reordering.

## 36. Out-of-order events?

Projection buffers missing versions; Saga accepts only current causal response. Participant terminal state checks reject
stale commands that would resurrect cancelled resources.

## 37. Order creation idempotency?

Unique customer/key plus stored normalized JSON request and order ID share the creation transaction. Changed payload
returns conflict; same payload returns the existing order.

## 38. Prevent duplicate capture?

Payment durably queues an operation, calls a separate provider outside its database transaction, and reconciles the same
provider identity after uncertainty. Both ledgers enforce capture/refund limits. The selected simulator supplies durable
idempotency; arbitrary providers need independently verified semantics.

## 39. Payment succeeds confirmation fails?

Retry confirmation where outcome is unknown. A definitive failure can trigger fulfillment cancellation, reversal and
stock release; uncertainty escalates instead of blind refund.

## 40. Reconcile ambiguous provider outcomes?

The implemented HTTP adapter persists operation identity before the call, queries status after uncertainty, and commits
the local result/outbox only under a live fence. Definite absence permits resubmission with the same identity.
Unresolved operations reach manual review and block later financial work; the simulator owns its own database.

## 41. Compensation failures?

Keep current durable step and history; bounded timeout reaches manual review. Shipped fulfillment rejection deliberately
stops automatic financial and inventory reversal.

## 42. Coordinate cancellation during Saga?

Serialize cancel request with replies using Saga row lock. Wait for current definitive response before reverse steps so
capture cannot race a speculative refund.

## 43. Database per service?

Compose provisions distinct PostgreSQL databases/users and Flyway runs per deployable. Production separates migration
ownership from runtime permissions.

## 44. Why no shared mutable tables?

Shared writes bypass service invariants and couple migration/release timing. Integration contracts permit eventual
collaboration while each owner controls concurrency.

## 45. Service authentication?

HTTP APIs validate issuer/audience/signature and scopes inside each service. Kafka production TLS/ACL and
workload-specific identities remain deployment gates.

## 46. Safe retries and breakers?

Retry only stable idempotent operations with bounded waits; unknown effects require reconciliation. HTTP timeouts and
gateway bulkhead exist; circuit breakers and jitter remain unfinished.

## 47. Test correctness?

Use real PostgreSQL competing transactions, plus black-box service flows across Kafka. Unit mocks alone cannot prove
database constraints, broker recovery or provider behavior.

## 48. Benchmark Saga latency?

Measure from accepted OrderCreated time to durable terminal Saga state, separately from HTTP acceptance. Offered and
achieved throughput, lag and compensation latency must be reported separately; no scale result is claimed.

## 49. What complexity is introduced?

More persisted histories, duplicate/gap handling, schema compatibility, retention and operator recovery. Storage and
relay contention grow with messages per order, not merely HTTP requests.

## 50. What patterns remove without justification?

Remove Event Sourcing if audit/replay is unnecessary, CQRS if indexed queries suffice and TCC if stock needs no
provisional hold. Keep outbox/inbox for durable asynchronous state changes; collapse services when ownership/scale do
not justify distribution.

