import {createTaskGuard} from "./task-guard.js";
import {projectionSummary} from "./projection.js";
import {observeOrders, readActivity, updateActivity} from "./activity.js";
import {parseRoute, routeHash} from "./routing.js";
import {clearOperations, mountOperations} from "./operations.js";
import {cartItems, normalize, readWorkspace, receipt, saveWorkspace, STATUSES,} from "./domain.js";
import {accessToken, clearSession, endSession} from "./session.js";
import {signIn} from "./auth.js";
import "./style.css";

const root = document.querySelector("#app");
const tasks = createTaskGuard();
let pendingNavigation = false;
const state = {
    token: null,
    user: "",
    view: "orders",
    orders: [],
    products: [],
    product: null,
    cart: {},
    offset: 0,
    loading: false,
    error: "",
    notice: "",
    detail: null,
    timeline: [],
    filter: "ALL",
    search: "",
    key: null,
    quote: null,
    checkout: null,
    auto: true,
    sort: "default",
    catalogSearch: "",
    cancelConfirm: false,
    cancellation: {},
    timelineError: "",
    projection: null,
    updated: null,
    activity: [],
    historySource: "projection",
};
const esc = (v) =>
    String(v ?? "").replace(
        /[&<>"']/g,
        (c) =>
            ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"})[
                c
                ],
    );
const money = (n, c = "USD") =>
    new Intl.NumberFormat("en-US", {style: "currency", currency: c}).format(
        (n || 0) / 100,
    );

