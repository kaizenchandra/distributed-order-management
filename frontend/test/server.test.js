import {test} from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import {mkdtemp, rm, writeFile} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {createFrontendServer, upstreamFor} from "../server/app.js";

const listen = (server) =>
    new Promise((resolve) =>
        server.listen(0, "127.0.0.1", () =>
            resolve(`http://127.0.0.1:${server.address().port}`),
        ),
    );
const close = (server) =>
    new Promise((resolve) => {
        server.closeAllConnections();
        server.close(resolve);
    });
test("production router preserves allowlisting and configurable targets", () => {
    assert.equal(upstreamFor("/ops-api/query/actuator/env", "GET"), null);
    assert.equal(
        upstreamFor("/ops-api/inventory/api/v1/inventory/SKU-1", "DELETE"),
        null,
    );
    assert.equal(
        upstreamFor("/api/v1/orders?offset=50", "GET", {
            OMS_API_TARGET: "http://localhost:1234",
        }).href,
        "http://localhost:1234/api/v1/orders?offset=50",
    );
});
test("built frontend serves static files and proxies authenticated requests without Vite", async () => {
    const dist = await mkdtemp(join(tmpdir(), "orbit-server-"));
    await writeFile(join(dist, "index.html"), "<h1>Orbit</h1>");
    const backend = http.createServer((req, res) => {
        assert.equal(req.url, "/api/v1/inventory/SKU-1");
        assert.equal(req.headers.authorization, "Bearer example");
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({available: 10}));
    });
    const target = await listen(backend);
    const frontend = createFrontendServer({
        dist,
        env: {OMS_INVENTORY_TARGET: target},
    });
    const base = await listen(frontend);
    try {
        const page = await fetch(base);
        assert.equal(page.status, 200);
        assert.match(await page.text(), /Orbit/);
        const response = await fetch(
            base + "/ops-api/inventory/api/v1/inventory/SKU-1",
            {headers: {Authorization: "Bearer example"}},
        );
        assert.deepEqual(await response.json(), {available: 10});
        assert.equal(response.headers.get("cache-control"), "no-store");
        assert.equal(
            (await fetch(base + "/ops-api/query/actuator/env")).status,
            404,
        );
        assert.equal((await fetch(base + "/%2e%2e%2fsecret")).status, 404);
        assert.equal((await fetch(base + "/missing.js")).status, 404);
    } finally {
        await close(frontend);
        await close(backend);
        await rm(dist, {recursive: true, force: true});
    }
});
