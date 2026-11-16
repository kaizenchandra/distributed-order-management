# Orbit order workspace

Frontend only. Uses the existing customer API gateway; no backend changes required.

```sh
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Start the backend using the root README first. Sign in with an OMS customer account (local
demo: `customer` / `local-customer-only`). Access tokens are kept in memory and cleared on sign-out or reload; Access
tokens renew automatically using an in-memory refresh token. Sign in again after reload or session expiry. Sign-out
revokes the Keycloak refresh token.

Vite proxies `/api` to localhost:8099 and `/realms` to localhost:8180, avoiding browser CORS changes. Copy
`.env.example` to `.env.local` to override either upstream. Never place client secrets in frontend environment
variables.

```sh
npm run build
npm run preview
```

For production, serve `dist` over HTTPS and configure a same-origin reverse proxy for `/api` and `/realms`; Vite's
development proxy is not included in the build or preview. This local-development sign-in uses the existing `oms-demo`
password grant. A public deployment should use a public OIDC client with authorization code + PKCE.

## Features and behavior

- Responsive customer workspace with catalog, quantities, authoritative quotes and order review.
- Checkout retains the same idempotency key after failed attempts, even if the review is closed or the tab reloads. If a
  submission times out, retry within that review to avoid creating a duplicate. The cart and unfinished request are
  saved per account in sessionStorage, so the same checkout can be recovered after a reload and sign-in within the same
  tab. Tokens and passwords are never stored there.
- Order history reads the customer projection endpoint, including its 50-record offset pagination. Summary cards
  describe only the loaded page; USD totals exclude other currencies.
- Detail refresh uses the authoritative order endpoint, with the projected timeline displayed separately. Order lists
  can lag checkout and cancellation.
- Cancellation requests do not imply completed cancellation. Cancellation requires confirmation and disables further
  requests while the workflow responds. Cancelled orders disable the action.
- Keyboard focus indicators, labelled inputs, loading/empty/error states, and status announcements.
- Customer routes use the existing gateway. A separate Operations workspace uses allowlisted frontend development
  proxies and an authorized operator token; see [Operations](OPERATIONS.md).

## Validation

`node --check src/main.js` checks JavaScript syntax. `npm run build` validates the Vite production bundle. Live
integration requires the gateway, Keycloak, catalog, order, query and workflow services described in the root README.

## Local port conflicts and login verification

If another project already uses port 8180, set `OMS_KEYCLOAK_PORT=8181` in the root `.env` before starting the backend,
and `OMS_AUTH_TARGET=http://localhost:8181` in `frontend/.env.local`. The Compose issuer and Keycloak hostname use the
configured port together. Restart Vite after changing its environment.

From the repository root, run `python3 scripts/verify-frontend-login.py` to verify the customer token grant, catalog
access and order history through the frontend proxy without printing tokens. Run `cd frontend && npm test` for
authentication, session renewal, cart validation, checkout recovery, and DOM workflow tests.

## Customer capability coverage

| Requirement                   | Frontend behavior                                                                                                                     |
|-------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Customer authentication       | Keycloak sign-in, automatic token renewal, refresh-token revocation on logout; account-isolated cart restoration                      |
| Catalog and pricing           | Name/SKU search, quantity validation, catalog prices and authoritative quote review                                                   |
| Shopping cart                 | Dedicated cart page, edit/remove/clear, per-account same-tab recovery                                                                 |
| Idempotent checkout           | Saved request identity before submission; recover and retry after failure/reload; explicit discard warning                            |
| Order creation                | Immediate authoritative order details and asynchronous projection notice                                                              |
| Order history                 | 50-record offset pagination, page-local search/filter/sort and JSON download                                                          |
| Order lookup                  | Direct authoritative lookup by full order ID, including orders not yet projected                                                      |
| Status and audit              | Automatic status/timeline refresh every 10 seconds with pause control; projected timeline failure does not hide authoritative details |
| Order cancellation            | Confirmation, pending request feedback and eventual cancellation status                                                               |
| Reorder and summary           | Add available prior items to cart at current prices; downloadable order summary, explicitly not a payment receipt                     |
| Inventory/payment/fulfillment | Checkout progress based only on actual order state; server workflow performs reservation, authorization, capture and compensation     |

