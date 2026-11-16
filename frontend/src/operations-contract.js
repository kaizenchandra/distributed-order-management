import {reservationItems} from "./reservation-items.js";

const uuid = {name: "id", label: "Resource ID", type: "uuid"};
const field = (name, label, type = "text") => ({name, label, type});
export const actions = [
    {
        id: "reservation-try",
        group: "Inventory",
        title: "Try inventory reservation",
        service: "inventory",
        scope: "oms.service",
        path: "/inventory/reservations/try",
        method: "POST",
        recoverable: true,
        fields: [
            field("reservationId", "Reservation ID", "uuid"),
            field("orderId", "Owning order ID", "uuid"),
            field("items", "Items: one SKU,quantity per line", "items"),
        ],
        body: ["reservationId", "orderId", "items"],
        warning:
            "Reserves inventory for up to 120 seconds. Use a dedicated reservation identity. Do not interfere with a reservation owned by a running checkout Saga.",
    },
    ...["confirm", "cancel"].map((phase) => ({
        id: "reservation-" + phase,
        group: "Inventory",
        title:
            phase === "confirm"
                ? "Confirm inventory reservation"
                : "Cancel inventory reservation",
        service: "inventory",
        scope: "oms.service",
        path: "/inventory/reservations/{id}/" + phase,
        method: "POST",
        recoverable: true,
        fields: [
            {...uuid, label: "Reservation ID"},
            field("orderId", "Owning order ID", "uuid"),
        ],
        body: ["orderId"],
        warning:
            phase === "confirm"
                ? "Moves a live TRIED reservation into allocated stock. The backend rejects expired reservations. Use the original owning order ID."
                : "Cancels an unconfirmed reservation using its original owner. Confirmed allocations cannot be cancelled through this endpoint; use the owning Saga compensation workflow.",
    })),

    {
        id: "stock",
        group: "Inventory",
        title: "Inspect stock",
        service: "inventory",
        scope: "oms.service",
        path: "/inventory/{id}",
        fields: [field("id", "Product SKU")],
    },
    {
        id: "payment",
        group: "Payments",
        title: "Inspect payment",
        service: "payments",
        scope: "oms.service",
        path: "/payments/{id}",
        fields: [uuid],
    },
    {
        id: "operation",
        group: "Payments",
        title: "Inspect operation",
        service: "payments",
        scope: "oms.service",
        path: "/payments/operations/{id}",
        fields: [uuid],
    },
    {
        id: "authorize",
        group: "Payments",
        title: "Authorize payment",
        service: "payments",
        scope: "oms.service",
        path: "/payments/authorize",
        method: "POST",
        idempotent: true,
        fields: [
            field("orderId", "Order ID", "uuid"),
            field("customerId", "Customer subject"),
            field("amount", "Amount in minor units", "amount"),
            field("currency", "Currency (e.g. USD)", "currency"),
        ],
        body: ["orderId", "customerId", "amount", "currency"],
        warning:
            "Queues a payment authorization in the provider simulator. Use the exact order identity and authorized amount.",
    },
    {
        id: "capture",
        group: "Payments",
        title: "Capture payment",
        service: "payments",
        scope: "oms.service",
        path: "/payments/{id}/capture",
        method: "POST",
        idempotent: true,
        fields: [uuid],
        warning:
            "Queues capture of this payment. This is a financial state change.",
    },
    {
        id: "void",
        group: "Payments",
        title: "Void authorization",
        service: "payments",
        scope: "oms.service",
        path: "/payments/{id}/void",
        method: "POST",
        idempotent: true,
        fields: [uuid],
        warning:
            "Queues reversal of the payment authorization. Inspect the payment before proceeding.",
    },
    {
        id: "refund",
        group: "Payments",
        title: "Request refund",
        service: "payments",
        scope: "oms.service",
        path: "/payments/{id}/refunds",
        method: "POST",
        idempotent: true,
        fields: [uuid, field("refundAmount", "Refund in minor units", "amount")],
        body: ["refundAmount"],
        warning:
            "Queues a refund against the eligible captured balance. The service validates the refundable amount.",
    },
    {
        id: "payment-history",
        group: "Payments",
        title: "Payment operation history",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/payments/{id}/operations",
        fields: [uuid],
    },
    {
        id: "payment-retry",
        group: "Recovery",
        title: "Retry payment lookup",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/payments/operations/{id}/retry",
        method: "POST",
        fields: [uuid],
        warning:
            "Resumes reconciliation of the same operation in manual review. It does not create a new payment identity.",
    },
    {
        id: "handoff",
        group: "Fulfillment",
        title: "Hand off shipment",
        service: "fulfillment",
        scope: "oms.service",
        path: "/fulfillments/{id}/handoff",
        method: "POST",
        fields: [uuid],
        warning:
            "Marks a PREPARING fulfillment as SHIPPED. Cancellation is no longer allowed after this irreversible handoff.",
    },
    {
        id: "saga",
        group: "Recovery",
        title: "Inspect Saga",
        service: "sagas",
        scope: "oms.admin",
        path: "/admin/sagas/{id}",
        fields: [uuid],
    },
    {
        id: "saga-steps",
        group: "Recovery",
        title: "Saga step history",
        service: "sagas",
        scope: "oms.admin",
        path: "/admin/sagas/{id}/steps",
        fields: [uuid],
    },
    {
        id: "saga-retry",
        group: "Recovery",
        title: "Retry Saga",
        service: "sagas",
        scope: "oms.admin",
        path: "/admin/sagas/{id}/retry",
        method: "POST",
        fields: [uuid],
        warning:
            "Resumes the outstanding command of a Saga in MANUAL_REVIEW. Inspect its current state first.",
    },
    {
        id: "saga-compensate",
        group: "Recovery",
        title: "Compensate Saga",
        service: "sagas",
        scope: "oms.admin",
        path: "/admin/sagas/{id}/compensate",
        method: "POST",
        fields: [uuid],
        warning:
            "Requests safe compensation. Ambiguous commands must be reconciled first; shipped fulfillment may require a return.",
    },
    {
        id: "events",
        group: "Audit",
        title: "Order event history",
        service: "orders",
        scope: "oms.admin",
        path: "/admin/orders/{id}/events",
        fields: [uuid],
    },
    {
        id: "rebuild",
        group: "Audit",
        title: "Rebuild order projections",
        service: "query",
        scope: "oms.admin",
        path: "/admin/projections/rebuild",
        method: "POST",
        fields: [],
        warning:
            "Rebuilds customer projections from buffered immutable events. This may temporarily block projection processing.",
    },
    {
        id: "legacy",
        group: "Migration",
        title: "Legacy payment candidates",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/legacy-payments",
        fields: [],
    },
    {
        id: "legacy-prepare",
        group: "Migration",
        title: "Prepare legacy balance",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/legacy-payments/{id}/prepare",
        method: "POST",
        fields: [uuid],
        fixedBody: {source: "oms-local-simulator-v1"},
        warning:
            "Freezes and prepares an audited opening balance from the original local simulator. No financial calls are replayed.",
    },
    {
        id: "legacy-inspect",
        group: "Migration",
        title: "Inspect migration",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/legacy-payments/migrations/{id}",
        fields: [uuid],
    },
    {
        id: "legacy-retry",
        group: "Migration",
        title: "Retry migration",
        service: "payments",
        scope: "oms.admin",
        path: "/admin/legacy-payments/migrations/{id}/retry",
        method: "POST",
        fields: [uuid],
        warning: "Retries manual review using the same frozen migration manifest.",
    },
];

