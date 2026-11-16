# MASTER PROMPT: Design and Develop an Enterprise-Grade Microservices-Based Order Management System Using Java 21, Spring Boot, Saga, CQRS, Event Sourcing, Transactional Outbox, and TCC

## 1. ROLE AND ENGINEERING RESPONSIBILITIES

Act as a **Principal Software Engineer, Distinguished Distributed Systems Architect, Enterprise Solution Architect, Java
21 Expert, Spring Boot Architect, Domain-Driven Design Expert, Event-Driven Architecture Specialist, Database Architect,
Payment Systems Engineer, Cloud Architect, and Site Reliability Engineer** with 15+ years of experience designing
mission-critical distributed transaction systems for banking, fintech, e-commerce, and large-scale enterprise platforms.

Your task is to **design, develop, test, benchmark, document, and deploy a complete Microservices-Based Order Management
System (OMS)** using Java 21 and Spring Boot.

The architecture must demonstrate the practical application of:

1. Domain-Driven Design (DDD)
2. Microservices Architecture
3. Saga Orchestration
4. Saga Choreography — as a comparison and targeted demonstration
5. CQRS
6. Event Sourcing
7. Transactional Outbox
8. Transactional Inbox / Idempotent Consumer
9. Try-Confirm/Cancel (TCC)
10. Database-per-Service
11. API Gateway
12. Event-Driven Architecture
13. Idempotency
14. Optimistic and Pessimistic Concurrency Control
15. Eventual Consistency
16. Compensation and Reconciliation
17. Circuit Breaker, Retry, Timeout, and Bulkhead
18. Observability and Distributed Tracing
19. Security and Service-to-Service Authentication
20. Automated Integration, Contract, Concurrency, and Failure Testing

Treat this as a real enterprise engineering project, not merely a system design interview exercise.

**Deliver complete working source code, actual microservices, data models, messaging infrastructure, automated tests,
Docker/Kubernetes deployment, and operational documentation.**

### Mandatory engineering principles

- Use Java 21 and a verified compatible Spring Boot release.
- Follow existing repository conventions, architecture, dependency versions, and coding standards.
- Apply DDD, SOLID, Clean Architecture, and Hexagonal Architecture pragmatically.
- Use explicit service boundaries based on business capabilities and data ownership.
- Each microservice owns its database schema and transactional boundaries.
- Never perform cross-service database joins or share mutable domain tables.
- Do not use distributed XA/2PC transactions as the default solution.
- Use Saga or TCC only where the business workflow and participant capabilities justify them.
- Do not require every service to implement Event Sourcing.
- Do not introduce CQRS solely to create duplicate persistence models.
- Use Transactional Outbox wherever a committed database state change must reliably publish an integration event.
- Design every consumer for at-least-once delivery and duplicate processing.
- Use bounded retries, timeouts, connection pools, thread pools, and backpressure.
- Handle idempotency, ordering, concurrency, partial failures, schema evolution, and transaction boundaries explicitly.
- Prefer `BigDecimal` or integer minor units for money.
- Never use floating-point arithmetic for financial values.
- Never store sensitive payment credentials or secrets in source code or logs.
- Never claim exactly-once distributed business effects without demonstrating the required guarantees.
- Never fabricate test results, benchmark measurements, or production readiness.
- Make routine technical decisions independently and explain significant trade-offs.

---

# PART A — PRIMARY OBJECTIVE

## 2. BUILD A DISTRIBUTED ORDER MANAGEMENT SYSTEM

Develop a multi-service system capable of handling the complete order lifecycle.

The system must support:

- Customer authentication
- Product catalog
- Product pricing
- Inventory availability
- Shopping cart or checkout request
- Order creation
- Inventory reservation
- Payment authorization
- Payment capture
- Order confirmation
- Shipping/fulfillment
- Order cancellation
- Refunds
- Order history
- Order status projections
- Distributed transaction recovery
- Audit history

### Example end-to-end scenario

A customer purchases two products.

The system must:

1. Authenticate the customer.
2. Validate product availability.
3. Calculate authoritative prices.
4. Receive an idempotent checkout request.
5. Create an order in PENDING state.
6. Persist an OrderCreated domain event.
7. Start a durable Order Saga.
8. Reserve inventory.
9. Authorize payment.
10. Confirm inventory allocation.
11. Confirm the business order.
12. Capture payment according to the selected policy.
13. Trigger fulfillment.
14. Update the order read model asynchronously.
15. Notify the customer.
16. Recover from failures at any stage.
17. Compensate or reconcile partial transactions.
18. Prevent duplicate inventory allocation and unintended duplicate charges.

### Fundamental business invariants

- The same idempotent checkout operation cannot create multiple logical orders.
- Inventory cannot be oversold under the documented reservation model.
- Payment authorizations, captures, and refunds are idempotent within supported provider semantics.
- Order state transitions are valid and monotonic where required.
- Duplicate events cannot repeat irreversible business effects.
- A failed Saga must eventually reach a valid terminal or explicitly recoverable/manual-review state.
- A confirmed order has a consistent recorded relationship to inventory, payment, and fulfillment state.
- The read model must never be treated as the authoritative source for financial or inventory mutations.

---

# PART B — BUSINESS CAPABILITIES AND MICROSERVICES

## 3. DEFINE BOUNDED CONTEXTS

Design the following services.

### 3.1 API Gateway

Responsibilities:

- Request routing
- JWT validation where appropriate
- Rate limiting
- Request correlation
- Routing-level authorization
- Request size limits
- Security headers

Do not assume gateway authentication replaces authorization inside individual services.

### 3.2 Order Service

Responsibilities:

- Order creation
- Order lifecycle
- Order items
- Order state transitions
- Checkout idempotency
- Order event persistence
- Order query APIs
- Order cancellation

### 3.3 Inventory Service

Responsibilities:

- Stock availability
- Inventory reservation
- Reservation confirmation
- Reservation cancellation
- Reservation expiration
- Stock adjustments
- No-overselling guarantees

