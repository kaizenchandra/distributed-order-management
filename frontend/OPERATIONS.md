# Operations workspace

Sign in to the customer workspace and choose **Operations** in the sidebar. Paste an authorized OMS access token and
select **Use token**. Customer credentials remain `customer` / `local-customer-only`; that account cannot perform
privileged operations.

The current development realm has no interactive administrator account. Use an access token obtained through your
existing authorized internal-client workflow (`oms-internal` client credentials in a trusted terminal), or a separately
provisioned authorized operator identity. This frontend does not provision identities, change scopes, or request service
credentials. Never paste a client secret into the token field or store it in a `VITE_` setting.

The token stays in JavaScript memory, is cleared by **Clear session**, customer sign-out, a new customer sign-in, or
reload, and is never written to browser storage. Operator tokens do not refresh automatically; replace an expired token
using the same input. Decoded token scopes control UI availability only; each backend service validates the JWT,
audience and scopes independently.

## Available functions

| Area             | Functions                                                                                       | Required scope |
|------------------|-------------------------------------------------------------------------------------------------|----------------|
| Inventory        | Inspect stock and availability; Try/Confirm/Cancel reservation phases                           | `oms.service`  |
| Payments         | Inspect payment or provider operation; authorize, capture, void, partial/full-amount refund     | `oms.service`  |
| Payment audit    | List operations for a payment                                                                   | `oms.admin`    |
| Fulfillment      | Hand off a PREPARING fulfillment as SHIPPED                                                     | `oms.service`  |
| Recovery         | Inspect Saga and steps; retry manual-review Saga; compensate Saga; retry payment reconciliation | `oms.admin`    |
| Audit            | Inspect immutable order events; rebuild projections from buffered history                       | `oms.admin`    |
| Legacy migration | List candidates, prepare original simulator balance, inspect migration, retry review            | `oms.admin`    |

Resource IDs are UUIDs except inventory SKUs. Payment amounts use integer minor units: `2500` means USD 25.00.
Authorization requires the exact customer subject and order identity; this is not a card-entry flow. The server
validates financial eligibility and workflow transitions.

All mutations require a review and confirmation. A financial submission locks its fields and retains its idempotency key
until you explicitly finish it. If the request times out, inspect the current operation or retry the same request. Do
not start a new request identity merely because a response was lost. Accepted payment operations expose **Check
operation status**; queued does not mean succeeded.

Shipment handoff marks a fulfillment shipped and prevents normal cancellation. Projection rebuilding may block
processing temporarily. Review the warnings and relevant records before confirming either operation.

## Routing and deployment

The customer gateway remains unchanged. Vite serves an allowlisted local reverse proxy under
`/ops-api/{service}/api/v1/...` to the existing loopback service ports. The allowlist is generated from
`src/operations-contract.js` and restricts HTTP methods and paths; it does not provide authorization. No backend service
code or security rules were changed.

Defaults: orders 8091, inventory 8092, payments 8093, fulfillment 8094, Sagas 8095, query 8096. Override targets in
`.env.local` using `.env.example`. Only HTTP endpoints used by this console are allowed; provider-internal APIs,
actuator endpoints, arbitrary services and unsupported inventory operations are excluded.

The standalone `npm start` frontend server serves the built app with these same allowlisted proxy routes. For a
static-only deployment, configure equivalent authenticated same-origin reverse-proxy routes on your hosting server. Vite
development routes are not bundled into `dist` and are not provided by `vite preview`. Keep service ports private;
continue enforcing JWT and scope validation at each service. Do not expose internal-client secrets in a static site.

## Remaining API limits

There is no shipment-read/tracking endpoint, carrier integration UI, stock-adjustment API, notification-history API,
user-address API, or return-authorization API in the current services. These cannot be completed solely in a frontend.
The console displays actual handoff responses and service data; it does not invent delivery or refund outcomes.

## Verification

`npm test` covers route allowlisting, payload validation, permission gating, mutation confirmation and idempotent
retries alongside the customer workflows. Live read-only checks verified inventory and migration access with an
authorized internal token, 403 for customer stock access, 404 for a disallowed proxy route, and continued customer
catalog access. Financial mutations and shipment handoff were not executed as part of live verification.

## Recovery, tracking and related records

Financial request identity and form values are saved before submission in sessionStorage, scoped to the token's issuer
and subject. Tokens are never saved. After a reload, reconnect using an authorized token for the same operator to
restore the original request. **Resume saved request** returns to it after inspecting related records. **Finish / start
another request** explicitly clears the saved identity; inspect uncertain outcomes first. Closing the browser tab
removes this recovery state.

**Track until resolved** checks the operation every five seconds, up to 30 visible-tab checks. It stops on `SUCCEEDED`,
`REJECTED`, `MANUAL_REVIEW`, a request error, or an explicit stop. A hidden tab pauses checks; resume tracking if the
limit is reached. Clearing/replacing the token cancels tracking and discards late responses.

Responses offer validated related-record links and JSON downloads. Downloads contain only the displayed service
response, not access tokens. A restored request must be reviewed against current service state before resubmitting.

## Inventory reservation phases

Inventory now includes **Try inventory reservation**, **Confirm inventory reservation**, and **Cancel inventory
reservation**, using the existing TCC endpoints and `oms.service` scope. Generate a new reservation UUID for a dedicated
manual request, enter the owning order UUID, and provide one `SKU,quantity` per line. Quantities must be integers from 1
to 1000; duplicate SKUs and more than 50 items are rejected before submission.

A successful TRIED response offers next-phase links that carry the reservation and owner IDs forward without executing
another mutation. Each phase still requires review and confirmation. The backend validates ownership, expiry and legal
transitions; reservations expire after 120 seconds. Cancelling a confirmed allocation is not supported by the HTTP
cancellation endpoint. Use the owning Saga's compensation workflow rather than interfering with its reservation. Manual
reservations use their original resource identity for retries and are recoverable in the same operator sessionStorage
scope.
