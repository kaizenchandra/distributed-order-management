# Event contracts

Envelope fields: eventId UUID, eventType string, aggregateId UUID, aggregateVersion integer, schemaVersion 1,
correlationId UUID, causationId nullable UUID, timestamp RFC3339 and payload object. Aggregate key is Kafka key.
Commands and replies use aggregateVersion 0; Order facts use contiguous versions 1..N. UUIDs are stable names derived
from logical operation identity. Payload facts use minor-unit integer USD amounts and frozen priced items.

Order facts: OrderCreated, OrderConfirmed, OrderCancelled. Commands: Checkout.Start/Cancel;
Inventory.Try/Confirm/Release; Payment.Authorize/Capture/Reverse; Order.Confirm/Cancel; Fulfillment.Start/Cancel.
Responses append .Ok or .Rejected, contain reason, and refer to the command envelope ID in causationId. Kafka topics are
oms.<service>, oms.order-events, and .DLT variants.

Example payload: {"orderId":"UUID","customerId":"OIDC subject","status":"PENDING","amount":17500,"currency":
"USD","items":[{"sku":"SKU-1","quantity":2,"unitPrice":7500},{"sku":"SKU-2","quantity":1,"unitPrice":2500}]}. Test-only
paymentScenario decline selects deterministic rejection.

Kafka client retries/rebalances can duplicate deliveries. Per-partition broker ordering is insufficient to eliminate
source relay reordering. Query buffers gaps; Saga validates current command causation. Schema1 is the only currently
supported version. Removing fields or changing amount meaning is breaking and requires historical compatibility work
before rollout.