### 3.4 Payment Service

Responsibilities:

- Payment intents
- Payment authorization
- Payment capture
- Payment cancellation/void
- Refunds
- Payment status
- Provider integration
- Idempotency
- Financial reconciliation

Use a deterministic payment simulator or an approved sandbox provider.

Never assume a successful external payment call is part of a local database transaction.

### 3.5 Shipping/Fulfillment Service

Responsibilities:

- Fulfillment requests
- Shipment creation
- Shipment state
- Carrier adapter
- Cancellation where permitted
- Idempotent fulfillment initiation

### 3.6 Product Catalog Service

Responsibilities:

- Products
- Product variants
- Product details
- Product availability hints
- Product information queries

Do not make cached catalog availability authoritative for inventory reservation.

### 3.7 Order Query / Projection Service

Responsibilities:

- CQRS read models
- Order history
- Customer order timeline
- Order status aggregation
- Projection rebuilding
- Query optimization

### 3.8 Notification Service

Responsibilities:

- Order notifications
- Payment notifications
- Shipment updates
- Retry handling
- Notification deduplication

Treat this service as asynchronous and noncritical to transaction confirmation.

### 3.9 Saga Orchestrator

Responsibilities:

- Durable workflow state
- Command dispatch
- Step tracking
- Compensation
- Timeouts
- Recovery
- Manual intervention

Implement as a distinct logical component or deployable service with an explicit ownership model.

---

# PART C — ARCHITECTURE PATTERN SELECTION

## 4. COMPARE DISTRIBUTED TRANSACTION PATTERNS

Evaluate:

| Pattern              | Purpose                                                                        |
|----------------------|--------------------------------------------------------------------------------|
| Saga                 | Coordinates a long-running distributed business transaction                    |
| CQRS                 | Separates command/write responsibilities from query/read models                |
| Event Sourcing       | Stores changes as an authoritative sequence of domain events                   |
| Transactional Outbox | Reliably bridges database commits and asynchronous message publication         |
| Transactional Inbox  | Deduplicates incoming messages and controls processing                         |
| TCC                  | Coordinates provisional resource reservations through Try, Confirm, and Cancel |

For each pattern, explain:

1. Problem solved
2. Architecture
3. Transaction boundaries
4. Consistency guarantees
5. Failure behavior
6. Advantages
7. Disadvantages
8. Operational cost
9. Appropriate use cases
10. Cases where the pattern should not be used

### Critical requirement

**These patterns solve different problems and are not interchangeable.**

Do not combine them mechanically or create duplicate coordination layers without a documented reason.

Select one clear primary checkout workflow.

Implement Saga orchestration as the primary business transaction pattern.

Use TCC for explicitly reservable resources such as inventory.

Use Event Sourcing for a selected bounded context, preferably Order, where auditability, replay, and event history
justify it.

Use CQRS for order query projections.

Use Transactional Outbox for reliable integration-event delivery.

---

# PART D — HIGH-LEVEL DESIGN

## 5. MICROSERVICES ARCHITECTURE

Design independent services with:

- Dedicated business ownership
- Dedicated database/schema
- API contracts
- Integration events
- Service authorization
- Independent deployment
- Independent scaling
- Failure isolation

Provide Mermaid diagrams for:

1. Overall architecture
2. Microservice boundaries
3. Checkout request
4. Order Saga
5. Inventory TCC
6. Payment authorization
7. Compensation
8. Event Sourcing
9. CQRS projections
10. Transactional Outbox
11. Transactional Inbox
12. Refund processing
13. Saga recovery
14. Kubernetes deployment

### Communication principles

Use synchronous REST/gRPC for operations that require immediate responses.

Use Kafka for durable asynchronous integration events and commands where justified.

Explain why synchronous calls and asynchronous events often coexist in the same business workflow.

Avoid long synchronous call chains during checkout.

---

# PART E — SAGA PATTERN

## 6. DESIGN SAGA ORCHESTRATION

Implement a durable Order Saga.

Example:

`Create Order → Reserve Inventory → Authorize Payment → Confirm Inventory → Confirm Order → Capture Payment → Start Fulfillment`

Evaluate the exact ordering of inventory confirmation, payment capture, and order confirmation.

Document the compensation and financial recovery implications of each ordering choice.

### Saga states

- STARTED
- INVENTORY_RESERVING
- INVENTORY_RESERVED
- PAYMENT_AUTHORIZING
- PAYMENT_AUTHORIZED
- CONFIRMING
- COMPLETED
- COMPENSATING
- COMPENSATED
- FAILED
- MANUAL_REVIEW

Choose a coherent state machine.

Define valid transitions.

### Mandatory Saga properties

- Durable workflow state
- Idempotent commands
- Idempotent responses
- Step execution history
- Timeout handling
- Bounded retries
- Compensating actions
- Crash recovery
- Duplicate event handling
- Out-of-order event handling
- Administrative inspection
- Manual intervention

Never store the only copy of Saga progress in JVM memory.

---

## 7. SAGA COMPENSATION

Design compensation for:

### Inventory reserved, payment failed

Release inventory.

### Payment authorized, inventory confirmation failed

Void or cancel payment authorization if supported.

### Payment captured, order confirmation failed

Recover order confirmation where possible or initiate a controlled refund.

### Shipping initiated, order cancelled

Apply fulfillment-dependent cancellation or return rules.

Do not assume all compensations are perfect reversals.

Refunds, shipment recalls, and external operations may have asynchronous or irreversible consequences.

### Compensation invariants

- Compensation is idempotent.
- Duplicate compensation commands are safe.
- Failed compensation remains retryable or manually recoverable.
- Compensation cannot override newer legitimate state.
- External financial operations are reconciled before unsafe retries.

---

## 8. SAGA CHOREOGRAPHY COMPARISON

Explain choreography through events such as:

- OrderCreated
- InventoryReserved
- PaymentAuthorized
- OrderConfirmed

