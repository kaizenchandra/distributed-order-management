function actor(token) {
    try {
        const claims = JSON.parse(
            atob(token.split(".")[1].replaceAll("-", "+").replaceAll("_", "/")),
        );
        return claims.sub && claims.iss
            ? `orbit:operator-request:${encodeURIComponent(claims.iss)}:${encodeURIComponent(claims.sub)}`
            : null;
    } catch {
        return null;
    }
}

export function saveOperatorRequest(token, draft, storage = sessionStorage) {
    const key = actor(token);
    if (key) storage.setItem(key, JSON.stringify(draft));
}

export function restoreOperatorRequest(token, storage = sessionStorage) {
    try {
        const key = actor(token);
        return key ? JSON.parse(storage.getItem(key) || "null") : null;
    } catch {
        return null;
    }
}

export function finishOperatorRequest(token, storage = sessionStorage) {
    const key = actor(token);
    if (key) storage.removeItem(key);
}

export const settledOperation = (status) =>
    ["SUCCEEDED", "REJECTED", "MANUAL_REVIEW"].includes(status);

export function resultLinks(data, actionId) {
    const links = [];
    const add = (action, id, label) => {
        if (
            typeof id === "string" &&
            /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(id)
        )
            links.push({action, id, label});
    };
    for (const row of Array.isArray(data) ? data : [data]) {
        add("operation", row.operationId || row.operation_id, "Inspect operation");
        add("payment", row.payment_id || row.paymentId, "Inspect payment");
        add("events", row.order_id || row.orderId, "Order event audit");
        if (
            actionId === "saga" ||
            actionId === "saga-retry" ||
            actionId === "saga-compensate"
        ) {
            add("saga-steps", row.id, "Saga step history");
            add("events", row.order_id, "Order event audit");
        }
        if (
            actionId === "legacy-prepare" ||
            actionId === "legacy" ||
            actionId === "legacy-inspect"
        )
            add(
                "legacy-inspect",
                row.migrationId ||
                row.migration_id ||
                (actionId === "legacy-inspect" ? row.id : null),
                "Inspect migration",
            );
    }
    return links;
}