export function buildRequest(action, values, key) {
    for (const f of action.fields) {
        const v = String(values[f.name] ?? "").trim();
        if (!v) throw new Error(`${f.label} is required.`);
        if (
            f.type === "uuid" &&
            !/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(v)
        )
            throw new Error(`${f.label} must be a UUID.`);
        if (
            f.type === "amount" &&
            (!/^\d+$/.test(v) || !Number.isSafeInteger(Number(v)) || Number(v) < 1)
        )
            throw new Error(
                `${f.label} must be a positive safe integer in minor units.`,
            );
        if (f.type === "items") reservationItems(v);
        if (f.type === "currency" && !/^[A-Z]{3}$/.test(v))
            throw new Error("Currency must be three uppercase letters.");
    }
    const body = {...action.fixedBody};
    for (const name of action.body || [])
        body[name] =
            action.fields.find((f) => f.name === name)?.type === "items"
                ? reservationItems(values[name])
                : action.fields.find((f) => f.name === name)?.type === "amount"
                    ? Number(values[name])
                    : String(values[name]).trim();
    return {
        url:
            `/ops-api/${action.service}/api/v1` +
            action.path.replace(
                "{id}",
                encodeURIComponent(String(values.id || "").trim()),
            ),
        method: action.method || "GET",
        headers: {
            "Content-Type": "application/json",
            ...(action.idempotent ? {"Idempotency-Key": key} : {}),
        },
        ...(action.method === "POST" ? {body: JSON.stringify(body)} : {}),
    };
}

export function isAllowed(service, path, method) {
    return actions.some(
        (a) =>
            a.service === service &&
            (a.method || "GET") === method &&
            new RegExp("^/api/v1" + a.path.replace("{id}", "[^/]+") + "$").test(path),
    );
}

export function tokenScopes(token) {
    try {
        return (
            JSON.parse(
                atob(token.split(".")[1].replaceAll("-", "+").replaceAll("_", "/")),
            ).scope?.split(" ") || []
        );
    } catch {
        return [];
    }
}
