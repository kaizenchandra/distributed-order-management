import {test} from "node:test";
import assert from "node:assert/strict";
import {
    finishOperatorRequest,
    restoreOperatorRequest,
    resultLinks,
    saveOperatorRequest,
    settledOperation,
} from "../src/operator-recovery.js";

const token = (sub) =>
    "header." +
    Buffer.from(JSON.stringify({sub, iss: "oms"})).toString("base64url") +
    ".sig";
test("interrupted financial requests retain identity per operator without storing tokens", () => {
    const data = new Map(),
        storage = {
            getItem: (k) => data.get(k),
            setItem: (k, v) => data.set(k, v),
            removeItem: (k) => data.delete(k),
        };
    const draft = {
        selected: "refund",
        key: "request-id",
        values: {id: "payment-id", refundAmount: "2500"},
    };
    saveOperatorRequest(token("one"), draft, storage);
    assert.deepEqual(restoreOperatorRequest(token("one"), storage), draft);
    assert.equal(restoreOperatorRequest(token("two"), storage), null);
    assert.equal(
        [...data.values()].some((v) => v.includes(".sig")),
        false,
    );
    finishOperatorRequest(token("one"), storage);
    assert.equal(restoreOperatorRequest(token("one"), storage), null);
});
test("tracking recognizes terminal review states and related records use validated IDs", () => {
    assert.ok(settledOperation("SUCCEEDED"));
    assert.ok(settledOperation("REJECTED"));
    assert.ok(settledOperation("MANUAL_REVIEW"));
    assert.equal(settledOperation("UNKNOWN"), false);
    const id = "12345678-1234-1234-1234-123456789abc";
    assert.deepEqual(resultLinks({payment_id: id}, "operation"), [
        {action: "payment", id, label: "Inspect payment"},
    ]);
    assert.deepEqual(resultLinks({payment_id: "<script>"}, "operation"), []);
});