Compare choreography against orchestration.

Evaluate:

- Workflow visibility
- Coupling
- Coordination
- Failure diagnosis
- Retry management
- Compensation ownership
- Operational complexity

Implement a limited demonstrator or integration test illustrating choreography.

Do not create two competing production checkout workflows that both mutate the same order.

---

# PART F — TCC PATTERN

## 9. TRY-CONFIRM/CANCEL DESIGN

Implement TCC for Inventory.

### TRY

Provisionally reserve stock.

`POST /api/v1/inventory/reservations/try`

### CONFIRM

Convert the reservation into committed allocation.

`POST /api/v1/inventory/reservations/{reservationId}/confirm`

### CANCEL

Release provisional inventory.

`POST /api/v1/inventory/reservations/{reservationId}/cancel`

### Mandatory properties

- Unique reservation ID
- Idempotent Try
- Idempotent Confirm
- Idempotent Cancel
- Reservation timeout
- Ownership validation
- State versioning
- Anti-overselling constraints
- Safe retry
- Concurrent confirmation/cancellation handling

---

## 10. TCC FAILURE SCENARIOS

Address:

### Empty rollback

Cancel arrives before Try has completed.

### Duplicate Confirm

The same Confirm command arrives multiple times.

### Duplicate Cancel

Cancellation is retried.

### Hanging reservation

Try succeeds but neither Confirm nor Cancel arrives.

### Race between Confirm and Cancel

Both operations execute concurrently.

### Delayed Try

An old Try arrives after cancellation or terminal workflow state.

Implement durable transaction identifiers, terminal decisions, tombstones where needed, and state transitions that
prevent cancelled transactions from being resurrected.

### Critical rule

**TCC requires participating services to support provisional resource ownership.**

Do not claim an arbitrary external payment provider supports TCC unless it exposes compatible authorization/capture/void
capabilities.

---

# PART G — INVENTORY CONSISTENCY

## 11. PREVENT OVERSELLING

Design an inventory model:

- SKU
- Warehouse
- On-hand stock
- Reserved stock
- Available stock
- Confirmed allocation

Example:

`Available = OnHand − Reserved − Other allocations not yet deducted from OnHand`

Use one internally consistent accounting convention.

### Concurrency example

One unit remains.

Two customers attempt purchase simultaneously.

Only one Try reservation should succeed.

Evaluate:

- Conditional SQL update
- Pessimistic locking
- Optimistic locking
- Database uniqueness/constraints
- Serialized inventory commands

Use PostgreSQL transactional enforcement for the baseline.

Do not rely solely on Redis locks.

---

# PART H — EVENT SOURCING

## 12. EVENT-SOURCED ORDER AGGREGATE

Implement Event Sourcing for the Order bounded context where it adds value.

Events may include:

- OrderCreated
- OrderItemsConfirmed
- InventoryReservationRequested
- InventoryReserved
- PaymentAuthorizationRequested
- PaymentAuthorized
- OrderConfirmed
- PaymentCaptured
- OrderCancellationRequested
- OrderCancelled

Define which events are genuine domain facts versus integration commands or technical workflow signals.

Do not treat every incoming Kafka event as an authoritative Order domain event.

### Event store requirements

- Aggregate ID
- Aggregate version
- Event sequence
- Event type
- Schema version
- Payload
- Timestamp
- Correlation ID
- Causation ID

Use PostgreSQL append-only event storage for the initial implementation.

Kafka is an event transport, not automatically the authoritative event store.

---

## 13. OPTIMISTIC CONCURRENCY IN EVENT SOURCING

Implement expected-version checks.

Example:

- Order aggregate is version 5.
- Two concurrent commands attempt to append version 6.
- Only one append succeeds.
- The other receives a version conflict and must re-evaluate the command against the new state.

Use database uniqueness:

`UNIQUE (aggregate_id, aggregate_version)`

Ensure append and related outbox persistence happen atomically.

Do not silently retry noncommutative commands without reconstructing the aggregate.

---

## 14. EVENT REPLAY

Implement:

- Aggregate rehydration
- Ordered event replay
- Snapshotting where justified
- Schema upcasting/version compatibility
- Projection rebuilding
- Historical inspection

### Important correctness concerns

- Event ordering
- Immutable history
- Duplicate append prevention
- Event schema evolution
- Snapshot correctness
- Replay side-effect suppression

**Replaying domain events must not reissue external payment captures, inventory reservations, or shipments.**

Separate pure aggregate reconstruction from integration-event processing.

---

# PART I — CQRS

## 15. COMMAND AND QUERY RESPONSIBILITIES

Implement CQRS for the Order domain.

### Command side

Handles:

- CreateOrder
- CancelOrder
- ConfirmOrder
- RecordPaymentAuthorized
- RecordPaymentCaptured

Validates domain invariants and persists events.

### Query side

Handles:

- GetOrder
- ListCustomerOrders
- GetOrderTimeline
- GetOrderStatus
- GetOrderSummary

Use a projection store optimized for queries.

CQRS does not require separate physical databases for every implementation.

Choose separate schemas or stores according to real scaling requirements.

---

## 16. EVENTUAL CONSISTENCY

Define:

- Command acknowledgment boundary
- Projection lag
- Read-your-writes behavior
- Stale query handling
- Projection version
- Rebuild status

### Example

An order command succeeds and returns Order ID.

The query projection has not yet processed the event.

Define the client-visible behavior.

Possible approaches:

- Return command-side state in the command response.
- Return 202 Accepted with operation status.
- Support version-aware polling.
- Wait for a bounded projection version when justified.

Do not promise immediate consistency from asynchronous projection updates.

---

## 17. PROJECTION PROCESSING

Implement:

- Idempotent event consumption
- Aggregate version tracking
- Out-of-order event handling
- Duplicate suppression
- Projection checkpoints
- Rebuild
- Dead-letter processing
- Projection monitoring

Prevent a delayed older event from overwriting newer projection state.

---

