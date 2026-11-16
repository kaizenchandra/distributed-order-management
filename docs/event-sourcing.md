# Order event history

Events contain aggregate ID/version, unique event ID, event type, schema version, immutable payload, timestamps,
correlation and causation. Unique (aggregate_id, aggregate_version) enforces expected-version appends; callers
reconstruct and validate commands again after conflicts. Append and event outbox insert share a transaction. Lost
checkout acknowledgments are resolved through the customer/key mapping committed in that same transaction.

Order.load replays events in version order through the pure aggregate. It emits no messages. Every event currently
carries the resulting small order state. This reduces migration complexity but repeats item payloads. Database triggers
prohibit UPDATE/DELETE of history; production runtime credentials should also be stripped of table ownership and DDL
privileges. Event retrieval is admin-only.

No snapshots are introduced because streams currently have at most three facts. This is deliberate: snapshot
version/upcast correctness costs more than replaying three events. Schema version 1 is supported; unsupported envelopes
are rejected, and no historical payload migration is silently performed. Upcasters must be implemented alongside
immutable historical fixtures before version 2 is admitted.
