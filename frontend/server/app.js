import http from "node:http";
import https from "node:https";
import {readFile, stat} from "node:fs/promises";
import {extname, resolve, sep} from "node:path";
import {fileURLToPath} from "node:url";
import {isAllowed} from "../src/operations-contract.js";

const serviceTargets = {
    inventory: ["OMS_INVENTORY_TARGET", 8092],
    payments: ["OMS_PAYMENT_TARGET", 8093],
    fulfillment: ["OMS_FULFILLMENT_TARGET", 8094],
    sagas: ["OMS_SAGA_TARGET", 8095],
    query: ["OMS_QUERY_TARGET", 8096],
    orders: ["OMS_ORDER_TARGET", 8091],
};

export function upstreamFor(url, method, env = {}) {
    const parsed = new URL(url, "http://localhost");
    if (parsed.pathname.startsWith("/ops-api/")) {
        const match = parsed.pathname.match(/^\/ops-api\/([^/]+)(\/.*)$/);
        if (!match || !isAllowed(match[1], match[2], method)) return null;
        const [setting, port] = serviceTargets[match[1]];
        return new URL(
            match[2] + parsed.search,
            env[setting] || `http://localhost:${port}`,
        );
    }
    if (parsed.pathname.startsWith("/api/"))
        return new URL(
            parsed.pathname + parsed.search,
            env.OMS_API_TARGET || "http://localhost:8099",
        );
    if (parsed.pathname.startsWith("/realms/oms/"))
        return new URL(
            parsed.pathname + parsed.search,
            env.OMS_AUTH_TARGET || "http://localhost:8180",
        );
    return null;
}

const mime = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".svg": "image/svg+xml",
    ".json": "application/json",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
};

export function createFrontendServer({
                                         env = {},
                                         dist = resolve(fileURLToPath(new URL("../dist", import.meta.url))),
                                     } = {}) {
    const server = http.createServer(async (req, res) => {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "same-origin");
        const fail = (status, detail) => {
            if (res.headersSent) return;
            res.writeHead(status, {
                "Content-Type": "application/json",
                "Cache-Control": "no-store",
            });
            res.end(JSON.stringify({detail}));
        };
        try {
            const target = upstreamFor(req.url, req.method, env);
            if (target) {
                const chunks = [];
                let size = 0;
                for await (const chunk of req) {
                    size += chunk.length;
                    if (size > 65536) {
                        fail(413, "Request body is too large");
                        return;
                    }
                    chunks.push(chunk);
                }
                const outgoing = (target.protocol === "https:" ? https : http).request(
                    target,
                    {
                        method: req.method,
                        headers: {...req.headers, host: target.host, connection: "close"},
                    },
                    (incoming) => {
                        res.writeHead(incoming.statusCode, {
                            ...incoming.headers,
                            "Cache-Control": "no-store",
                            "X-Content-Type-Options": "nosniff",
                        });
                        incoming.pipe(res);
                        incoming.on("error", () => res.destroy());
                    },
                );
                outgoing.setTimeout(25000, () =>
                    outgoing.destroy(new Error("Upstream timeout")),
                );
                outgoing.on("error", () =>
                    fail(
                        502,
                        "The backend service is unavailable. Check its connection and try again.",
                    ),
                );
                req.on("aborted", () => outgoing.destroy());
                outgoing.end(Buffer.concat(chunks));
                return;
            }
            const pathname = new URL(req.url, "http://localhost").pathname;
            if (
                pathname.startsWith("/api/") ||
                pathname.startsWith("/realms/") ||
                pathname.startsWith("/ops-api/")
            ) {
                fail(404, "Unsupported API route or method");
                return;
            }
            if (!["GET", "HEAD"].includes(req.method)) {
                fail(405, "Method not allowed");
                return;
            }
            const path = resolve(
                dist,
                "." + decodeURIComponent(pathname === "/" ? "/index.html" : pathname),
            );
            if (!path.startsWith(resolve(dist) + sep)) {
                fail(404, "Not found");
                return;
            }
            const info = await stat(path);
            if (!info.isFile()) {
                fail(404, "Not found");
                return;
            }
            const body = await readFile(path);
            res.writeHead(200, {
                "Content-Type": mime[extname(path)] || "application/octet-stream",
                "Cache-Control": pathname.startsWith("/assets/")
                    ? "public, max-age=31536000, immutable"
                    : "no-cache",
            });
            res.end(req.method === "HEAD" ? undefined : body);
        } catch (error) {
            fail(
                error.code === "ENOENT" ? 404 : 400,
                error.code === "ENOENT"
                    ? "File not found. Run npm run build first."
                    : "Invalid request",
            );
        }
    });
    server.requestTimeout = 30000;
    server.headersTimeout = 15000;
    return server;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
    const root = fileURLToPath(new URL("../", import.meta.url));
    let local = {};
    for (const file of [".env", ".env.local"])
        try {
            const text = await readFile(resolve(root, file), "utf8");
            for (const line of text.split("\n")) {
                const match = line.match(/^([A-Z_][A-Z_0-9]*)=(.*)$/);
                if (match)
                    local[match[1]] = match[2].trim().replace(/^['"]|['"]$/g, "");
            }
        } catch {
        }
    const env = {...local, ...process.env};
    const port = Number(env.OMS_FRONTEND_PORT || 4173);
    const server = createFrontendServer({env});
    server.listen(port, "127.0.0.1", () =>
        console.log(`Orbit frontend: http://localhost:${port}`),
    );
    server.on("error", (error) => {
        console.error(error.message);
        process.exitCode = 1;
    });
}