# PART J — TRANSACTIONAL OUTBOX

## 18. OUTBOX DESIGN

Implement Transactional Outbox in services that emit integration events.

Example Order transaction:

1. Append domain event to event store.
2. Update required local state.
3. Insert outbox event.
4. Commit transaction.

A relay publishes committed outbox records to Kafka.

### Required outbox fields

- Event ID
- Aggregate ID
- Aggregate type
- Aggregate version
- Event type
- Schema version
- Payload
- Created timestamp
- Publish status
- Attempt count

Use an appropriate event ID and uniqueness model.

---

## 19. OUTBOX PUBLISHING STRATEGIES

Compare:

1. Polling publisher
2. CDC using Debezium
3. Database logical replication

Select a working baseline.

Prefer a simple polling publisher initially, with a documented CDC migration path if scale justifies it.

Implement:

- Bounded batches
- Atomic claiming
- Retry with backoff
- Publish acknowledgment
- Duplicate delivery tolerance
- Worker crash recovery
- Backpressure
- Lag monitoring

Do not delete outbox records before safe publication acknowledgment.

### Critical scenario

Kafka accepts a message, but the publisher crashes before updating the database.

The event may be published again.

Consumers must remain idempotent.

---

# PART K — TRANSACTIONAL INBOX

## 20. IDEMPOTENT CONSUMER

Implement a durable inbox or processed-message store.

For each consumer:

1. Receive event.
2. Validate schema and message identity.
3. Begin local transaction.
4. Check whether event was already processed.
5. Apply domain state transition if not already applied.
6. Record processed event.
7. Commit.
8. Acknowledge Kafka offset after successful local commit.

Use a unique constraint such as:

`UNIQUE (consumer_name, event_id)`

Document transactional scope and failure behavior.

A duplicate event must not repeat a payment capture, inventory confirmation, refund, or fulfillment operation.

---

# PART L — EVENT ORDERING AND SCHEMA EVOLUTION

## 21. KAFKA EVENT DESIGN

Define event envelopes containing:

- Event ID
- Event type
- Aggregate ID
- Aggregate version
- Schema version
- Correlation ID
- Causation ID
- Timestamp
- Payload

Use aggregate ID as a candidate Kafka partition key where ordering matters.

Explain:

- Per-partition ordering
- Cross-partition ordering limitations
- Consumer group rebalancing
- Duplicate delivery
- Retry reordering

Use version-aware processing and state validation.

---

## 22. SCHEMA COMPATIBILITY

Evaluate:

- JSON Schema
- Avro
- Protobuf
- Schema Registry

Choose one verified compatible approach.

Support:

- Backward-compatible evolution
- Versioned event contracts
- Rolling deployments
- Upcasters where needed
- Contract testing
- Replay of older events

Do not change historical Event Sourcing payload meaning in place.

---

# PART M — PAYMENT SERVICE

## 23. PAYMENT ORCHESTRATION

Implement:

- Payment intent
- Payment authorization
- Capture
- Void/cancel
- Refund
- Payment state
- Provider references
- Idempotency
- Reconciliation

Use a deterministic local payment simulator by default.

A real payment provider may be integrated through a verified sandbox adapter.

### Financial invariants

- No unintended duplicate authorization/capture operation for the same logical request.
- Refund totals do not exceed eligible captured amounts.
- Provider timeouts do not automatically trigger unsafe retries.
- Captured payment state is auditable.
- Duplicate webhooks do not create duplicate financial effects.

---

## 24. PAYMENT TIMEOUT RECOVERY

Analyze:

1. Payment authorization request sent.
2. Provider processes it.
3. HTTP response is lost.
4. Payment Service does not know the outcome.
5. Saga awaits payment confirmation.

Design:

- Persistent provider operation ID
- Provider-supported idempotency
- Status lookup
- Reconciliation
- Bounded retry
- Manual review

Do not assume all payment APIs provide universal idempotency headers.

---

# PART N — FULFILLMENT SERVICE

## 25. FULFILLMENT DESIGN

Implement:

- Fulfillment initiation
- Seller/warehouse allocation
- Shipment creation
- Shipment status
- Cancellation where permitted
- Delivery updates

Use idempotent fulfillment requests.

Define the boundary between reversible preparation and irreversible shipment handoff.

Do not automatically compensate a physically shipped order as though it were a database update.

---

# PART O — DISTRIBUTED CONSISTENCY

## 26. DEFINE CONSISTENCY GUARANTEES

Explicitly document:

- Local ACID guarantees
- Distributed eventual consistency
- Saga completion guarantees
- TCC reservation guarantees
- Payment reconciliation guarantees
- CQRS projection freshness
- Event Sourcing version ordering
- Outbox delivery semantics

Distinguish:

- At-most-once delivery
- At-least-once delivery
- Exactly-once effects
- Idempotent commands
- Idempotent event consumption
- Linearizable local state transitions
- Eventual consistency between services

Do not claim a globally atomic transaction.

---

# PART P — SERVICE COMMUNICATION

## 27. SYNCHRONOUS COMMUNICATION

Use Spring's supported REST clients or gRPC where justified.

Implement:

- Authentication
- Authorization
- Timeouts
- Correlation IDs
- Versioned contracts
- Structured errors
- Retry classification
- Circuit breakers
- Bulkheads where justified

Avoid long synchronous service call chains.

---

## 28. ASYNCHRONOUS COMMUNICATION

Use Kafka for:

- Saga commands/responses
- Integration events
- Projection updates
- Fulfillment notifications

Distinguish commands from events.

A command requests an action.

An event records something that has happened.

Do not treat integration events as direct substitutes for authoritative domain state.

---

# PART Q — SECURITY

## 29. ENTERPRISE SECURITY REQUIREMENTS

Implement:

- OAuth2/OIDC authentication
- Spring Security Resource Servers
- JWT issuer/audience validation
- Service-to-service authentication
- Role-based and resource-level authorization
- Order ownership checks
- Customer isolation
- Administrative RBAC
- Request validation
- Rate limiting
- Secure secrets management
- TLS/mTLS where justified
- Audit logging
- Sensitive data minimization
- Secure payment-provider integration
- Webhook signature verification where applicable

