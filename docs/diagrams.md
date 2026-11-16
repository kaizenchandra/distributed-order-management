# Implementation diagrams

Overall architecture is in architecture.md. These diagrams describe the implemented baseline; operator-provisioned
production components are marked.

## Bounded contexts

```mermaid
flowchart LR
 O[Order DB: events and checkout keys] --- S[Saga DB: workflow]
 I[Inventory DB: stock and reservations]
 P[Payment DB: ledger and operations]
 F[Fulfillment DB: requests and shipments]
 Q[Query DB: projections and event buffer]
 C[Catalog DB: products]
 N[Notification DB: audit sink]
```

## Checkout request

```mermaid
sequenceDiagram
 Customer->>Gateway: JWT + Idempotency-Key + items
 Gateway->>Order: POST orders
 Order->>Catalog: Authoritative quote
 Order->>Order: Commit events + key + outbox
 Order-->>Customer: 202 / ID / version 1
 Order-->>Kafka: Checkout.Start
```

## Order Saga

```mermaid
stateDiagram-v2
 [*] --> STARTED
 STARTED --> RUNNING
 RUNNING --> COMPLETED: six successful steps
 RUNNING --> COMPENSATING: definitive rejection or cancellation
 COMPENSATING --> COMPENSATED: reverse steps finish
 RUNNING --> MANUAL_REVIEW: timeout budget exhausted
 COMPENSATING --> MANUAL_REVIEW: reversal rejected
 MANUAL_REVIEW --> RUNNING: inspected retry
```

## Inventory TCC

```mermaid
stateDiagram-v2
 [*] --> NEW
 NEW --> TRIED: Try / stock condition
 NEW --> CANCELLED: empty rollback
 TRIED --> CONFIRMED: Confirm before expiry
 TRIED --> CANCELLED: Cancel
 TRIED --> EXPIRED: deadline
 CONFIRMED --> RELEASED: separate allocation compensation
```

## Payment authorization

```mermaid
sequenceDiagram
 Kafka->>Payment: Authorize / operation ID
 Payment->>PaymentDB: Lock intent + inspect operation
 Payment->>PaymentDB: Atomic simulator decision + result + reply outbox
 Payment-->>Kafka: Authorize.Ok or Rejected
 Note over Payment,PaymentDB: Real provider calls require a separate operation/reconciliation protocol
```

## Compensation

```mermaid
flowchart LR
 E[Known rejection / requested cancellation] --> F[Fulfillment.Cancel]
 F -->|pre-handoff| P[Payment.Reverse]
 F -->|already shipped| M[Manual review / return]
 P --> I[Inventory.Release]
 I --> O[Order.Cancel]
```

## Event Sourcing

```mermaid
flowchart LR
 C[Command] --> R[Replay ordered facts]
 R --> V[Validate transition]
 V --> A[Append expected version]
 A --> T[Same transaction: integration outbox]
 A --> U[Unique aggregate/version conflict]
```

## CQRS projection

```mermaid
flowchart LR
 K[Kafka order facts] --> D[Inbox]
 D --> B[Buffer aggregate versions]
 B --> C[Apply contiguous prefix]
 C --> R[Read model + timeline]
 H[Retained projection history] --> X[Admin rebuild / exclusive lock]
 X --> R
```

## Transactional Outbox

```mermaid
sequenceDiagram
 Service->>DB: Begin
 Service->>DB: State + outbox
 Service->>DB: Commit
 Relay->>DB: Claim bounded rows / SKIP LOCKED
 Relay->>Kafka: Publish
 Kafka-->>Relay: Acknowledge
 Relay->>DB: Mark published / commit
 Note over Relay,Kafka: Crash before marker can duplicate
```

## Transactional Inbox

```mermaid
sequenceDiagram
 Kafka->>Consumer: Envelope
 Consumer->>DB: Begin + unique inbox insert
 Consumer->>DB: Local mutation + response outbox
 Consumer->>DB: Commit
 Consumer-->>Kafka: Offset acknowledgment
 Note over Consumer,DB: Duplicate inbox identity skips mutation
```

## Refund processing

```mermaid
sequenceDiagram
 Saga->>Kafka: Payment.Reverse
 Kafka->>Payment: Stable compensation identity
 Payment->>PaymentDB: Lock intent
 Payment->>PaymentDB: Refund remaining eligible capture
 Payment->>PaymentDB: Operation result + reply outbox
 Payment-->>Saga: Via Kafka / completion
```

## Saga recovery

```mermaid
flowchart LR
 D[Persisted due deadline] --> L[Row lock / SKIP LOCKED]
 L --> B{Budget remaining?}
 B -->|yes| R[Reopen same outbox identity]
 B -->|no| M[Manual review]
 R --> C[Participant inbox and durable reply]
```

## Kubernetes deployment

```mermaid
flowchart TD
 Ingress[TLS ingress: supplied by operator] --> Gateway[Gateway Service / replicas]
 Gateway --> Order[Order Service / replicas]
 Gateway --> Query[Query Service / replicas]
 Order --> DB[(Owned database)]
 Order --> Kafka[Managed Kafka with ACL/TLS: operator provisioned]
 Kafka --> Saga[Saga Deployment]
 Saga --> SDB[(Saga owned DB)]
 HPA[HPA + PDB] --- Saga
```

## Domain classes

```mermaid
classDiagram
 class Order {
 long version
 ObjectNode state
 evolve(type, payload) Order
 }
 class Envelope {
 UUID eventId
 UUID aggregateId
 long aggregateVersion
 UUID causationId
 JsonNode payload
 }
 class Sagas {
 dispatch(id, cursor)
 recover()
 }
 class Inventory {
 apply(id, action, payload)
 expire()
 }
 Sagas ..> Envelope : commands
 Inventory ..> Envelope : replies
```

