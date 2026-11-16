export const STATUSES = ["PENDING", "CONFIRMED", "CANCELLED"];

export function cartItems(cart) {
    const items = Object.entries(cart)
        .filter(([, quantity]) => quantity !== 0)
        .map(([sku, quantity]) => ({sku, quantity}));
    if (!items.length || items.length > 50)
        throw new Error("Choose between 1 and 50 products.");
    if (
        items.some(
            (i) =>
                !i.sku ||
                !Number.isInteger(i.quantity) ||
                i.quantity < 1 ||
                i.quantity > 1000,
        )
    )
        throw new Error("Quantities must be whole numbers between 1 and 1000.");
    return items;
}

export function normalize(row) {
    let value = row.state?.value ?? row.state ?? row;
    if (typeof value === "string") value = JSON.parse(value);
    return {
        ...value,
        orderId: row.order_id || value.orderId,
        version: row.version ?? value.version,
    };
}

export function storageKey(user) {
    return `orbit:cart:${encodeURIComponent(user)}`;
}

export function readWorkspace(user, storage = sessionStorage) {
    let raw;
  try {
    raw = storage.getItem(storageKey(user));
  } catch {
    return { cart: {}, checkout: null };
  }
  try {
    const value = JSON.parse(raw || "{}");
    const cart = {};
    for (const [sku, quantity] of Object.entries(value?.cart || {}))
      if (
        sku &&
        Number.isInteger(quantity) &&
        quantity >= 0 &&
        quantity <= 1000
      )
        cart[sku] = quantity;
    let checkout = value?.checkout || null;
    if (checkout) {
      const q = checkout.quote;
      const valid =
        /^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(checkout.key || "") &&
        q &&
        /^[A-Z]{3}$/.test(q.currency) &&
        Number.isSafeInteger(q.amount) &&
        q.amount > 0 &&
        Array.isArray(q.items) &&
        q.items.length > 0 &&
        q.items.length <= 50 &&
        q.items.every(
          (i ) =>
            i &&
            typeof i.sku === "string" &&
            i.sku &&
            Number.isInteger(i.quantity) &&
            i.quantity > 0 &&
            i.quantity <= 1000 &&
            Number.isSafeInteger(i.unitPrice) &&
            i.unitPrice >= 0,
        ) &&
        new Set(q.items.map((i ) => i.sku)).size === q.items.length;
      if (!valid) checkout = { invalid: true };
    }
    return { cart, checkout };
  } catch {
    return { cart: {}, checkout: { invalid: true } };
    }
}

export function saveWorkspace(user, cart, checkout, storage = sessionStorage) {
    storage.setItem(storageKey(user), JSON.stringify({cart, checkout}));
}

export function receipt(order) {
    const price = (amount) => `${order.currency} ${(amount / 100).toFixed(2)}`;
    return [
        "ORBIT · ORDER SUMMARY",
        `Order: ${order.orderId}`,
        `Status: ${order.status}`,
        `Version: ${order.version}`,
        "",
        ...(order.items || []).map(
            (i) => `${i.sku} × ${i.quantity} — ${price(i.quantity * i.unitPrice)}`,
        ),
        "",
        `Total: ${price(order.amount)}`,
        "",
        "This order summary is not a payment receipt or tax invoice. Payment and delivery details are not exposed by the customer API.",
    ].join("\n");
}
