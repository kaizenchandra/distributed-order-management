import { test } from "node:test";
import assert from "node:assert/strict";
import { createTaskGuard } from "../src/task-guard.js";
test("navigation and account invalidation discard old asynchronous results", () => {
  const guard = createTaskGuard();
  const old = guard.capture();
  assert.equal(old(), true);
  guard.invalidate();
  assert.equal(old(), false);
  const current = guard.capture();
  assert.equal(current(), true);
  guard.invalidate();
  assert.equal(current(), false);
});
