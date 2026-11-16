import {test} from "node:test";
import assert from "node:assert/strict";
import {signIn} from "../src/auth.js";

test("customer login submits the configured Keycloak grant", async () => {
    globalThis.fetch = async (path, options) => {
        assert.equal(path, "/realms/oms/protocol/openid-connect/token");
        assert.equal(options.method, "POST");
        assert.equal(options.body.get("client_id"), "oms-demo");
        assert.equal(options.body.get("username"), "customer");
        assert.equal(options.body.get("password"), "local-customer-only");
        return Response.json({access_token: "test-token"});
    };
    assert.equal(await signIn("customer", "local-customer-only"), "test-token");
});
test("wrong realm is distinguished from wrong credentials", async () => {
    globalThis.fetch = async () =>
        Response.json({error: "Realm does not exist"}, {status: 404});
    await assert.rejects(signIn("customer", "test"), /realm was not found/);
});
test("invalid credentials show the Keycloak error", async () => {
    globalThis.fetch = async () =>
        Response.json(
            {error: "invalid_grant", error_description: "Invalid user credentials"},
            {status: 401},
        );
    await assert.rejects(signIn("customer", "wrong"), /Invalid user credentials/);
});
test("proxy outage reports service availability", async () => {
    globalThis.fetch = async () => new Response("", {status: 500});
    await assert.rejects(signIn("customer", "test"), /Keycloak is unavailable/);
});
test("network outage reports connection failure", async () => {
    globalThis.fetch = async () => {
        throw new TypeError("fetch failed");
    };
    await assert.rejects(signIn("customer", "test"), /Cannot reach Keycloak/);
});