The current customer gateway does **not** expose stock levels, tracking numbers, shipping estimates, payment operations,
direct refund requests, or administrator recovery APIs. The Operations workspace adds privileged stock inspection,
payment/refund operations, handoff and administrator recovery using the existing service endpoints;
see [Operations](OPERATIONS.md). Customer access remains unchanged. The UI explains the unavailable details and does not
infer successful shipment or refund from order confirmation/cancellation. No backend service files were changed for
these additions.

Automatic updates pause in hidden tabs and while customer inputs, quotes, or cancellation confirmation are active. Cart
recovery lasts for the current browser tab; closing the tab clears it. The unfinished-checkout warning prevents silently
changing a request after a possibly committed submission.

## Operations workspace

The sidebar now includes an Operations console for inventory, payments/refunds, fulfillment handoff, Saga recovery,
order audit, projection rebuilds and legacy migrations. It requires an authorized operator access token with the
appropriate existing scopes. See [setup, permissions, workflows and deployment](OPERATIONS.md).

## Built frontend with API integration

```sh
npm run build
npm start
```

Open http://localhost:4173. Unlike `vite preview`, this standalone Node server serves `dist` and proxies customer API,
Keycloak and allowlisted Operations requests. It reads frontend `.env` and `.env.local`; process environment values take
precedence. `OMS_FRONTEND_PORT` changes the listening port. It binds to loopback; use an HTTPS reverse proxy when
deploying remotely. The frontend server requires Node 22 or newer and has no runtime package dependencies. No backend
service implementation is changed.

To verify this mode from the repository root:

```sh
OMS_FRONTEND_URL=http://127.0.0.1:4173 python3 scripts/verify-frontend-login.py
```

## Navigation, history and activity

- Bookmark `#catalog`, `#cart`, `#orders`, `#operations`, or `#activity`. `#orders/<order-uuid>` opens a specific owned
  order after sign-in. Browser Back/Forward follows workspace navigation. Bookmarks never contain credentials.
- **History source → Authoritative orders** reads owned identifiers and their current states directly from the order
  service through the existing gateway. This finds orders before projections catch up. Reads are batched and paced to
  stay below the gateway's normal request-rate limit. Search/filter/sort/export still apply to the current 50-record
  page.
- **Activity** lists status transitions observed while the current tab is open. Events are scoped per account,
  deduplicated, capped at 100, and stored in sessionStorage. Mark them read or clear them. This does not claim to be the
  backend notification audit or to deliver email/push notifications. Older projection versions do not regress known
  status.
- Token refresh responses from an earlier account cannot overwrite a newly signed-in account's session.

Tests that start the standalone server require permission to bind loopback sockets.

Catalog items now open a product-detail dialog with SKU, unit price and quantity control. The cart shows estimated
totals grouped by currency; authoritative pricing still comes from quote review. Operator payment requests support
same-tab recovery, bounded tracking, related-record links and result downloads.

See [requirement coverage](REQUIREMENTS.md) for the mapping to the original business requirements and the precise API
gaps that cannot be implemented by frontend changes alone.

## Reservation operations and projection consistency

Operations includes manual inventory Try/Confirm/Cancel with reservation ID generation, item validation,
ownership-preserving next-phase links, confirmation, and same-tab recovery. These are authorized operator actions, not
part of the customer cart flow.

Order details include **Check history consistency**, comparing the read projection against the current authoritative
version using the existing `minVersion` query. The UI distinguishes a current projection (200), lagging projection
(202), absent projection (404), and unavailable service. Authoritative order details remain visible in all cases.
