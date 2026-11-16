import {defineConfig, loadEnv} from "vite";
import {isAllowed} from "./src/operations-contract.js";

export default defineConfig(({mode}) => {
    const env = loadEnv(mode, process.cwd(), "");
    const targets = {
        inventory: ["OMS_INVENTORY_TARGET", 8092],
        payments: ["OMS_PAYMENT_TARGET", 8093],
        fulfillment: ["OMS_FULFILLMENT_TARGET", 8094],
        sagas: ["OMS_SAGA_TARGET", 8095],
        query: ["OMS_QUERY_TARGET", 8096],
        orders: ["OMS_ORDER_TARGET", 8091],
    };
    const proxy = {
        "/api": {
            target: env.OMS_API_TARGET || "http://localhost:8099",
            changeOrigin: true,
        },
        "/realms": {
            target: env.OMS_AUTH_TARGET || "http://localhost:8180",
            changeOrigin: true,
        },
    };
    for (const [service, [setting, port]] of Object.entries(targets))
        proxy[`^/ops-api/${service}/`] = {
            target: env[setting] || `http://localhost:${port}`,
            changeOrigin: true,
            rewrite: (path) => path.replace(`/ops-api/${service}`, ""),
        };
    return {
        plugins: [
            {
                name: "operations-route-allowlist",
                configureServer(server) {
                    server.middlewares.use((req, res, next) => {
                        if (!req.url?.startsWith("/ops-api/")) return next();
                        const match = new URL(req.url, "http://localhost").pathname.match(
                            /^\/ops-api\/([^/]+)(\/.*)$/,
                        );
                        if (!match || !isAllowed(match[1], match[2], req.method)) {
                            res.statusCode = 404;
                            res.setHeader("Content-Type", "application/json");
                            res.end(
                                JSON.stringify({
                                    detail: "Unsupported operations route or method",
                                }),
                            );
                            return;
                        }
                        next();
                    });
                },
            },
        ],
        server: {proxy},
    };
});
