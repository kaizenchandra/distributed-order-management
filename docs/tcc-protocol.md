# Inventory TCC

Available = on_hand − reserved − allocated. Confirm transfers reserved to allocated; it does not deduct on_hand.
Shipping stock deduction is not modeled. Cancellation of an unconfirmed reservation releases reserved; a separately
authorized allocation Release compensates confirmed allocation before shipment.

Reservation IDs equal order IDs for Saga commands. REST Try accepts reservationId and orderId. Confirm/Cancel require an
orderId body matching the stored owner. Only oms.service callers can use these APIs. Unique IDs are inserted before
locking; empty rollback creates a durable CANCELLED tombstone. Delayed Try cannot resurrect it. Same Try ID requires
identical items. Confirm and Cancel lock the same row, so only a valid state transition wins. Duplicate terminal
operations are no-ops.

SKU conditional updates enforce available ≥ quantity. Multi-SKU mutations occur in one transaction, in sorted SKU order
to reduce deadlocks. Database constraints independently enforce nonnegative stock and reserved + allocated ≤ on_hand.
Stock adjustment APIs are intentionally absent. Expiry scans 50 rows with SKIP LOCKED every five seconds and releases
TRIED stock. A confirm checks expiry under the same lock. Confirmed allocations never expire automatically.

A reservation held while payment authorizes can expire. Confirm then rejects; Saga reverses payment and releases any
remaining stock. No tombstone deletion policy is safe until maximum command replay/delay retention is defined.
