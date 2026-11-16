let session = null;
let refreshing = null;
let generation = 0;

export function setSession(data) {
    generation++;
    session = {...data, expiresAt: Date.now() + (data.expires_in || 60) * 1000};
}

export function clearSession() {
    generation++;
    session = null;
    refreshing = null;
}

export async function accessToken() {
    if (!session) throw new Error("Your session ended. Please sign in again.");
    if (session.expiresAt > Date.now() + 30000) return session.access_token;
    if (!refreshing) {
        const current = generation;
        const task = (async () => {
            const response = await fetch(
                "/realms/oms/protocol/openid-connect/token",
                {
                    method: "POST",
                    signal: AbortSignal.timeout(15000),
                    body: new URLSearchParams({
                        grant_type: "refresh_token",
                        client_id: "oms-demo",
                        refresh_token: session.refresh_token || "",
                    }),
                },
            );
            if (current !== generation)
                throw new Error("Your session changed. Please retry.");
            if (!response.ok) {
                clearSession();
                throw new Error("Your session expired. Please sign in again.");
            }
            const data = await response.json();
            if (current !== generation || !session)
                throw new Error("Your session changed. Please retry.");
            setSession(data);
            return data.access_token;
        })();
        refreshing = task;
        task
            .finally(() => {
                if (refreshing === task) refreshing = null;
            })
            .catch(() => {
            });
    }
    return refreshing;
}

export async function endSession() {
    const refresh = session?.refresh_token;
    clearSession();
    if (refresh)
        await fetch("/realms/oms/protocol/openid-connect/logout", {
            method: "POST",
            signal: AbortSignal.timeout(10000),
            body: new URLSearchParams({
                client_id: "oms-demo",
                refresh_token: refresh,
            }),
        });
}
