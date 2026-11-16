import {test} from "node:test";
import assert from "node:assert/strict";
import {reservationItems} from "../src/reservation-items.js";
import {projectionSummary} from "../src/projection.js";
import {actions, buildRequest} from "../src/operations-contract.js";

const id = "12345678-1234-1234-1234-123456789abc";
test("reservation items validate before any state-changing request", () => {
    assert.deepEqual(reservationItems("SKU-1,2\nSKU-2,1"), [
        {sku: "SKU-1", quantity: 2},
        {sku: "SKU-2", quantity: 1},
    ]);
    for (const text of [
        "",
        "SKU-1,0",
        "SKU-1,1001",
        "SKU-1,1.5",
        "SKU-1,1\nSKU-1,2",
        "SKU-1,1,2",
    ])
        assert.throws(() => reservationItems(text));
});
test("TCC forms preserve reservation ownership and use existing endpoint payloads", () => {
    const request = buildRequest(
        actions.find((a) => a.id === "reservation-try"),
        {reservationId: id, orderId: id, items: "SKU-1,2"},
    );
    assert.deepEqual(JSON.parse(request.body), {
        reservationId: id,
        orderId: id,
        items: [{sku: "SKU-1", quantity: 2}],
    });
    for (const phase of ["confirm", "cancel"]) {
        const r = buildRequest(
            actions.find((a) => a.id === "reservation-" + phase),
            {id, orderId: id},
        );
        assert.equal(
            r.url,
            `/ops-api/inventory/api/v1/inventory/reservations/${id}/${phase}`,
        );
        assert.deepEqual(JSON.parse(r.body), {orderId: id});
    }
});
test("projection checks distinguish current, lagging, missing and unavailable", () => {
    assert.equal(projectionSummary(200, {}, 3).kind, "current");
    assert.match(
        projectionSummary(202, {version: 1}, 3).message,
        /projected version 1/,
    );
    assert.match(projectionSummary(404, {}, 3).message, /not appeared/);
    assert.equal(projectionSummary(503, {}, 3).kind, "unavailable");
});