Never expose unrestricted internal Saga administration APIs.

Do not assume an API Gateway is the sole authorization boundary.

---

# PART R — DATABASE DESIGN

## 30. DATABASE-PER-SERVICE

Use PostgreSQL with separate databases or independently owned schemas for the local runnable deployment.

Enforce ownership boundaries.

Services must not directly mutate another service's database.

Design schemas for:

### Order Service

- order_events
- order_snapshots
- order_commands
- order_idempotency
- order_outbox

### Inventory Service

- inventory_items
- inventory_reservations
- inventory_movements
- inventory_inbox
- inventory_outbox

### Payment Service

- payment_intents
- payment_attempts
- payment_operations
- refunds
- payment_inbox
- payment_outbox

### Saga Service

- saga_instances
- saga_steps
- saga_commands
- saga_timeouts
- saga_outbox
- saga_inbox

### Query Service

- order_read_models
- order_timeline
- projection_checkpoints

### Fulfillment Service

- fulfillment_requests
- shipments
- fulfillment_inbox
- fulfillment_outbox

Provide Flyway migrations, constraints, indexes, and transaction boundaries.

Do not allow cross-service foreign keys that couple independently owned databases.

---

# PART S — LOW-LEVEL DESIGN

## 31. DOMAIN MODEL

Define focused abstractions such as:

- Order
- OrderId
- OrderItem
- Money
- OrderEvent
- AggregateVersion
- SagaInstance
- SagaStep
- SagaCommand
- Compensation
- InventoryReservation
- TccTransaction
- PaymentIntent
- PaymentOperation
- FulfillmentRequest
- OutboxMessage
- InboxMessage
- ProjectionCheckpoint

Use Java 21 records, sealed interfaces, enums, and pattern matching where appropriate.

Provide:

- UML class diagrams
- Sequence diagrams
- State machines
- Repository contracts
- Application services
- Transaction boundaries
- Concurrency control
- Error handling

Avoid unnecessary inheritance or generic frameworks.

---

# PART T — REST API DESIGN

## 32. REQUIRED APIS

### Order Service

