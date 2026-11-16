# Frontend requirement coverage

This maps the business capabilities in the root `prompt.md` to the frontend and the actual service API. Frontend
implementation does not add backend endpoints or change service code.

| Business capability     | Implemented frontend flow                                                           | Existing API / boundary                                                   |
|-------------------------|-------------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| Customer authentication | Sign-in, token renewal, logout, isolated cart/activity state                        | Keycloak `oms` realm                                                      |
| Product catalog         | Search, product details, quantities                                                 | `GET /catalog/products`                                                   |
| Product pricing         | Per-currency cart estimates, authoritative quote review                             | `POST /catalog/quotes`                                                    |
| Inventory availability  | Authorized operator stock inspection; checkout availability handled by service      | Inventory `GET /inventory/{sku}`                                          |
| Shopping cart           | Edit/remove/clear, tab recovery, totals, quote                                      | Local cart and catalog API                                                |
| Order creation          | Review and submit, recover uncertain requests using original key                    | `POST /orders`                                                            |
| Inventory reservation   | Checkout automatically; authorized manual Try/Confirm/Cancel forms                  | Existing Inventory TCC endpoints; owning Saga remains authoritative       |
| Payment authorization   | Checkout automatic; explicit authorized operator action                             | Payment authorize API                                                     |
| Payment capture         | Checkout automatic; explicit authorized operator action                             | Payment capture API                                                       |
| Order confirmation      | Authoritative detail, projection list, bounded updates                              | Order and query APIs                                                      |
| Shipping/fulfillment    | Authorized handoff with explicit irreversible-state warning                         | Fulfillment handoff API; no shipment-read endpoint                        |
| Order cancellation      | Confirmation, pending state, status refresh                                         | Order cancellation API                                                    |
| Refunds                 | Authorized operator refund in integer minor units                                   | Payment refunds API                                                       |
| Order history           | Pagination, filter/search/sort, export, authoritative fallback                      | Order/query list APIs                                                     |
| Order projections       | Projection history and timeline, authoritative lookup, minVersion consistency check | Query list/timeline, order GET                                            |
| Distributed recovery    | Saga inspect/steps/retry/compensate, payment reconciliation retry                   | Existing administrator APIs                                               |
| Audit history           | Event audit, payment operation history, downloadable service results                | Order events and payment operations APIs                                  |
| Notifications           | Observed customer status activity with read/clear controls                          | Browser-tab observations; backend notification audit has no HTTP endpoint |
| Legacy migration        | Candidate list, prepare, inspect, retry                                             | Legacy payment admin API                                                  |
| Projection rebuild      | Authorized, confirmed rebuild operation                                             | Query admin rebuild API                                                   |

Operational payments support bounded tracking (30 checks, five seconds apart while visible) and links to related
payment/order/audit records. Financial request identity and inputs can be recovered within the same tab per operator
subject and issuer. Credentials are never persisted. A downloadable order summary is explicitly distinguished from a
payment receipt or tax invoice.

## Requirements that cannot be added solely in the frontend

There are no existing service endpoints for customer addresses, customer shipment/tracking reads, return authorization,
stock adjustments, notification history/preferences, or authoritative invoice/tax data. The UI does not collect
information the backend cannot accept or claim delivery/refund outcomes it cannot read. Implementing these capabilities
requires a separately authorized backend API change.

Operator access requires a real authorized token. The existing realm does not contain a human administrator login.
Creating such an identity, assigning scopes, or distributing internal-client credentials is not performed by this
frontend.

## Verification scope

Automated tests cover customer flows, operator confirmation/retries, financial request recovery, bounded operation
terminal states, account isolation, route validation, activity, and standalone server proxying. Live checks cover
customer authentication, catalog/history access and privileged read-only routes. No live financial mutation, shipment
handoff, projection rebuild or migration was executed by the implementation checks.

## Final endpoint audit and reliability corrections

All required HTTP endpoints listed in section 32 of the root `prompt.md` have corresponding customer or authorized Operations workflows. The audit also verified coverage of existing supplemental catalog, audit, recovery, migration, projection-rebuild, payment-operation and fulfillment-handoff endpoints. This is a frontend coverage statement, not production certification of the distributed backend.

Late API responses are discarded after navigation or account changes. Browser navigation requested during a loading operation is applied after that operation settles. An obsolete background 401 cannot clear the newer workspace. Token refresh checks account identity again after decoding the response body.

Recovered carts accept only valid integer quantities. An invalid or damaged saved checkout is blocked rather than silently receiving a new idempotency key; customers must review order history and explicitly discard it in the cart before starting another request. Tokens and passwords remain memory-only.
