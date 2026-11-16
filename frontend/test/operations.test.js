import {test} from "node:test";
import assert from "node:assert/strict";
import {actions, buildRequest, isAllowed, tokenScopes,} from "../src/operations-contract.js";
import {JSDOM} from "jsdom";
import {clearOperations, mountOperations} from "../src/operations.js";

const id = "12345678-1234-1234-1234-123456789abc";
test("all console actions are allowed only for their declared method and owning service", () => {
    for (const action of actions) {
        const path = "/api/v1" + action.path.replace("{id}", id);
        assert.ok(isAllowed(action.service, path, action.method || "GET"));
        assert.equal(isAllowed("unknown", path, action.method || "GET"), false);
    }
    assert.equal(
        isAllowed("payments", "/api/v1/payments/x/refunds", "DELETE"),
        false,
    );
    assert.equal(isAllowed("query", "/actuator/env", "GET"), false);
    assert.equal(
        isAllowed("inventory", "/api/v1/inventory/reservations/try", "POST"),
        true,
    );
});
test("refund payload uses integer minor units and stable request identity", () => {
    const action = actions.find((a) => a.id === "refund");
    const request = buildRequest(
        action,
        {id, refundAmount: "2500"},
        "retry-key",
    );
    assert.equal(request.url, `/ops-api/payments/api/v1/payments/${id}/refunds`);
    assert.deepEqual(JSON.parse(request.body), {refundAmount: 2500});
    assert.equal(request.headers["Idempotency-Key"], "retry-key");
    for (const amount of ["0", "-1", "0.5", "9007199254740992"])
        assert.throws(() =>
            buildRequest(action, {id, refundAmount: amount}, "key"),
        );
});
test("input validation, URL encoding and scope display", () => {
    assert.throws(() =>
        buildRequest(
            actions.find((a) => a.id === "payment"),
            {id: "invalid"},
        ),
    );
    assert.match(
        buildRequest(
            actions.find((a) => a.id === "stock"),
            {id: "SKU/1"},
        ).url,
        /SKU%2F1/,
    );
    assert.deepEqual(tokenScopes("malformed"), []);
});
test("operator console respects customer scopes and confirms financial changes with safe retries", async (t) => {
    t.after(clearOperations);
    const dom = new JSDOM('<main id="host"></main>', {
        url: "http://localhost:5173",
        pretendToBeVisual: true,
    });
    globalThis.document = dom.window.document;
    globalThis.FormData = dom.window.FormData;
    globalThis.window = dom.window;
    globalThis.sessionStorage = dom.window.sessionStorage;
    const host = document.querySelector("#host");
    mountOperations(host);
    const makeToken = (scope) =>
        "header." +
        Buffer.from(
            JSON.stringify({
                scope,
                sub: "operator-one",
                iss: "http://localhost/realms/oms",
            }),
        ).toString("base64url") +
        ".signature";
    const connect = (scope) => {
        host.querySelector("#operator-token").value = makeToken(scope);
        host.querySelector("#operator-connect").click();
    };
    connect("oms");
    assert.equal(host.querySelector("button.primary").disabled, true);
    connect("oms.service oms.admin");
    let failure = true;
    const requests = [];
    globalThis.fetch = async (url, options) => {
        requests.push({url, options});
        if (!options.method)
            return Response.json({operation_id: id, status: "SUCCEEDED"});
        if (failure) {
            failure = false;
            throw new Error("network timeout");
        }
        return Response.json(
            {operationId: id, status: "PENDING"},
            {status: 202},
        );
    };
    host.querySelector("[data-action=refund]").click();
    host.querySelector("[name=id]").value = id;
    host.querySelector("[name=refundAmount]").value = "2500";
    const submit = () =>
        host
            .querySelector("form")
            .dispatchEvent(new dom.window.Event("submit", {cancelable: true}));
    const settle = async () => {
        for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r));
    };
    submit();
    assert.equal(requests.length, 0);
    assert.ok(host.querySelector("#operator-confirm"));
    host.querySelector("#operator-confirm").click();
    await settle();
    assert.match(host.textContent, /uncertain/);
    clearOperations();
    mountOperations(host);
    connect("oms.service oms.admin");
    assert.match(host.textContent, /interrupted request was restored/);
    assert.equal(host.querySelector("[name=refundAmount]").disabled, true);
    submit();
    host.querySelector("#operator-confirm").click();
    await settle();
    assert.equal(requests.length, 2);
    assert.equal(
        requests[0].options.headers["Idempotency-Key"],
        requests[1].options.headers["Idempotency-Key"],
    );
    assert.match(host.textContent, /processing asynchronously/);
    assert.ok(host.querySelector("[data-poll-operation]"));
    host.querySelector("#operation-track").click();
    await settle();
    assert.match(host.textContent, /Operation reached SUCCEEDED/);
    clearOperations();
    mountOperations(host);
    assert.equal(host.querySelector("#operator-token").value, "");
});
test("reservation UI validates items and carries ownership into the next confirmed phase", async (t) => {
    t.after(clearOperations);
    clearOperations();
    const dom = new JSDOM('<main id="host"></main>', {
        url: "http://localhost:5173",
        pretendToBeVisual: true,
    });
    for (const name of ["window", "document", "FormData", "sessionStorage"])
        globalThis[name] = dom.window[name];
    const host = document.querySelector("#host");
    mountOperations(host);
    host.querySelector("#operator-token").value =
        "header." +
        Buffer.from(
            JSON.stringify({
                scope: "oms.service",
                sub: "reservation-test",
                iss: "oms",
            }),
        ).toString("base64url") +
        ".sig";
    host.querySelector("#operator-connect").click();
    host.querySelector("[data-action=reservation-try]").click();
    host.querySelector("#reservation-id-generate").click();
    const reservationId = host.querySelector("[name=reservationId]").value;
    assert.match(reservationId, /^[\da-f-]{36}$/);
    host.querySelector("[name=orderId]").value = id;
    host.querySelector("[name=items]").value = "SKU-1,2\nSKU-2,1";
    let count = 0;
    globalThis.fetch = async (url, options) => {
        count++;
        const body = JSON.parse(options.body);
        assert.equal(body.orderId, id);
        assert.equal(body.reservationId, reservationId);
        assert.equal(body.items.length, 2);
        return Response.json({id: reservationId, state: "TRIED"});
    };
    host
        .querySelector("form")
        .dispatchEvent(new dom.window.Event("submit", {cancelable: true}));
    assert.equal(count, 0);
    host.querySelector("#operator-confirm").click();
    for (let i = 0; i < 8; i++) await new Promise((r) => setImmediate(r));
    assert.equal(count, 1);
    host.querySelector("[data-reservation-phase=confirm]").click();
    assert.equal(host.querySelector("[name=id]").value, reservationId);
    assert.equal(host.querySelector("[name=orderId]").value, id);
    assert.equal(count, 1);
});
