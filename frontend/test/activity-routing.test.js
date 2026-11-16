import {test} from "node:test";
import assert from "node:assert/strict";
import {observeOrders, readActivity, updateActivity,} from "../src/activity.js";
import {parseRoute, routeHash} from "../src/routing.js";

test("activity only records observed changes and stays account isolated", () => {
    const values = new Map(),
        storage = {
            getItem: (k) => values.get(k),
            setItem: (k, v) => values.set(k, v),
        };
    const order = {orderId: "one", status: "PENDING", version: 1};
    assert.equal(observeOrders("customer", [order], storage).length, 0);
    assert.equal(
        observeOrders(
            "customer",
            [{...order, status: "CONFIRMED", version: 2}],
            storage,
        ).length,
        1,
    );
    assert.equal(
        observeOrders(
            "customer",
            [{...order, status: "CONFIRMED", version: 2}],
            storage,
        ).length,
        1,
    );
    assert.equal(observeOrders("customer", [order], storage).length, 1);
    assert.equal(readActivity("customer", storage).known.one.status, "CONFIRMED");
    assert.equal(readActivity("other", storage).events.length, 0);
    assert.equal(updateActivity("customer", "read", storage)[0].read, true);
    assert.equal(updateActivity("customer", "clear", storage).length, 0);
});
test("routes support order deep links and reject invalid paths", () => {
    const id = "12345678-1234-1234-1234-123456789abc";
    assert.deepEqual(parseRoute(routeHash("orders", id)), {
        view: "orders",
        orderId: id,
    });
    assert.deepEqual(parseRoute("#operations"), {view: "operations"});
    assert.deepEqual(parseRoute("#activity"), {view: "activity"});
    assert.deepEqual(parseRoute("#orders/not-a-uuid"), {view: "orders"});
    assert.deepEqual(parseRoute("#unknown"), {view: "orders"});
});
