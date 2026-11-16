import {test} from "node:test";
import assert from "node:assert/strict";
import {readFile} from "node:fs/promises";
import {JSDOM} from "jsdom";

const dom = new JSDOM('<div id="app"></div>', {url: "http://localhost:5173"});
for (const key of ["window", "document", "sessionStorage", "FormData"])
    globalThis[key] = dom.window[key];
let poll;
globalThis.setInterval = (fn) => {
    poll = fn;
    return 1;
};
const id = "12345678-1234-1234-1234-123456789abc";
const quote = {
    currency: "USD",
    amount: 15000,
    items: [{sku: "SKU-1", quantity: 2, unitPrice: 7500}],
};
let status = "PENDING",
    timelineDown = false,
    failCheckout = true;
const keys = [];
globalThis.fetch = async (path, options = {}) => {
    if (path.includes("/token"))
        return Response.json({
            access_token: "token",
            refresh_token: "refresh",
            expires_in: 300,
        });
    if (path.includes("/logout")) return new Response(null, {status: 204});
    if (path.endsWith("/products"))
        return Response.json([
            {sku: "SKU-1", name: "Keyboard", price_minor: 7500, currency: "USD"},
        ]);
    if (path.endsWith("/quotes")) {
        assert.deepEqual(JSON.parse(options.body), {
            items: [{sku: "SKU-1", quantity: 2}],
        });
        return Response.json(quote);
    }
    if (path === "/api/v1/orders?offset=0")
        return Response.json([{aggregate_id: id}]);
    if (path === "/api/v1/orders") {
        keys.push(options.headers["Idempotency-Key"]);
        if (failCheckout) {
            failCheckout = false;
            throw new Error("Failed to fetch");
        }
        return Response.json(
            {...quote, orderId: id, status, version: 1},
            {status: 202},
        );
    }
    if (path.includes("/customers/me/orders"))
        return Response.json(
            keys.length > 1
                ? [
                    {
                        order_id: id,
                        version: 2,
                        state: {value: JSON.stringify({...quote, status})},
                    },
                ]
                : [],
        );
    if (path.includes("?minVersion="))
        return Response.json({version: 1}, {status: 202});
    if (path.endsWith("/timeline"))
        return timelineDown
            ? Response.json({}, {status: 503})
            : Response.json([
                {type: "OrderCreated", version: 1, occurred_at: "2026-11-16"},
            ]);
    if (path.endsWith("/cancel")) {
        status = "CANCELLED";
        return Response.json({status: "CANCELLATION_REQUESTED"});
    }
    if (path.endsWith("/" + id))
        return Response.json({...quote, orderId: id, status, version: 2});
    throw new Error("Unexpected request " + path);
};

async function settle() {
    for (let i = 0; i < 12; i++)
        await new Promise((resolve) => setImmediate(resolve));
}