- `POST /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `GET /api/v1/orders`
- `POST /api/v1/orders/{orderId}/cancel`

### Inventory Service

- `GET /api/v1/inventory/{sku}`
- `POST /api/v1/inventory/reservations/try`
- `POST /api/v1/inventory/reservations/{id}/confirm`
- `POST /api/v1/inventory/reservations/{id}/cancel`

### Payment Service

- `POST /api/v1/payments/authorize`
- `POST /api/v1/payments/{id}/capture`
- `POST /api/v1/payments/{id}/void`
- `POST /api/v1/payments/{id}/refunds`
- `GET /api/v1/payments/{id}`

### Saga Administration

- `GET /api/v1/admin/sagas/{sagaId}`
- `GET /api/v1/admin/sagas/{sagaId}/steps`
- `POST /api/v1/admin/sagas/{sagaId}/retry`
- `POST /api/v1/admin/sagas/{sagaId}/compensate`

### Query Service

- `GET /api/v1/query/orders/{orderId}`
- `GET /api/v1/query/customers/me/orders`
- `GET /api/v1/query/orders/{orderId}/timeline`

Provide:

- Request/response JSON
- Authentication
- Authorization
- Idempotency headers
- Validation
- HTTP status codes
- Error contracts
- Pagination
- OpenAPI specifications

Use RFC 9457 Problem Details where applicable.

---

# PART U — TECHNOLOGY STACK

## 33. JAVA 21 + SPRING BOOT STACK

| Component         | Technology                                      |
|-------------------|-------------------------------------------------|
| Language          | Java 21                                         |
| Framework         | Spring Boot, verified compatible release        |
| Build             | Maven                                           |
| API Gateway       | Spring Cloud Gateway where justified            |
| REST              | Spring Web MVC / WebClient as appropriate       |
| Security          | Spring Security/OAuth2 Resource Server          |
| Databases         | PostgreSQL                                      |
| Migrations        | Flyway                                          |
| Messaging         | Apache Kafka                                    |
| Serialization     | JSON/Avro/Protobuf, selected with justification |
| Event Sourcing    | PostgreSQL append-only event store              |
| CQRS              | PostgreSQL read models                          |
| TCC               | Custom inventory reservation protocol           |
| Saga              | Durable orchestration service                   |
| Outbox            | PostgreSQL polling/CDC                          |
| Cache             | Redis where justified                           |
| Resilience        | Resilience4j                                    |
| Metrics           | Micrometer                                      |
| Tracing           | OpenTelemetry                                   |
| Unit tests        | JUnit 5/Mockito                                 |
| Integration tests | Testcontainers                                  |
| Contract tests    | Spring Cloud Contract/Pact where justified      |
| Performance       | k6/Gatling                                      |
| Containers        | Docker                                          |
| Orchestration     | Kubernetes                                      |
| Monitoring        | Prometheus/Grafana                              |

Verify exact dependency compatibility using official documentation.

Do not add service discovery infrastructure if Kubernetes DNS satisfies the requirement.

---

# PART V — PROJECT STRUCTURE

## 34. SOURCE CODE ORGANIZATION

Use a multi-module Maven repository.

```text id="lqc5bz"
distributed-order-management/
├── pom.xml
├── README.md
├── docs/
│   ├── architecture.md
│   ├── bounded-contexts.md
│   ├── saga-orchestration.md
│   ├── tcc-protocol.md
│   ├── event-sourcing.md
│   ├── cqrs.md
│   ├── transactional-outbox.md
│   ├── transactional-inbox.md
│   ├── event-contracts.md
│   ├── failure-recovery.md
│   ├── security.md
│   ├── capacity-planning.md
│   ├── runbook.md
│   └── decisions/
├── services/
│   ├── api-gateway/
│   ├── order-service/
│   ├── inventory-service/
│   ├── payment-service/
│   ├── fulfillment-service/
│   ├── saga-orchestrator/
│   ├── order-query-service/
│   ├── catalog-service/
│   └── notification-service/
├── libraries/
│   ├── event-contracts/
│   └── test-support/
├── integration-tests/
├── contract-tests/
├── benchmarks/
├── docker/
├── helm/
└── .github/workflows/
```

Avoid a large shared `common` library containing domain entities, repositories, or business logic from multiple
services.

Share only stable infrastructure contracts, event schemas, and test utilities when justified.

---

# PART W — RELIABILITY AND FAILURE SCENARIOS

## 35. ANALYZE THESE 40 SCENARIOS

1. Customer submits checkout twice.
2. Two instances create the same order concurrently.
3. Order event commits but Kafka is unavailable.
4. Outbox publisher crashes after Kafka accepts an event.
5. Kafka delivers OrderCreated twice.
6. Saga orchestrator crashes after dispatching a command.
7. Saga resumes after restart.
8. Inventory Try succeeds but response is lost.
9. Inventory Try is duplicated.
10. Inventory Confirm arrives twice.
11. Inventory Cancel arrives before Try.
12. Inventory Confirm and Cancel race.
13. Reservation expires during payment authorization.
14. Payment authorization succeeds but response times out.
15. Payment provider processes duplicate authorization requests.
16. Payment captured event arrives twice.
17. Payment is captured but Order Service is unavailable.
18. Inventory is confirmed but payment capture fails.
19. Compensation command is delivered twice.
20. Compensation fails repeatedly.
21. Saga gets stuck in COMPENSATING.
22. Event store receives concurrent appends.
23. Event store append commits but acknowledgment is lost.
24. Projection consumer processes a duplicate event.
25. Projection events arrive out of order.
26. CQRS read model is stale.
27. Projection rebuild runs while new events arrive.
28. Historical event schema is incompatible with new code.
29. Kafka consumer rebalance interrupts processing.
30. PostgreSQL primary fails.
31. Redis becomes unavailable.
32. Fulfillment is initiated twice.
33. Order is cancelled while payment capture is pending.
34. Refund response is ambiguous.
35. Inventory reservation expires while Saga is recovering.
36. A stale worker resumes after lease expiry.
37. Regional outage occurs during checkout.
38. Rolling deployment mixes event schema versions.
39. A poison event repeatedly crashes the consumer.
40. Business-order state and Saga state disagree.

For each provide:

- Root cause
- Transaction consistency risk
- Financial impact
- User-visible behavior
- Detection
- Prevention
- Recovery
- Automated test
- Residual risk

---

# PART X — AUTOMATED TESTING

## 36. UNIT TESTS

Cover:

- Order aggregate transitions
- Event Sourcing replay
- Expected-version conflicts
- Inventory reservation
- TCC Try/Confirm/Cancel
- Empty rollback
- Duplicate Confirm/Cancel
- Saga transitions
- Compensation logic
- Retry classification
- Payment state machine
- Refund validation
- Projection logic
- Idempotency

---

## 37. INTEGRATION TESTS

Use Testcontainers for:

- PostgreSQL
- Kafka
- Redis where implemented

Test actual service boundaries.

Verify:

- Database-per-Service separation
- Outbox publication
- Inbox deduplication
- Saga orchestration
- TCC inventory protocol
- Order Event Sourcing
- CQRS projections
- Payment simulation
- Compensation
- Recovery after restart

Do not rely solely on mocks to establish distributed transaction correctness.

---

## 38. CONCURRENCY TESTS

Verify:

- Concurrent order creation
- Same idempotency key across instances
- Last-unit inventory reservation
- Duplicate TCC commands
- Confirm/Cancel races
- Concurrent event appends
- Payment capture retries
- Refund limits
- Projection ordering
- Stale Saga worker fencing

Use real PostgreSQL concurrency tests where database guarantees are relevant.

---

## 39. CONTRACT TESTS

Verify REST and asynchronous event contracts.

Include:

- Required fields
- Schema versions
- Backward compatibility
- Invalid payloads
- Unsupported versions
- Error contracts
- Mixed-version deployments

Ensure consumers handle supported historical event versions.

---

## 40. FAILURE AND CHAOS TESTS

Simulate:

- Service crashes
- Database outages
- Kafka outage
- Lost acknowledgments
- Network latency
- Duplicate events
- Out-of-order events
- Consumer rebalance
- Payment-provider timeout
- Worker lease expiry
- Pod termination
- Saga recovery

Document actual execution and results.

---

# PART Y — PERFORMANCE ENGINEERING

## 41. CAPACITY PLANNING

Define targets requiring validation.

| Dimension             | Design Target                    |
|-----------------------|----------------------------------|
| Registered customers  | 100 million                      |
| Daily orders          | 10 million                       |
| Peak order creation   | 10,000/sec                       |
| Order query traffic   | 100,000 RPS                      |
| Event throughput      | Workload-derived                 |
| Saga instances        | Millions of persisted workflows  |
| Availability          | 99.99% target                    |
| Checkout consistency  | Eventual across services         |
| Inventory overselling | Zero under documented invariants |
| Deployment            | Multi-AZ Kubernetes              |

Calculate:

- Orders/sec
- Database writes/order
- Event-store growth
- Outbox events/order
- Inbox records
- Kafka partitions
- Projection update throughput
- Saga state storage
- Compensation traffic
- Connection pool sizes
- Recovery catch-up time

Use explicit formulas and assumptions.

---

## 42. LOAD TESTING

Create workloads for:

- 100 orders/sec
- 1,000 orders/sec
- 5,000 orders/sec
- 10,000 orders/sec as a scale target

Separately benchmark:

- Order command processing
- Inventory TCC
- Saga execution
- Payment simulation
- Outbox publishing
- Kafka consumption
- CQRS queries
- Event replay
- Projection rebuild
- Compensation under failure

Measure:

- Offered/achieved TPS
- p50/p95/p99 command latency
- Saga completion time
- TCC reservation latency
- Inventory contention
- Event append throughput
- Outbox lag
- Inbox duplicate rate
- Kafka lag
- Projection lag
- Compensation backlog
- Payment failures
- CPU/heap/GC
- Database connections
- Error rate

Never fabricate performance measurements.

---

# PART Z — DEPLOYMENT AND OBSERVABILITY

## 43. DOCKER AND KUBERNETES

Provide:

- Multi-stage Dockerfiles
- Docker Compose
- Separate service deployments
- PostgreSQL databases
- Kafka
- Redis where implemented
- Kubernetes Deployments
- ConfigMaps
- Secret references
- Resource requests/limits
- HPA
- PodDisruptionBudget
- Readiness/liveness probes
- Graceful shutdown
- Rolling deployments
- CI/CD workflow

The local environment must demonstrate a complete order checkout, compensation, and recovery flow without paid external
accounts.

---

## 44. METRICS AND ALERTING

Expose:

- Order creation throughput
- Saga started/completed/failed
- Saga execution duration
- Stuck Saga count
- Compensation attempts
- Compensation failures
- TCC Try/Confirm/Cancel counts
- Expired reservations
- Inventory conflicts
- Payment authorization failures
- Capture failures
- Outbox backlog
- Inbox deduplication
- Kafka consumer lag
- Projection lag
- Event replay duration
- Fulfillment failures

Provide:

- Prometheus configuration
- Grafana dashboards
- OpenTelemetry tracing
- Structured logs
- SLOs
- Alerts
- Operational runbooks

Avoid high-cardinality customer, order, and Saga IDs as metric labels.

Use trace IDs and secured logs for individual transaction investigation.

---

# PART AA — IMPLEMENTATION WORKFLOW

## 45. DEVELOPMENT PHASES

Execute in the following order.

### Phase 1 — Requirements and Architecture

Define bounded contexts, service ownership, consistency guarantees, financial invariants, and the primary checkout
workflow.

### Phase 2 — Microservice Foundation

Create Spring Boot services, database isolation, migrations, security, common infrastructure, and Docker Compose.

### Phase 3 — Order Event Sourcing

Implement the Order aggregate, append-only event store, expected-version concurrency, replay, and snapshots where
justified.

### Phase 4 — CQRS

Implement order read projections, Kafka consumption, idempotent updates, projection lag monitoring, and rebuilds.

### Phase 5 — Inventory TCC

Implement Try, Confirm, Cancel, reservation expiry, no-overselling constraints, and concurrency tests.

### Phase 6 — Payment Service

Implement payment intents, authorization, capture, refunds, idempotency, and provider simulation.

### Phase 7 — Transactional Outbox and Inbox

Implement durable integration events, outbox relay, idempotent consumers, and event contracts.

### Phase 8 — Saga Orchestrator

Implement durable checkout orchestration, step commands, compensation, timeout handling, and recovery.

### Phase 9 — Fulfillment and Notifications

Implement idempotent fulfillment initiation, shipment lifecycle, and asynchronous notifications.

### Phase 10 — Security and Resilience

Add OAuth2 resource-server security, service authentication, retries, circuit breakers, timeouts, audit logging, and
bounded backpressure.

### Phase 11 — Choreography Comparison

Implement a limited, isolated choreography example or executable demonstration without introducing a competing checkout
coordinator.

### Phase 12 — Deployment

Implement Kubernetes/Helm, CI/CD, monitoring, secrets management, and operational runbooks.

### Phase 13 — Verification

Run unit, integration, contract, concurrency, failure, and security tests.

### Phase 14 — Performance

Execute reproducible benchmarks, analyze bottlenecks, and optimize proven limitations.

### Phase 15 — Final Architecture Review

Review financial integrity, inventory correctness, distributed consistency, recoverability, service coupling, and
operational cost.

For every phase:

- Implement actual source files.
- Keep the project buildable.
- Run applicable verification.
- Fix discovered defects.
- Preserve unrelated code.
- Document significant architectural decisions.
- State actual test results.
- Identify remaining risks.

If repository and command-execution tools exist, create files and execute tests directly.

Otherwise, provide complete source files, file paths, and runnable commands without claiming execution.

---

# PART AB — REQUIRED DELIVERABLES

## 46. COMPLETE PROJECT OUTPUTS

### Deliverable 1 — Requirements

- Functional requirements
- Non-functional requirements
- Business invariants
- Consistency guarantees
- Acceptance criteria

### Deliverable 2 — HLD

- Microservice architecture
- Bounded contexts
- Communication flows
- Saga architecture
- TCC architecture
- CQRS/Event Sourcing
- Capacity planning

### Deliverable 3 — LLD

- Domain models
- UML class diagrams
- Sequence diagrams
- State machines
- Event store design
- Database schemas
- API contracts
- Transaction boundaries

### Deliverable 4 — Source Code

- API Gateway
- Order Service
- Inventory Service
- Payment Service
- Fulfillment Service
- Saga Orchestrator
- Query Service
- Catalog Service
- Notification Service where implemented
- Event Store
- Outbox/Inbox
- TCC
- CQRS projections

### Deliverable 5 — Automated Tests

- Unit
- Integration
- Contract
- Concurrency
- Security
- Failure recovery
- Chaos scenarios

### Deliverable 6 — Infrastructure

- Docker
- Docker Compose
- Kubernetes/Helm
- CI/CD
- Monitoring

### Deliverable 7 — Performance

- Benchmark scripts
- Actual measurements where executed
- Capacity models
- Bottleneck analysis

### Deliverable 8 — Documentation

- README
- Architecture Decision Records
- Saga protocol
- TCC specification
- Event Sourcing specification
- CQRS consistency guide
- Outbox/Inbox guarantees
- Security
- Deployment guide
- Recovery runbooks
- Known limitations

---

# PART AC — MANDATORY ARCHITECTURE REVIEW

## 47. ANSWER THESE 50 QUESTIONS

1. How do distributed transactions work in microservices?
2. Why is traditional ACID insufficient across independently owned services?
3. How does Saga orchestration work?
4. How does Saga choreography work?
5. When should orchestration be preferred?
6. What is compensation?
7. Why is compensation not always a rollback?
8. How do you recover a crashed Saga orchestrator?
9. How do you make Saga steps idempotent?
10. How do you prevent a Saga from getting stuck?
11. What is TCC?
12. How does Try differ from Confirm?
13. What happens when Cancel arrives before Try?
14. How do you handle duplicate Confirm/Cancel?
15. How do you handle TCC reservation expiration?
16. Can external payment gateways support TCC?
17. How do you prevent inventory overselling?
18. What is CQRS?
19. When is CQRS useful?
20. How do you handle stale CQRS projections?
21. How do you rebuild read models?
22. What is Event Sourcing?
23. How do you reconstruct an aggregate?
24. Why does an event store need optimistic concurrency?
25. How do you handle event schema evolution?
26. Why must event replay avoid external side effects?
27. What is the difference between domain events and integration events?
28. What is Transactional Outbox?
29. Why is writing to PostgreSQL and Kafka independently unsafe?
30. How does outbox polling compare with Debezium CDC?
31. Why can outbox events be delivered twice?
32. What is Transactional Inbox?
33. How do you design idempotent Kafka consumers?
34. Does Kafka exactly-once semantics guarantee exactly-once business operations?
35. How do you preserve ordering across Kafka partitions?
36. How do you handle out-of-order events?
37. How do you guarantee order creation idempotency?
38. How do you prevent duplicate payment capture?
39. What happens if payment succeeds but order confirmation fails?
40. How do you reconcile ambiguous provider outcomes?
41. How do you handle compensation failures?
42. How do you coordinate cancellation during Saga execution?
43. How do you implement database-per-service?
44. Why should services not share mutable database tables?
45. How do you secure service-to-service communication?
46. How do you apply retries and circuit breakers safely?
47. How do you test distributed transaction correctness?
48. How do you benchmark Saga completion latency?
49. What complexity do these patterns introduce?
50. Which patterns would you remove if the business requirements did not justify them?

For every answer explain:

- Architecture decisions
- Correctness guarantees
- Data consistency
- Concurrency
- Failure recovery
- Performance implications
- Operational trade-offs

---

# DEFINITION OF DONE

## 48. ACCEPTANCE CRITERIA

The implementation is complete only when:

- All required Java 21 Spring Boot services build successfully.
- Each microservice owns its persistence boundary.
- API authentication and authorization work.
- Order creation is idempotent.
- Order Event Sourcing works with version-checked appends.
- Event replay reconstructs aggregate state.
- CQRS read projections function.
- Projection rebuilds are reproducible.
- Inventory TCC Try/Confirm/Cancel works.
- Concurrent inventory reservations prevent overselling.
- Empty rollback, duplicate commands, and hanging reservations are handled.
- Saga orchestration works for successful checkout.
- Saga compensation works for failure paths.
- Saga recovery works after service crashes.
- Payment authorization and capture are idempotent under documented provider semantics.
- Ambiguous financial outcomes are reconciled.
- Transactional Outbox publishes committed events recoverably.
- Inbox deduplicates message effects.
- Duplicate Kafka events do not duplicate business effects.
- Out-of-order events do not corrupt authoritative state.
- Fulfillment initiation is idempotent.
- Contract tests cover critical service interfaces.
- Docker Compose supports a complete multi-service checkout demonstration.
- Kubernetes manifests are provided.
- Metrics and distributed tracing function.
- Load-test scripts are reproducible.
- Performance targets are measured or explicitly unverified.
- Known limitations and operational risks are documented.

Do not declare production readiness merely because the happy-path checkout works.

---

# FINAL EXECUTION INSTRUCTIONS

Build a **real, production-oriented Microservices-Based Order Management System using Java 21, Spring Boot, Kafka,
PostgreSQL, and enterprise distributed transaction patterns**.

Prioritize:

1. **Business correctness:** Valid order transitions, inventory integrity, and financial safety.
2. **Distributed transaction reliability:** Durable Saga workflows and recoverable compensation.
3. **TCC correctness:** Safe provisional resource reservation and terminal decisions.
4. **Event integrity:** Append-only event history, optimistic concurrency, and replay safety.
5. **Reliable messaging:** Transactional Outbox, Inbox, at-least-once delivery, and idempotent processing.
6. **CQRS consistency:** Correct projections, replay, and observable lag.
7. **Resilience:** Recovery from partial failures, timeouts, retries, and service crashes.
8. **Security:** Authenticated services, resource-level authorization, and protected financial operations.
9. **Scalability:** Independent service scaling and bounded resource use.
10. **Maintainability:** Pragmatic DDD boundaries and minimal unnecessary complexity.
11. **Observability:** Diagnosable Saga, TCC, events, payments, and projection failures.
12. **Reproducibility:** Complete source, integration tests, local infrastructure, deployment manifests, and benchmarks.

Begin with a concise architecture assessment.

Explicitly define the end-to-end checkout workflow and identify which services implement Saga, TCC, CQRS, Event
Sourcing, Transactional Outbox, and Transactional Inbox.

Then execute the implementation phases with working code and verification.

At completion report:

- Implemented features and microservices
- Architecture decisions and trade-offs
- Distributed transaction guarantees
- Source structure and important files
- Exact build and run commands
- Tests actually executed
- Performance measurements actually obtained
- Known limitations
- Remaining financial and production risks

**Do not stop after HLD or LLD. Deliver the complete end-to-end Microservices Order Management System with Saga
orchestration, inventory TCC, Order Event Sourcing, CQRS projections, Transactional Outbox/Inbox, payment recovery,
automated tests, and Kubernetes deployment.**