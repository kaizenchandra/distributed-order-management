import {test} from "node:test";
import assert from "node:assert/strict";
import {cartItems, normalize, readWorkspace, receipt, saveWorkspace,} from "../src/domain.js";

test("rejects invalid checkout quantities and empty carts", () => {
    for (const quantity of [-1, 1.5, 1001, NaN])
        assert.throws(() => cartItems({"SKU-1": quantity}));
    assert.throws(() => cartItems({}));
    assert.throws(() =>
        cartItems(
            Object.fromEntries(Array.from({length: 51}, (_, i) => ["SKU-" + i, 1])),
        ),
    );
    assert.deepEqual(cartItems({"SKU-1": 2, "SKU-2": 0}), [
        {sku: "SKU-1", quantity: 2},
    ]);
});
test("normalizes PostgreSQL JSON wrapper and preserves projection version", () => {
    const row = normalize({
        order_id: "one",
        version: 3,
        state: {type: "jsonb", value: '{"status":"CONFIRMED","amount":7500}'},
    });
    assert.equal(row.status, "CONFIRMED");
    assert.equal(row.orderId, "one");
    assert.equal(row.version, 3);
});
test("checkout identity survives restore and is isolated by account", () => {
    const values = new Map();
    const storage = {
        getItem: (k) => values.get(k),
        setItem: (k, v) => values.set(k, v),
    };
    const checkout = {
        key: "12345678-1234-1234-1234-123456789abc",
    quote: {
      currency: "USD",
      amount: 15000,
      items: [{ sku: "SKU-1", quantity: 2, unitPrice: 7500 }],
    },
  };
  saveWorkspace("customer", { "SKU-1": 2 }, checkout, storage);
  assert.deepEqual(readWorkspace("customer", storage).checkout, checkout);
  assert.deepEqual(readWorkspace("other", storage), {
    cart: {},
    checkout: null,
  });
});
test("order summary distinguishes itself from a financial receipt", () => {
  const text = receipt({
    orderId: "one",
    status: "CONFIRMED",
    version: 2,
    currency: "USD",
    amount: 15000,
    items: [{ sku: "SKU-1", quantity: 2, unitPrice: 7500 }],
  });
  assert.match(text, /USD 150.00/);
  assert.match(text, /not a payment receipt/);
});

test("damaged recovery data blocks a new checkout until explicitly discarded", () => {
  const storage = {
    getItem: () =>
      JSON.stringify({
        cart: { "SKU-1": 2, "SKU-2": "bad" },
        checkout: { key: "damaged" },
      }),
  };
  const restored = readWorkspace("customer", storage);
  assert.deepEqual(restored.cart, { "SKU-1": 2 });
  assert.deepEqual(restored.checkout, { invalid: true });
});

test("invalid serialized checkout recovery is not silently discarded", () => {
  assert.deepEqual(
    readWorkspace("customer", { getItem: () => "{broken" }).checkout,
    { invalid: true },
  );
});
