import {test} from "node:test";
import assert from "node:assert/strict";
import {accessToken, clearSession, endSession, setSession,} from "../src/session.js";

test("parallel API calls share one refresh and use the renewed token", async () => {
    let calls = 0;
    globalThis.fetch = async (path, options) => {
        calls++;
        assert.equal(options.body.get("grant_type"), "refresh_token");
        assert.equal(options.body.get("refresh_token"), "old-refresh");
        await new Promise((r) => setImmediate(r));
        return Response.json({
            access_token: "new-token",
            refresh_token: "new-refresh",
            expires_in: 300,
        });
    };
    setSession({
        access_token: "old-token",
        refresh_token: "old-refresh",
        expires_in: 1,
    });
    assert.deepEqual(await Promise.all([accessToken(), accessToken()]), [
        "new-token",
        "new-token",
    ]);
    assert.equal(calls, 1);
    assert.equal(await accessToken(), "new-token");
    assert.equal(calls, 1);
});
test("rejected refresh clears access and requests a new sign-in", async () => {
    setSession({access_token: "old", refresh_token: "expired", expires_in: 1});
    globalThis.fetch = async () => new Response(null, {status: 400});
    await assert.rejects(accessToken(), /session expired/);
    await assert.rejects(accessToken(), /session ended/);
});
test("logout revokes the refresh token and clears the local session", async () => {
    setSession({
        access_token: "active",
        refresh_token: "refresh",
        expires_in: 300,
    });
    globalThis.fetch = async (path, options) => {
        assert.match(path, /logout$/);
        assert.equal(options.body.get("refresh_token"), "refresh");
        return new Response(null, {status: 204});
    };
    await endSession();
    await assert.rejects(accessToken(), /session ended/);
    clearSession();
});
test("an old refresh cannot replace a newly signed-in account", async () => {
    let finish;
    globalThis.fetch = () =>
        new Promise((resolve) => {
            finish = resolve;
        });
    setSession({
        access_token: "old",
        refresh_token: "old-refresh",
        expires_in: 1,
    });
    const old = accessToken();
    setSession({
        access_token: "new-account",
        refresh_token: "new-refresh",
        expires_in: 300,
    });
    finish(Response.json({access_token: "stale", expires_in: 300}));
    await assert.rejects(old, /session changed/);
    assert.equal(await accessToken(), "new-account");
});
test("account switch during refresh-body decoding cannot restore an older token", async () => {
  let finishBody;
  globalThis.fetch = async () => ({
    ok: true,
    json: () =>
      new Promise((resolve) => {
        finishBody = resolve;
      }),
  });
  setSession({ access_token: "old", refresh_token: "refresh", expires_in: 1 });
  const pending = accessToken();
  await new Promise((resolve) => setImmediate(resolve));
  setSession({ access_token: "newer", expires_in: 300 });
  finishBody({ access_token: "stale-body", expires_in: 300 });
  await assert.rejects(pending, /session changed/);
  assert.equal(await accessToken(), "newer");
});
