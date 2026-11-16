export function projectionSummary(status, data, version) {
    if (status === 200)
        return {
            kind: "current",
            message: `Order history has caught up to at least version ${version}.`,
        };
    if (status === 202)
        return {
            kind: "pending",
            message: `Order history is catching up: projected version ${data.version ?? "unknown"}, authoritative version ${version}.`,
        };
    if (status === 404)
        return {
            kind: "pending",
            message:
                "This order has not appeared in the read projection yet. Authoritative details are shown.",
        };
    return {
        kind: "unavailable",
        message:
            "Projection status is unavailable. Authoritative details are shown.",
    };
}
