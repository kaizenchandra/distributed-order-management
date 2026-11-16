const views = new Set(["orders", "catalog", "cart", "operations", "activity"]);

export function parseRoute(hash) {
    const [view, id] = hash.replace(/^#\/?/, "").split("/");
    if (!views.has(view)) return {view: "orders"};
    if (
        view === "orders" &&
        id &&
        /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(id)
    )
        return {view, orderId: id};
    return {view};
}

export function routeHash(view, id) {
    return (
        "#" +
        (views.has(view) ? view : "orders") +
        (id ? "/" + encodeURIComponent(id) : "")
    );
}
