const key = (user) => `orbit:activity:${encodeURIComponent(user)}`;

export function readActivity(user, storage = sessionStorage) {
    try {
        const value = JSON.parse(storage.getItem(key(user)) || "{}");
        return {
            known: value.known || {},
            events: Array.isArray(value.events) ? value.events : [],
        };
    } catch {
        return {known: {}, events: []};
    }
}

export function observeOrders(user, orders, storage = sessionStorage) {
    const data = readActivity(user, storage);
    for (const order of orders) {
        const saved = data.known[order.orderId];
        const previous = typeof saved === "string" ? saved : saved?.status;
        if (saved?.version && order.version && saved.version > order.version)
            continue;
        if (previous && previous !== order.status)
            data.events.unshift({
                id: crypto.randomUUID(),
                orderId: order.orderId,
                from: previous,
                status: order.status,
                at: new Date().toISOString(),
                read: false,
            });
        data.known[order.orderId] = {
            status: order.status,
            version: order.version || 0,
        };
    }
    data.events = data.events.slice(0, 100);
    try {
        storage.setItem(key(user), JSON.stringify(data));
    } catch {
    }
    return data.events;
}

export function updateActivity(user, mode, storage = sessionStorage) {
    const data = readActivity(user, storage);
    data.events =
        mode === "clear"
            ? []
            : data.events.map((event) => ({...event, read: true}));
    try {
        storage.setItem(key(user), JSON.stringify(data));
    } catch {
    }
    return data.events;
}
