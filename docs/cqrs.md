# Projection consistency

Command acknowledgment follows Order's local commit and returns version and command-side state. Read model appearance
and advancement are asynchronous. minVersion provides polling: 202 and Retry-After when present but behind; 404 when not
yet present or not owned. Reads never authorize inventory or financial mutations.

Projection events are buffered by aggregate/version, and only a contiguous prefix applies. Duplicates do not overwrite
state. Version gaps remain buffered and require source/transport investigation. Timeline and read state commit
atomically with inbox identity. The durable per-order version is the checkpoint.

Admin rebuild takes an exclusive read-model lock and replays the locally retained projection_events history in order,
without external effects. It blocks live projection writers until commit. This is a reproducible small-deployment
rebuild, not an online shadow-table rebuild at target scale. Loss of the projection event buffer requires an event-store
export/reimport workflow, which is not automated here.

Projection rebuild now uses set-based PostgreSQL statements over buffered full-state integration envelopes, retaining
only the contiguous version prefix for each aggregate. Missing first versions retain a zero-version model; later gaps
cannot advance state or timeline. The local table lock prevents the consumer from interleaving mutations; incoming
messages resume afterward. The unit test checks gaps, duplicate/reordered input and absence of outbound effects. This
removes per-event database round trips but does not provide online cross-store rebuilding or restore events absent from
the query history.