async function api(path, options = {}) {
    const valid = tasks.capture();
    const {inspectStatus, ...requestOptions} = options;
    let token;
    try {
        token = await accessToken();
    } catch (e) {
        if (valid()) state.token = null;
        throw e;
    }
    const response = await fetch("/api/v1" + path, {
        ...requestOptions,
        signal: AbortSignal.timeout(15000),
        headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${token}`,
            ...options.headers,
        },
    });
    const data = await response.json().catch(() => ({}));
    if (!valid()) throw new Error("Request superseded by newer navigation.");
    if (inspectStatus && [200, 202, 404].includes(response.status))
        return {status: response.status, data};
    if (!response.ok) {
        if (response.status === 401) {
            state.token = null;
            clearSession();
            throw new Error("Your session expired. Please sign in again.");
        }
        throw new Error(
            data.detail ||
            data.title ||
            (response.status === 429
                ? "Too many requests. Wait a moment before trying again."
                : `Request failed (${response.status}). Please try again.`),
        );
    }
    return data;
}

function persist() {
    try {
        saveWorkspace(state.user, state.cart, state.checkout);
    } catch {
        state.notice =
            "Browser storage is unavailable. Keep this tab open until checkout finishes.";
    }
}

function restore() {
    const saved = readWorkspace(state.user);
    state.cart = saved.cart;
    state.checkout = saved.checkout;
    if (state.checkout?.invalid) {
        state.quote = null;
        state.notice =
            "Saved checkout data is damaged. Check your order history, then explicitly discard the saved request from your cart before starting another.";
        return;
    }
    if (state.checkout) {
        state.quote = state.checkout.quote;
        state.key = state.checkout.key;
        state.notice =
            "An unfinished checkout was restored. Retry it to safely recover the same order.";
    }
}

async function run(fn) {
    if (state.loading) return;
    state.loading = true;
    state.error = "";
    render();
    try {
        await fn();
    } catch (e) {
        state.error =
            e.message === "Failed to fetch"
                ? "Cannot reach the service. Check that the backend is running."
                : e.message;
    } finally {
        state.loading = false;
        render();
        if (pendingNavigation) {
            pendingNavigation = false;
            queueMicrotask(navigateFromLocation);
        }
    }
}

async function loadOrders() {
    const valid = tasks.capture();
    const owner = state.user;
    const rows =
        state.historySource === "projection"
            ? await api(`/query/customers/me/orders?offset=${state.offset}`)
            : [];
    if (state.historySource === "authoritative") {
        const ids = await api(`/orders?offset=${state.offset}`);
        const orders = [];
        for (let i = 0; i < ids.length; i += 5) {
            if (!valid()) return;
            if (i) await new Promise((resolve) => setTimeout(resolve, 220));
            const batch = await Promise.all(
                ids
                    .slice(i, i + 5)
                    .map((row) => api(`/orders/${encodeURIComponent(row.aggregate_id)}`)),
            );
            orders.push(...batch);
        }
        if (!valid() || state.user !== owner) return;
        state.orders = orders;
    } else {
        if (!valid() || state.user !== owner) return;
        state.orders = rows.map(normalize);
    }
    state.activity = observeOrders(state.user, state.orders);
    state.updated = new Date();
}

async function loadProducts() {
    const valid = tasks.capture();
    const products = await api("/catalog/products");
    if (valid()) state.products = products;
}

const badge = (s) =>
    `<span class="badge ${["CONFIRMED", "SHIPPED", "COMPLETED"].includes(s) ? "success" : ["CANCELLED", "FAILED", "REJECTED"].includes(s) ? "muted" : "pending"}"><i></i>${esc((s || "PENDING").replaceAll("_", " "))}</span>`;

function render() {
    if (!state.token) {
        root.innerHTML = `<main class="login"><section class="welcome"><div class="brand">◈ <b>orbit</b><span>ORDER WORKSPACE</span></div><div><p class="eyebrow">EVERY ORDER. CONNECTED.</p><h1>A clearer view of<br>your commerce.</h1><p>From the first item to the final handoff.<br>Your orders, all in one place.</p></div><small>DISTRIBUTED ORDER MANAGEMENT</small></section><section class="login-form"><form id="login"><p class="eyebrow">WELCOME BACK</p><h2>Let’s get you connected.</h2><p class="sub">Sign in to your order workspace.</p>${state.error ? `<div class="error" role="alert">${esc(state.error)}</div>` : ""}<label>Username<input name="username" autocomplete="username" required placeholder="Your username"></label><label>Password<input type="password" name="password" autocomplete="current-password" required placeholder="Your password"></label><button class="primary" ${state.loading ? "disabled" : ""}>${state.loading ? "Connecting…" : "Sign in →"}</button><small>Local demo: customer / local-customer-only</small></form></section></main>`;
        document.querySelector("#login").onsubmit = (e) => {
            e.preventDefault();
            const f = new FormData(e.target);
            run(async () => {
                state.token = await signIn(f.get("username"), f.get("password"));
                state.user = f.get("username");
                clearOperations();
                tasks.invalidate();
                Object.assign(state, {
                    detail: null,
                    product: null,
                    quote: null,
                    checkout: null,
                    orders: [],
                    products: [],
                    cancellation: {},
                    view: "orders",
                    offset: 0,
                    filter: "ALL",
                    search: "",
                    notice: "",
                });
                restore();
                state.activity = readActivity(state.user).events;
                const route = parseRoute(window.location.hash);
                state.view = route.view;
                if (route.orderId) await openOrder(route.orderId);
                if (state.view === "orders") await loadOrders();
                else if (["catalog", "cart"].includes(state.view)) await loadProducts();
            });
        };
        return;
    }
    const selected = Object.values(state.cart).reduce((a, b) => a + b, 0);
    let filtered = state.orders.filter(
        (o) =>
            (state.filter === "ALL" || o.status === state.filter) &&
            o.orderId?.toLowerCase().includes(state.search.toLowerCase()),
    );
    if (state.sort === "amount")
        filtered.sort((a, b) => (b.amount || 0) - (a.amount || 0));
    if (state.sort === "status")
        filtered.sort((a, b) => (a.status || "").localeCompare(b.status || ""));
    root.innerHTML = `<div class="shell"><aside><div class="brand">◈ <b>orbit</b></div><p class="nav-label">WORKSPACE</p><nav><button data-view="orders" class="${state.view === "orders" ? "active" : ""}">▤ <span>Orders</span></button><button data-view="catalog" class="${state.view === "catalog" ? "active" : ""}">▦ <span>Product catalog</span></button></nav><button data-view="cart" class="${state.view === "cart" ? "active" : ""}">◫ <span>Shopping cart (${selected})</span></button><button data-view="operations" class="${state.view === "operations" ? "active" : ""}">⚙ <span>Operations</span></button><button data-view="activity" class="${state.view === "activity" ? "active" : ""}">♧ <span>Activity (${state.activity.filter((e) => !e.read).length})</span></button><div class="sidebar-bottom"><div class="system"><i></i>Authenticated session</div><small>Distributed order management<br>Customer workspace</small></div></aside><div class="workspace"><header><span>Workspace <span class="slash">/</span> <b>${state.view === "orders" ? "Orders" : state.view === "cart" ? "Shopping cart" : state.view === "activity" ? "Activity" : state.view === "operations" ? "Operations" : "Product catalog"}</b></span><div class="account"><span class="avatar">${esc(state.user[0]?.toUpperCase())}</span>${esc(state.user)}<button id="logout" class="text-button">Sign out</button></div></header><main class="content"><div class="page-title"><div><p class="eyebrow">YOUR COMMERCE, IN FOCUS</p><h1>${state.view === "orders" ? "Order overview" : state.view === "cart" ? "Your shopping cart" : state.view === "activity" ? "Activity" : state.view === "operations" ? "Operations" : "Product catalog"}</h1><p class="sub">${state.view === "orders" ? "Track progress, explore details, and keep things moving." : state.view === "cart" ? "Adjust your items, then review authoritative pricing." : state.view === "operations" ? "Inspect, reconcile, and recover using existing service APIs." : "Discover your next order. We’ll take care of the rest."}</p></div><button id="primary" ${["operations", "activity"].includes(state.view) ? "hidden" : ""} class="primary" ${state.loading ? "disabled" : ""}>${state.view === "orders" ? "+ Create order" : `Review order (${selected}) →`}</button></div>${state.error ? `<div role="alert" class="error">${esc(state.error)}</div>` : ""}${state.notice ? `<div role="status" class="notice">${esc(state.notice)}</div>` : ""}${
        state.view === "orders"
            ? `<section class="stats"><article><span>Orders on this page</span><strong>${state.orders.length}</strong><small>Up to 50 orders per page</small></article><article><span>In progress</span><strong>${state.orders.filter((o) => o.status === "PENDING").length}<em>↗</em></strong><small>Checkout in progress</small></article><article><span>Cancelled</span><strong>${state.orders.filter((o) => o.status === "CANCELLED").length}</strong><small>Cancelled orders on this page</small></article><article><span>Order value · USD</span><strong>${money(state.orders.filter((o) => o.currency === "USD").reduce((a, o) => a + (o.amount || 0), 0))}</strong><small>USD orders on this page</small></article></section><section class="panel"><div class="panel-title"><h2>Your orders <span class="count">${state.orders.length}</span></h2><button id="refresh" ${state.loading ? "disabled" : ""}>↻ Refresh</button></div><div class="live-tools"><label><input type="checkbox" id="auto" ${state.auto ? "checked" : ""}> Update every 10 seconds</label><small>${state.updated ? "Last updated " + state.updated.toLocaleTimeString() : ""}</small><button id="export">Download this page</button></div><form id="lookup" class="lookup"><label for="lookup-id">Find any of your orders</label><input id="lookup-id" required placeholder="Full order ID" pattern="[0-9a-fA-F-]{36}"><button>Find order →</button></form><div class="history-source"><label for="history-source">History source</label><select id="history-source"><option value="projection" ${state.historySource === "projection" ? "selected" : ""}>Projected history</option><option value="authoritative" ${state.historySource === "authoritative" ? "selected" : ""}>Authoritative orders</option></select><small>Use authoritative orders if a recent order has not appeared yet.</small></div><div class="toolbar"><input id="search" aria-label="Search order ID" placeholder="⌕  Search by order ID" value="${esc(state.search)}"><select id="filter" aria-label="Filter order status">${["ALL", ...new Set([...STATUSES, ...state.orders.map((o) => o.status).filter(Boolean)])].map((s) => `<option ${s === state.filter ? "selected" : ""}>${s}</option>`).join("")}</select><select id="sort" aria-label="Sort orders">${[
                ["default", "Default order"],
                ["amount", "Highest total"],
                ["status", "Status"],
            ]
                .map(
                    ([v, label]) =>
                        `<option value="${v}" ${state.sort === v ? "selected" : ""}>${label}</option>`,
                )
                .join(
                    "",
                )}</select></div><div class="table-wrap"><table><thead><tr><th>ORDER ID</th><th>STATUS</th><th>ITEMS</th><th>TOTAL</th><th>VERSION</th><th></th></tr></thead><tbody>${filtered.map((o) => `<tr><td><button class="order-link" data-order="${esc(o.orderId)}">${esc(o.orderId?.slice(0, 8))}<span class="id-tail">…${esc(o.orderId?.slice(-4))}</span></button></td><td>${badge(o.status)}</td><td>${(o.items || []).reduce((a, i) => a + i.quantity, 0)} items</td><td class="amount">${money(o.amount, o.currency)}</td><td>v${esc(o.version)}</td><td><button data-order="${esc(o.orderId)}" aria-label="View order ${esc(o.orderId)}">↗</button></td></tr>`).join("")}</tbody></table>${!filtered.length ? `<div class="empty"><div>▤</div><h3>${state.loading ? "Loading your orders…" : "A little space for what’s next."}</h3><p>${state.search || state.filter !== "ALL" ? "No orders match your filters." : "Your orders will appear here. Browse the catalog to get started."}</p></div>` : ""}</div><footer><small>Showing ${state.orders.length ? state.offset + 1 : 0}–${state.offset + state.orders.length} · Customer orders</small><div><button id="prev" ${state.offset === 0 || state.loading ? "disabled" : ""}>← Previous</button><button id="next" ${state.orders.length < 50 || state.loading ? "disabled" : ""}>Next →</button></div></footer></section><div class="info"><span>◎</span><div><b>Every step, in sync.</b><p>Order updates appear as checkout and fulfillment progress. Refresh to see the latest status.</p></div></div>`
            : state.view === "cart"
                ? cartView()
                : state.view === "operations"
                    ? '<div id="operations-root"></div>'
                    : state.view === "activity"
                        ? activityView()
                        : `<div class="catalog-toolbar"><input id="catalog-search" aria-label="Search products" placeholder="Search name or SKU" value="${esc(state.catalogSearch)}"><p class="sub">Stock is checked during checkout.</p></div><div class="catalog">${
                            state.products
                                .filter((p) =>
                                    (p.name + " " + p.sku)
                                        .toLowerCase()
                                        .includes(state.catalogSearch.toLowerCase()),
                                )
                                .map(
                                    (p, i) =>
                                        `<article class="product"><div class="product-art">${i % 2 ? "◉" : "▦"}<span>ORBIT ESSENTIALS</span></div><div class="product-body"><small>${esc(p.sku)}</small><h2><button class="product-name" data-product="${esc(p.sku)}">${esc(p.name)} ↗</button></h2><p class="sub">${money(p.price_minor, p.currency)}</p><label>Quantity<input type="number" min="0" max="1000" step="1" value="${state.cart[p.sku] || 0}" data-sku="${esc(p.sku)}"></label></div></article>`,
                                )
                                .join("") || '<div class="empty">No products available.</div>'
                        }</div>`
    }<div class="bottom-note">ORBIT WORKSPACE <span>Thoughtfully connected, from checkout to delivery.</span></div></main></div></div>${state.detail ? detailView() : ""}${state.quote ? quoteView() : ""}${state.product ? productView() : ""}`;
    bind();
}

function detailView() {
    const o = state.detail;
    return `<dialog open aria-modal="true" aria-labelledby="detail-title"><div class="dialog-top"><p class="eyebrow">ORDER DETAILS</p><button id="close" aria-label="Close details">✕</button></div><h2 id="detail-title">Your order, at a glance.</h2><p class="full-id">${esc(o.orderId)}</p>${badge(o.status)}<div class="line-items">${(o.items || []).map((i) => `<div><span>${esc(i.sku)} × ${i.quantity}</span><b>${money(i.unitPrice * i.quantity, o.currency)}</b></div>`).join("")}<div><b>Total</b><strong>${money(o.amount, o.currency)}</strong></div></div>${lifecycleView(o)}<section class="projection-status" role="status"><h3>Order history consistency</h3><p>${esc(state.projection?.message || "Projection status has not been checked yet.")}</p><button id="projection-refresh" ${state.loading ? "disabled" : ""}>Check history consistency</button></section><h3>Order timeline</h3>${state.timelineError ? `<p class="error">${esc(state.timelineError)}</p>` : ""}<ol class="timeline">${state.timeline.map((t) => `<li><b>${esc(t.type)}</b><small>${esc(t.occurred_at)} · v${esc(t.version)}</small></li>`).join("") || "<p>Timeline is catching up. Refresh shortly.</p>"}</ol><div class="dialog-actions"><button id="detail-refresh" ${state.loading ? "disabled" : ""}>Refresh status</button><button id="reorder">Order again</button><button id="receipt">Download summary</button><button id="cancel" class="danger" ${state.loading || state.cancellation[o.orderId] || ["CANCELLED", "FAILED", "SHIPPED", "COMPLETED"].includes(o.status) ? "disabled" : ""}>Request cancellation</button></div>${state.cancelConfirm ? `<div class="cancel-confirm"><p>Request cancellation for this order? Reversal depends on the fulfillment handoff.</p><button id="confirm-cancel" class="danger">Confirm cancellation request</button><button id="keep-order">Keep order</button></div>` : ""}${state.cancellation[o.orderId] ? '<div role="status" class="notice">Cancellation requested. Waiting for the order to update.</div>' : ""}<small>Cancellation is processed asynchronously and may be declined after handoff.</small>${state.error ? `<div class="error" role="alert">${esc(state.error)}</div>` : ""}</dialog>`;
}

function quoteView() {
    return `<dialog open aria-modal="true" aria-labelledby="quote-title"><div class="dialog-top"><p class="eyebrow">REVIEW YOUR ORDER</p><button id="close-quote" aria-label="Close review" ${state.loading ? "disabled" : ""}>✕</button></div><h2 id="quote-title">Ready when you are.</h2><p class="sub">Prices confirmed by the catalog. Stock and payment are checked after submission.</p>${state.checkout ? '<div class="notice">This checkout may have been submitted. Retry safely with the same request, or check your order history before starting another.</div>' : ""}<div class="line-items">${state.quote.items.map((i) => `<div><span>${esc(i.sku)} × ${i.quantity}</span><b>${money(i.unitPrice * i.quantity, state.quote.currency)}</b></div>`).join("")}<div><b>Total</b><strong>${money(state.quote.amount, state.quote.currency)}</strong></div></div><button id="place" class="primary" ${state.loading ? "disabled" : ""}>${state.loading ? "Placing order…" : "Place order →"}</button>${state.error ? `<div class="error" role="alert">${esc(state.error)}</div>` : ""}</dialog>`;
}

async function openOrder(id) {
    const valid = tasks.capture();
    state.quote = null;
    const order = await api(`/orders/${encodeURIComponent(id)}`);
    if (!valid()) return;
    setRoute("orders", id);
    state.detail = order;
    state.activity = observeOrders(state.user, [order]);
    state.cancelConfirm = false;
    state.projection = null;
    await checkProjection(order);
    state.timelineError = "";
    try {
        const timeline = await api(
            `/query/orders/${encodeURIComponent(id)}/timeline`,
        );
        if (valid() && state.detail?.orderId === id) state.timeline = timeline;
    } catch (e) {
        if (!valid()) return;
        state.timeline = [];
        state.timelineError =
            "Timeline unavailable. Authoritative order details are shown above.";
    }
    if (order.status === "CANCELLED") delete state.cancellation[id];
}

function bind() {
    if (state.loading)
        document
            .querySelectorAll("button,input,select")
            .forEach((b) => (b.disabled = true));
    document.querySelectorAll("[data-view]").forEach(
        (b) =>
            (b.onclick = () =>
                run(async () => {
                    tasks.invalidate();
                    state.product = null;
                    state.view = b.dataset.view;
                    setRoute(state.view);
                    state.notice = "";
                    if (state.view === "orders") await loadOrders();
                    else if (["catalog", "cart"].includes(state.view))
                        await loadProducts();
                })),
    );
    document.querySelector("#logout").onclick = () =>
        run(async () => {
            tasks.invalidate();
            try {
                await endSession();
            } finally {
                clearOperations();
                tasks.invalidate();
                Object.assign(state, {
                    token: null,
                    orders: [],
                    cart: {},
                    detail: null,
                    product: null,
                    quote: null,
                    checkout: null,
                    cancellation: {},
                    notice: "",
                    error: "",
                    offset: 0,
                    filter: "ALL",
                    search: "",
                });
            }
        });
    document.querySelector("#primary").onclick = () =>
        run(async () => {
            if (state.view === "orders") {
                state.view = "catalog";
                setRoute("catalog");
                await loadProducts();
                return;
            }
            if (state.checkout?.invalid) {
                state.view = "cart";
                setRoute("cart");
                throw new Error(
                    "Saved checkout data is damaged. Check order history before discarding it in your cart.",
                );
            }
            if (state.checkout) {
                state.quote = state.checkout.quote;
                state.key = state.checkout.key;
                return;
            }
            const items = cartItems(state.cart);
            state.quote = await api("/catalog/quotes", {
                method: "POST",
                body: JSON.stringify({items}),
            });
            state.key = crypto.randomUUID();
        });
    document
        .querySelector("#refresh")
        ?.addEventListener("click", () => run(loadOrders));
    document.querySelector("#search")?.addEventListener("input", (e) => {
        const pos = e.target.selectionStart;
        state.search = e.target.value;
        render();
        const el = document.querySelector("#search");
        el.focus();
        el.setSelectionRange(pos, pos);
    });
    document.querySelector("#filter")?.addEventListener("change", (e) => {
        state.filter = e.target.value;
        render();
    });
    for (const [id, delta] of [
        ["prev", -50],
        ["next", 50],
    ])
        document.querySelector("#" + id)?.addEventListener("click", () =>
            run(async () => {
                const previous = state.offset;
                state.offset += delta;
                try {
                    await loadOrders();
                } catch (e) {
                    state.offset = previous;
                    throw e;
                }
            }),
        );
    document
        .querySelectorAll("[data-order]")
        .forEach((b) => (b.onclick = () => run(() => openOrder(b.dataset.order))));
    document.querySelectorAll("[data-sku]").forEach(
        (el) =>
            (el.onchange = () => {
                if (!el.validity.valid) {
                    el.reportValidity();
                    return;
                }
                if (state.checkout) {
                    state.error =
                        "Resolve the unfinished checkout before editing the cart.";
                    render();
                    return;
                }
                state.cart[el.dataset.sku] = Number(el.value);
                persist();
                render();
            }),
    );
    document.querySelector("#close")?.addEventListener("click", () => {
        state.detail = null;
        setRoute(state.view);
        render();
    });
    document.querySelector("#close-quote")?.addEventListener("click", () => {
        state.quote = null;
        render();
    });
    document
        .querySelector("#detail-refresh")
        ?.addEventListener("click", () =>
            run(() => openOrder(state.detail.orderId)),
        );
    document.querySelector("#cancel")?.addEventListener("click", () => {
        state.cancelConfirm = true;
        render();
    });
    document.querySelector("#keep-order")?.addEventListener("click", () => {
        state.cancelConfirm = false;
        render();
    });
    document.querySelector("#confirm-cancel")?.addEventListener("click", () =>
        run(async () => {
            await api(`/orders/${encodeURIComponent(state.detail.orderId)}/cancel`, {
                method: "POST",
                body: "{}",
            });
            state.cancellation[state.detail.orderId] = true;
            state.cancelConfirm = false;
            state.notice = "Cancellation requested. Waiting for workflow processing.";
        }),
    );
    document.querySelector("#place")?.addEventListener("click", () =>
        run(async () => {
            state.checkout = {key: state.key, quote: state.quote};
            persist();
            const result = await api("/orders", {
                method: "POST",
                headers: {"Idempotency-Key": state.key},
                body: JSON.stringify({
                    items: state.quote.items.map(({sku, quantity}) => ({
                        sku,
                        quantity,
                    })),
                }),
            });
            state.quote = null;
            state.cart = {};
            state.checkout = null;
            persist();
            state.detail = result;
            state.timeline = [];
            state.view = "orders";
            setRoute("orders", result.orderId);
            state.offset = 0;
            state.notice = `Order ${result.orderId} accepted. Checkout is in progress; the order list may take a moment to update.`;
            await loadOrders();
        }),
    );
    bindExtras();
    if (state.view === "operations")
        mountOperations(document.querySelector("#operations-root"));
    const dialog = document.querySelector("dialog");
    if (dialog) {
        document.querySelector(".shell").inert = true;
        dialog.onkeydown = (e) => {
            if (e.key === "Tab") {
                const buttons = [
                    ...dialog.querySelectorAll(
                        "button:not(:disabled),input:not(:disabled),select:not(:disabled)",
                    ),
                ];
                if (!buttons.length) {
                    e.preventDefault();
                    return;
                }
                const first = buttons[0],
                    last = buttons.at(-1);
                if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    last.focus();
                } else if (!e.shiftKey && document.activeElement === last) {
                    e.preventDefault();
                    first.focus();
                }
            }
            if (e.key === "Escape" && !state.loading) {
                state.detail = null;
                state.quote = null;
                state.product = null;
                setRoute(state.view);
                render();
            }
        };
        if (!polling) dialog.querySelector("button:not(:disabled)")?.focus();
    }
}

render();

function cartView() {
    const entries = Object.entries(state.cart).filter(([, q]) => q > 0);
    const totals = {};
    for (const [sku, quantity] of entries) {
        const product = state.products.find((p) => p.sku === sku);
        if (product)
            totals[product.currency] =
                (totals[product.currency] || 0) + product.price_minor * quantity;
    }

    return `<section class="panel cart-panel"><div class="panel-title"><h2>Items in your cart</h2><button id="clear-cart" ${state.checkout || !entries.length ? "disabled" : ""}>Clear cart</button></div>${state.checkout ? '<div class="notice">An unfinished checkout is saved. Use Review order to retry the same request. Check your orders before discarding it.</div><button id="discard-checkout">Discard unfinished checkout…</button>' : ""}${state.discardConfirm ? '<div class="error"><p>Discarding removes duplicate protection for this checkout. Check your order history first if a submission timed out.</p><button id="discard-confirm">Discard checkout</button><button id="discard-keep">Keep saved checkout</button></div>' : ""}${
        entries
            .map(([sku, q]) => {
                const p = state.products.find((p) => p.sku === sku);
                return `<div class="cart-row"><div><b>${esc(p?.name || sku)}</b><small>${esc(sku)} · ${p ? money(p.price_minor, p.currency) + " each" : "Unavailable in the current catalog"}</small></div><label>Quantity <input aria-label="Quantity for ${esc(p?.name || sku)}" type="number" min="0" max="1000" step="1" data-sku="${esc(sku)}" value="${q}" ${state.checkout ? "disabled" : ""}></label><strong>${p ? money(p.price_minor * q, p.currency) : "—"}</strong><button data-remove="${esc(sku)}" ${state.checkout ? "disabled" : ""}>Remove</button></div>`;
            })
            .join("") ||
        '<div class="empty"><h3>Your cart is ready for something good.</h3><p>Browse the product catalog to add items.</p><button data-view="catalog">Browse products →</button></div>'
    }<div class="cart-foot"><div class="cart-estimates">${Object.entries(totals)
        .map(
            ([currency, total]) =>
                `<div><span>Estimated total · ${esc(currency)}</span><strong>${money(total, currency)}</strong></div>`,
        )
        .join(
            "",
        )}</div><p class="sub">Listed prices are estimates. Review your order for a server-calculated quote.</p><p class="sub">Inventory is reserved and payment processed by the checkout workflow. No card information is collected here.</p></div></section>`;
}

function lifecycleView(o) {
    const cancelled = o.status === "CANCELLED";
    return `<section class="lifecycle"><h3>Checkout progress</h3><div class="steps"><span class="done">✓ Order accepted</span><span class="${o.status === "CONFIRMED" ? "done" : ""}">${o.status === "CONFIRMED" ? "✓" : "○"} ${cancelled ? "Checkout reversed" : "Inventory & payment"}</span><span class="${o.status !== "PENDING" ? "done" : ""}">${o.status === "CONFIRMED" ? "✓ Confirmed" : cancelled ? "✓ Cancelled" : "○ Confirmation pending"}</span></div><p class="sub">${cancelled ? "The order is cancelled. Detailed refund and reversal records are not available through the customer API." : o.status === "CONFIRMED" ? "Checkout is confirmed. Shipment tracking and delivery estimates are not available through the customer API." : "Checkout is processing. Inventory reservation and payment are managed automatically."}</p></section>`;
}

function download(name, text, type = "text/plain") {
    const url = URL.createObjectURL(new Blob([text], {type}));
    const a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function checkProjection(order) {
    const valid = tasks.capture();
    try {
        const response = await api(
            `/query/orders/${encodeURIComponent(order.orderId)}?minVersion=${order.version || 1}`,
            {inspectStatus: true},
        );
        if (valid() && state.detail?.orderId === order.orderId)
            state.projection = projectionSummary(
                response.status,
                response.data,
                order.version || 1,
            );
    } catch {
        if (!valid()) return;
        state.projection = projectionSummary(503, {}, order.version || 1);
    }
}

function bindExtras() {
    document
        .querySelector("#projection-refresh")
        ?.addEventListener("click", () => run(() => checkProjection(state.detail)));

    document.querySelectorAll("[data-product]").forEach(
        (button) =>
            (button.onclick = () => {
                state.product = state.products.find(
                    (p) => p.sku === button.dataset.product,
                );
                render();
            }),
    );
    document.querySelector("#close-product")?.addEventListener("click", () => {
        state.product = null;
        render();
    });

    document.querySelector("#history-source")?.addEventListener("change", (e) =>
        run(async () => {
            const previous = state.historySource;
            const offset = state.offset;
            state.historySource = e.target.value;
            state.offset = 0;
            try {
                await loadOrders();
            } catch (error) {
                state.historySource = previous;
                state.offset = offset;
                throw error;
            }
        }),
    );
    document.querySelector("#activity-read")?.addEventListener("click", () => {
        state.activity = updateActivity(state.user, "read");
        render();
    });
    document.querySelector("#activity-clear")?.addEventListener("click", () => {
        state.activity = updateActivity(state.user, "clear");
        render();
    });
    document.querySelector("#auto")?.addEventListener("change", (e) => {
        state.auto = e.target.checked;
        render();
    });
    document.querySelector("#sort")?.addEventListener("change", (e) => {
        state.sort = e.target.value;
        render();
    });
    document.querySelector("#catalog-search")?.addEventListener("input", (e) => {
        const pos = e.target.selectionStart;
        state.catalogSearch = e.target.value;
        render();
        const input = document.querySelector("#catalog-search");
        input.focus();
        input.setSelectionRange(pos, pos);
    });
    document.querySelector("#lookup")?.addEventListener("submit", (e) => {
        e.preventDefault();
        const id = document.querySelector("#lookup-id").value.trim();
        run(() => openOrder(id));
    });
    document.querySelector("#clear-cart")?.addEventListener("click", () => {
        state.cart = {};
        persist();
        render();
    });
    document.querySelectorAll("[data-remove]").forEach(
        (b) =>
            (b.onclick = () => {
                if (state.checkout) return;
                delete state.cart[b.dataset.remove];
                persist();
                render();
            }),
    );
    document.querySelector("#discard-checkout")?.addEventListener("click", () => {
        state.discardConfirm = true;
        render();
    });
    document.querySelector("#discard-keep")?.addEventListener("click", () => {
        state.discardConfirm = false;
        render();
    });
    document.querySelector("#discard-confirm")?.addEventListener("click", () => {
        state.checkout = null;
        state.key = null;
        state.quote = null;
        state.discardConfirm = false;
        persist();
        render();
    });
    document.querySelector("#reorder")?.addEventListener("click", () =>
        run(async () => {
            if (state.checkout)
                throw new Error(
                    "Resolve the unfinished checkout in your cart before starting another order.",
                );
            await loadProducts();
            const items = state.detail.items || [];
            const missing = items.filter(
                (i) => !state.products.some((p) => p.sku === i.sku),
            );
            for (const i of items.filter((i) =>
                state.products.some((p) => p.sku === i.sku),
            ))
                state.cart[i.sku] = Math.min(
                    1000,
                    (state.cart[i.sku] || 0) + i.quantity,
                );
            state.detail = null;
            state.view = "cart";
            setRoute("cart");
            state.notice = missing.length
                ? "Available items added. Some items are no longer in the catalog."
                : "Items added to your cart. Review current prices before placing a new order.";
            persist();
        }),
    );
    document
        .querySelector("#receipt")
        ?.addEventListener("click", () =>
            download(
                `orbit-order-${state.detail.orderId}.txt`,
                receipt(state.detail),
            ),
        );
    document
        .querySelector("#export")
        ?.addEventListener("click", () =>
            download(
                `orbit-orders-page-${state.offset / 50 + 1}.json`,
                JSON.stringify(state.orders, null, 2),
                "application/json",
            ),
        );
}

let polling = false;
setInterval(async () => {
    if (
        !state.token ||
        !state.auto ||
        state.loading ||
        polling ||
        document.hidden ||
        state.quote
    )
        return;
    if (state.view !== "orders" && !state.detail) return;
    // Do not replace forms or dialogs while a customer is using their controls.
    if (document.activeElement?.matches("input,select") || state.cancelConfirm)
        return;
    polling = true;
    const valid = tasks.capture();
    try {
        if (state.detail) {
            const id = state.detail.orderId;
            const order = await api(`/orders/${encodeURIComponent(id)}`);
            if (valid() && state.detail?.orderId === id) {
                state.detail = order;
                await checkProjection(order);
                state.activity = observeOrders(state.user, [order]);
                try {
                    state.timeline = await api(
                        `/query/orders/${encodeURIComponent(id)}/timeline`,
                    );
                    state.timelineError = "";
                } catch {
                    state.timelineError =
                        "Timeline unavailable. Order status is current.";
                }
                if (order.status === "CANCELLED") delete state.cancellation[id];
            }
        } else await loadOrders();
        if (valid()) render();
    } catch (e) {
        if (valid()) {
            state.error = e.message;
            render();
        }
    } finally {
        polling = false;
    }
}, 10000);

function setRoute(view, id) {
    const hash = routeHash(view, id);
    if (window.location.hash !== hash) window.history.pushState(null, "", hash);
}

function activityView() {
    return `<section class="panel"><div class="panel-title"><h2>Order activity</h2><div><button id="activity-read">Mark all read</button><button id="activity-clear">Clear activity</button></div></div><p class="activity-note">Updates observed in this browser tab while your workspace is open. This is not the backend notification history.</p>${state.activity.map((event) => `<article class="activity-row ${event.read ? "" : "unread"}"><div><b>Order ${esc(event.orderId.slice(0, 8))} · ${esc(event.status)}</b><p>${esc(event.from)} → ${esc(event.status)}</p><small>${esc(new Date(event.at).toLocaleString())}</small></div><button data-order="${esc(event.orderId)}">View order →</button></article>`).join("") || '<div class="empty"><h3>You’re all caught up.</h3><p>Status changes will appear here as your orders update.</p></div>'}</section>`;
}

function navigateFromLocation() {
    if (!state.token) return;
    if (state.loading) {
        pendingNavigation = true;
        return;
    }
    tasks.invalidate();
    run(async () => {
        const route = parseRoute(window.location.hash);
        state.view = route.view;
        state.detail = null;
        state.product = null;
        state.quote = null;
        if (route.orderId) await openOrder(route.orderId);
        else if (state.view === "orders") await loadOrders();
        else if (["cart", "catalog"].includes(state.view)) await loadProducts();
    });
}

window.addEventListener("popstate", navigateFromLocation);
window.addEventListener("hashchange", navigateFromLocation);

function productView() {
    const p = state.product;
    return `<dialog open aria-modal="true" aria-labelledby="product-title"><div class="dialog-top"><p class="eyebrow">PRODUCT DETAILS</p><button id="close-product" aria-label="Close product details">✕</button></div><h2 id="product-title">${esc(p.name)}</h2><p class="full-id">${esc(p.sku)}</p><div class="product-art" aria-hidden="true">▦</div><div class="product-detail-price"><strong>${money(p.price_minor, p.currency)}</strong><small>per unit · ${esc(p.currency)}</small></div><label>Quantity in your cart<input type="number" data-sku="${esc(p.sku)}" value="${state.cart[p.sku] || 0}" min="0" max="1000" step="1" ${state.checkout ? "disabled" : ""}></label><p class="sub">Availability is checked during checkout. Prices are confirmed by the catalog when you review your order.</p></dialog>`;
}
