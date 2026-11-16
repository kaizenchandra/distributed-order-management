# Ownership map

See the service/port table in ../README.md and architecture.md. No service may read or write another owned database.
Every integration uses a versioned envelope or JWT-protected API. Inventory is the sole stock authority; Catalog
availability is never used for reservation. Query is disposable; Payment alone validates refundable capture; Fulfillment
owns the physical handoff boundary.
