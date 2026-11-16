# Legacy local-simulator opening balance migration

This protocol applies only to the original OMS local simulator. It does not invent settlement history for a real
processor or call financial authorization/capture/refund APIs to recreate past effects.

Flyway V4 classifies rows with local financial audit but no provider operation history as LEGACY_BLOCKED; V5 also
quarantines unaudited pre-provider rows rather than assuming they are safe native intents. V6 detects mixed histories
where a local operation lacks a corresponding provider operation, even if newer provider requests exist. The dispatcher
excludes quarantined origins. Mixed histories cannot use opening-balance import: they remain blocked for explicit
reconciliation. Stop old payment workers before migrating so an old binary cannot submit in-flight work during
classification. Ordinary financial commands cannot proceed for those rows. The administrator explicitly attests
`oms-local-simulator-v1` and prepares a deterministic migration ID. Payment locks the intent, validates state, capture
totals, refundable balance, refund audit totals and historical result bounds, then stores a canonical manifest and
SHA-256 digest. Origin becomes MIGRATING.

The worker commits its claim/fence before HTTP. It looks up an existing import before submitting an opening balance. The
provider transaction refuses to overwrite native accounts/history, imports the opening balance, records provenance and
reserves historical operation IDs. Import does not append provider financial requests. A lost response is resolved by
the same migration ID/digest. Payment verifies the returned manifest and its still-frozen source before marking
VERIFIED/MIGRATED and importing historical operation keys as completed records. A stale worker cannot unblock the
payment. Repeated prepare/import is idempotent; conflicting native state or invalid evidence stays blocked/manual
review.

Historical API-key replay returns recorded local success without another provider call. The provider independently
refuses to execute reserved historical keys, including after a process restart. Both native execution and imports
acquire the same transaction-scoped operation-identity advisory locks before account locks; ordered key acquisition also
protects cross-account key races. New refunds after migration use the remaining eligible captured amount. Migration
actions, actor and digest are retained in an audit table.

```sh
# Choose the active deployment file explicitly.
OMS_COMPOSE_FILE=compose.tuned.yml python3 scripts/legacy-migrate.py
OMS_COMPOSE_FILE=compose.tuned.yml python3 scripts/legacy-verify.py
```

The existing five legacy rows were verified through this protocol. A repeat migration found zero candidates. The
verification script compares both balances and replays historical capture keys; it does not issue new financial
operations. Tests separately cover eligible post-migration refund, inconsistent audit rejection, conflicting provider
state, key reservation, classifier migration and stale importer fencing.

Manual review requires inspecting the immutable manifest and provider import, then retrying the same migration identity.
Do not delete conflicting native accounts, edit a saved manifest or generate a new migration ID to escape uncertainty.
Production runtime/migration role separation and independent finance approval policy remain deployment responsibilities.