const click = async (selector) => {
    const el = document.querySelector(selector);
    assert.ok(el, "Missing control " + selector);
    assert.equal(el.disabled, false, "Disabled control " + selector);
    el.click();
    await settle();
};
const change = async (selector, value) => {
    const el = document.querySelector(selector);
    el.value = value;
    el.dispatchEvent(new dom.window.Event("change"));
    await settle();
};
const submit = async (selector) => {
    document
        .querySelector(selector)
        .dispatchEvent(
            new dom.window.Event("submit", {bubbles: true, cancelable: true}),
        );
    await settle();
};
let source = await readFile(new URL("../src/main.js", import.meta.url), "utf8");
source = source
    .replace(/import ["']\.\/style\.css["'];/, "")
    .replace(
        /from ["']\.\/(.*?)["']/g,
        (_, file) => `from '${new URL("../src/" + file, import.meta.url).href}'`,
    );
await import(
"data:text/javascript;base64," + Buffer.from(source).toString("base64")
    );
test("customer cart, failed checkout retry, timeline fallback, cancellation and reorder", async () => {
    document.querySelector("[name=username]").value = "customer";
    document.querySelector("[name=password]").value = "local-customer-only";
    await submit("#login");
    assert.match(document.body.textContent, /Order overview/);
    await click("[data-view=catalog]");
    await click("[data-product=SKU-1]");
    assert.ok(document.querySelector("#product-title"));
    await click("#close-product");
    await change("[data-sku=SKU-1]", "2");
    await click("[data-view=cart]");
    assert.match(document.body.textContent, /Keyboard/);
    assert.match(document.body.textContent, /Estimated total/);
    assert.match(sessionStorage.getItem("orbit:cart:customer"), /SKU-1/);
    await click("#primary");
    await click("#place");
    assert.match(document.body.textContent, /Cannot reach the service/);
    await click("#close-quote");
    await click("#primary");
    await click("#place");
    assert.equal(keys.length, 2);
    assert.equal(keys[0], keys[1]);
    assert.match(document.body.textContent, /Order accepted/);
    assert.equal(
        JSON.parse(sessionStorage.getItem("orbit:cart:customer")).checkout,
        null,
    );
    await click("#projection-refresh");
    assert.match(document.body.textContent, /projected version 1/);
    timelineDown = true;
    await click("#detail-refresh");
    assert.match(document.body.textContent, /Timeline unavailable/);
    assert.ok(document.querySelector("#cancel"));
    await click("#cancel");
    await click("#confirm-cancel");
    assert.match(document.body.textContent, /Cancellation requested/);
    assert.equal(document.querySelector("#cancel").disabled, true);
    await click("#detail-refresh");
    assert.match(document.body.textContent, /Checkout reversed/);
    await click("#close");
    await change("#history-source", "authoritative");
    assert.match(document.body.textContent, /CANCELLED/);
    await click("[data-view=activity]");
    assert.match(document.body.textContent, /Order activity/);
    assert.match(window.location.hash, /activity/);
    await click("[data-order]");
    await click("#reorder");
    assert.match(document.body.textContent, /Your shopping cart/);
    assert.equal(document.querySelector("[data-sku=SKU-1]").value, "2");
    await click("[data-view=operations]");
    assert.ok(document.querySelector("#operator-form"));
    assert.equal(document.querySelector("#primary").hidden, true);
    assert.match(document.querySelector("h1").textContent, /Operations/);
    await click("#logout");
    document.querySelector("[name=username]").value = "other";
    document.querySelector("[name=password]").value = "local-customer-only";
    await submit("#login");
    await click("[data-view=cart]");
    assert.equal(document.querySelector("[data-sku=SKU-1]"), null);
    const originalFetch = globalThis.fetch;
    let finish;
    globalThis.fetch = (path, options) =>
        path.endsWith("/products")
            ? new Promise((resolve) => {
                finish = resolve;
            })
            : originalFetch(path, options);
    await click("[data-view=catalog]");
    window.history.replaceState(null, "", "#activity");
    window.dispatchEvent(new dom.window.PopStateEvent("popstate"));
    finish(Response.json([]));
    await settle();
    assert.match(document.querySelector("h1").textContent, /Activity/);
    globalThis.fetch = originalFetch;
    await click("[data-view=orders]");
    Object.defineProperty(document, "hidden", {
        value: false,
        configurable: true,
    });
    document.activeElement?.blur();
    globalThis.fetch = (path, options) =>
        path === "/api/v1/orders?offset=0"
            ? new Promise((resolve) => {
                finish = resolve;
            })
            : originalFetch(path, options);
    const background = poll();
    await settle();
    await click("[data-view=catalog]");
    finish(Response.json({}, {status: 401}));
    await background;
    await settle();
    assert.ok(document.querySelector("#logout"));
    assert.match(document.querySelector("h1").textContent, /Product catalog/);
    globalThis.fetch = originalFetch;
});
