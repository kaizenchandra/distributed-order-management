import {
    finishOperatorRequest,
    restoreOperatorRequest,
    resultLinks,
    saveOperatorRequest,
    settledOperation,
} from "./operator-recovery.js";
import {actions, buildRequest, tokenScopes} from "./operations-contract.js";

let token = "",
    selected = actions[0].id,
    values = {},
    result = null,
    error = "",
    pending = null,
    busy = false,
    host = null,
    key = null,
    locked = false,
    generation = 0,
    tracking = null,
    recoveryNotice = "";
const esc = (v) =>
    String(v ?? "").replace(
        /[&<>"']/g,
        (c) =>
            ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"})[
                c
                ],
    );

export function clearOperations() {
    generation++;
    stopTracking();
    busy = false;
    token = "";
    values = {};
    result = null;
    error = "";
    recoveryNotice = "";
    pending = null;
    locked = false;
    key = null;
}

export function mountOperations(element) {
    host = element;
    render();
}

function render() {
    if (!host?.isConnected) return;
    const action = actions.find((a) => a.id === selected);
    const scopes = tokenScopes(token);
    host.innerHTML = `<section class="operator-intro"><h2>Operations console</h2><p class="sub">Use an authorized operator access token for inventory, financial operations, fulfillment and recovery. The backend enforces all permissions.</p></section><section class="panel operator-access"><label for="operator-token">Operator access token · kept only in memory</label><div><input id="operator-token" type="password" autocomplete="off" placeholder="Paste an authorized OMS access token" value="${esc(token)}" ${busy ? "disabled" : ""}><button id="operator-connect" ${busy ? "disabled" : ""}>Use token</button>${token && restoreOperatorRequest(token) ? `<button id="operator-resume" ${busy ? "disabled" : ""}>Resume saved request</button>` : ""}<button id="operator-clear" ${busy ? "disabled" : ""}>Clear session</button></div><small>${token ? "Token claims: " + esc(scopes.join(", ") || "no operation scopes") : "Requires oms.service and/or oms.admin. Customer tokens cannot perform these operations."}</small></section><div class="operator-layout"><nav class="panel operator-nav" aria-label="Operation categories">${[
        ...new Set(actions.map((a) => a.group)),
    ]
        .map(
            (group) =>
                `<p class="eyebrow">${group}</p>${actions
                    .filter((a) => a.group === group)
                    .map(
                        (a) =>
                            `<button data-action="${a.id}" class="${a.id === selected ? "active" : ""}" ${busy ? "disabled" : ""}>${esc(a.title)}</button>`,
                    )
                    .join("")}`,
        )
        .join(
            "",
        )}</nav><section class="panel operator-form"><p class="eyebrow">${esc(action.group)} · ${action.scope}</p><h2>${esc(action.title)}</h2>${recoveryNotice ? `<div class="notice">${esc(recoveryNotice)}</div>` : ""}${error ? `<div class="error" role="alert">${esc(error)}</div>` : ""}<form id="operator-form">${action.fields.map((f) => `<label>${esc(f.label)}${f.type === "items" ? `<textarea name="${f.name}" rows="5" required placeholder="SKU-1,2\nSKU-2,1" ${locked || busy ? "disabled" : ""}>${esc(values[f.name] || "")}</textarea>` : `<input name="${f.name}" value="${esc(values[f.name] || "")}" required ${locked || busy ? "disabled" : ""} ${f.type === "amount" ? 'inputmode="numeric"' : ""} autocomplete="off">`}${f.name === "reservationId" ? `<button type="button" id="reservation-id-generate" ${locked || busy ? "disabled" : ""}>Generate reservation ID</button>` : ""}</label>`).join("")}${action.warning ? `<p class="operation-warning">${esc(action.warning)}</p>` : ""}${key ? `<small>Request identity: ${esc(key)}. Retried submissions keep this identity.</small>` : ""}<div class="dialog-actions"><button class="primary" ${busy || !token || !scopes.includes(action.scope) ? "disabled" : ""}>${busy ? "Requesting…" : locked ? "Retry same request" : action.method === "POST" ? "Review action →" : "Load details"}</button>${locked ? '<button type="button" id="operator-new">Finish / start another request</button>' : ""}</div></form>${pending ? `<section class="cancel-confirm"><h3>Confirm ${esc(action.title.toLowerCase())}</h3><p>${esc(action.warning)}</p><p>Resource: <b>${esc(values.id || values.orderId || "All order projections")}</b></p><button id="operator-confirm" class="danger" ${busy ? "disabled" : ""}>Confirm action</button><button id="operator-back" ${busy ? "disabled" : ""}>Go back</button></section>` : ""}<div role="status">${result ? resultView(result) : ""}</div></section></div>`;
    host.querySelector("#operator-connect").onclick = () => {
        const next = host.querySelector("#operator-token").value.trim();
        clearOperations();
        token = next;
        result = null;
        error = tokenScopes(token).some((s) =>
            ["oms.service", "oms.admin"].includes(s),
        )
            ? ""
            : "This token does not claim oms.service or oms.admin. Obtain an authorized operator token.";
        const draft = restoreOperatorRequest(token);
        if (draft && actions.some((a) => a.id === draft.selected)) {
            selected = draft.selected;
            values = draft.values;
            key = draft.key;
            locked = true;
            recoveryNotice =
                "An interrupted request was restored. Inspect its outcome or retry with this same request identity.";
        }
        render();
    };
    host.querySelector("#operator-resume")?.addEventListener("click", () => {
        if (busy) return;
        const draft = restoreOperatorRequest(token);
        if (!draft || !actions.some((action) => action.id === draft.selected))
            return;
        stopTracking();
        selected = draft.selected;
        values = draft.values;
        key = draft.key;
        locked = true;
        result = null;
        pending = null;
        recoveryNotice = "Saved request restored with its original identity.";
        render();
    });
    host.querySelector("#operator-clear").onclick = () => {
        clearOperations();
        render();
    };
    host.querySelectorAll("[data-action]").forEach(
        (b) =>
            (b.onclick = () => {
                if (
                    locked &&
                    !window.confirm(
                        "An operation may already be queued. Keep its request identity for safe retries. Leave this request?",
                    )
                )
                    return;
                stopTracking();
                selected = b.dataset.action;
                values = {};
                key = null;
                locked = false;
                result = null;
                pending = null;
                error = "";
                render();
            }),
    );
    host
        .querySelector("#reservation-id-generate")
        ?.addEventListener("click", () => {
            host.querySelector("[name=reservationId]").value = crypto.randomUUID();
        });
    host.querySelectorAll("[data-reservation-phase]").forEach(
        (button) =>
            (button.onclick = () => {
                if (busy) return;
                const id = result.data.id,
                    orderId = values.orderId;
                finishOperatorRequest(token);
                selected = "reservation-" + button.dataset.reservationPhase;
                values = {id, orderId};
                key = null;
                locked = false;
                pending = null;
                result = null;
                recoveryNotice =
                    "Review the next reservation phase using the same reservation and owning order.";
                render();
            }),
    );
    host.querySelector("#operator-form").onsubmit = (e) => {
        e.preventDefault();
        if (!locked) values = Object.fromEntries(new FormData(e.target));
        try {
            key = key || crypto.randomUUID();
            const request = buildRequest(action, values, key);
            error = "";
            if (action.method === "POST") {
                pending = request;
                render();
            } else execute(request);
        } catch (e) {
            error = e.message;
            render();
        }
    };
    host
        .querySelector("#operator-confirm")
        ?.addEventListener("click", () => execute(pending));
    host.querySelector("#operator-back")?.addEventListener("click", () => {
        pending = null;
        render();
    });
    host.querySelector("#operator-new")?.addEventListener("click", () => {
        if (
            !window.confirm(
                "If the previous request timed out, inspect its operation status before starting a new request. Continue?",
            )
        )
            return;
        stopTracking();
        finishOperatorRequest(token);
        recoveryNotice = "";
        key = null;
        locked = false;
        result = null;
        pending = null;
        render();
    });
    host
        .querySelector("#operation-track")
        ?.addEventListener("click", () =>
            startTracking(result.data.operationId || result.data.operation_id),
        );
    host.querySelector("#operation-stop")?.addEventListener("click", () => {
        stopTracking();
        render();
    });
    host.querySelector("#operation-export")?.addEventListener("click", () => {
        const url = URL.createObjectURL(
            new Blob([JSON.stringify(result.data, null, 2)], {
                type: "application/json",
            }),
        );
        const a = document.createElement("a");
        a.href = url;
        a.download = `orbit-${selected}-result.json`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    });
    host.querySelectorAll("[data-related]").forEach(
        (button) =>
            (button.onclick = () => {
                const link = resultLinks(result.data, selected)[
                    Number(button.dataset.related)
                    ];
                stopTracking();
                selected = link.action;
                values = {id: link.id};
                locked = false;
                pending = null;
                result = null;
                key = null;
                render();
            }),
    );
    host.querySelector("[data-poll-operation]")?.addEventListener("click", () => {
        const id = result.data.operationId;
        selected = "operation";
        values = {id};
        key = null;
        locked = false;
        result = null;
        execute(
            buildRequest(
                actions.find((a) => a.id === "operation"),
                values,
            ),
        );
    });
}

async function execute(request) {
    if (busy) return;
    stopTracking();
    const current = generation;
    busy = true;
    pending = null;
    error = "";
    if (request.method === "POST") {
        locked = true;
        if (
            actions.find((a) => a.id === selected)?.idempotent ||
            actions.find((a) => a.id === selected)?.recoverable
        ) {
            try {
                saveOperatorRequest(token, {selected, values, key});
            } catch {
                recoveryNotice =
                    "Storage is unavailable. Keep this tab open and preserve the request identity.";
            }
        }
    }
    render();
    try {
        const r = await fetch(request.url, {
            method: request.method,
            headers: {...request.headers, Authorization: `Bearer ${token}`},
            body: request.body,
            signal: AbortSignal.timeout(20000),
        });
        const data = await r.json().catch(() => ({}));
        if (current !== generation) return;
        if (!r.ok)
            throw new Error(
                r.status === 403
                    ? "Insufficient permissions for this operation."
                    : r.status === 401
                        ? "Operator token expired or invalid. Paste a valid token."
                        : data.detail || data.title || `Operation rejected (${r.status}).`,
            );
        result = {data, status: r.status};
    } catch (e) {
        if (current !== generation) return;
        error =
            e.message +
            (request.method === "POST"
                ? " The result may be uncertain; inspect current state or retry with this same request identity."
                : "");
    } finally {
        if (current === generation) {
            busy = false;
            render();
        }
    }
}

function resultView({data, status}) {
    const rows = Array.isArray(data) ? data : [data];
    return `<section class="operator-result">${selected === "reservation-try" && data.state === "TRIED" ? '<div class="related-records"><button data-reservation-phase="confirm">Confirm this reservation →</button><button data-reservation-phase="cancel">Cancel this reservation →</button></div>' : ""}<div class="result-tools"><button id="operation-export">Download result</button>${(data.operationId || data.operation_id) && !settledOperation(data.status) ? `<button id="operation-track" ${busy ? "disabled" : ""}>Track until resolved</button>` : ""}${tracking ? `<button id="operation-stop">Stop tracking (${tracking.count}/30)</button>` : ""}</div><div class="related-records">${resultLinks(
        data,
        selected,
    )
        .map(
            (link, i) =>
                `<button data-related="${i}" ${busy ? "disabled" : ""}>${esc(link.label)} →</button>`,
        )
        .join(
            "",
        )}</div><h3>${status === 202 ? "Accepted · processing asynchronously" : "Service response"}</h3>${data.operationId ? `<p>Operation: <code>${esc(data.operationId)}</code></p><button data-poll-operation>Check operation status →</button>` : ""}${
        !rows.length
            ? "<p>No records found.</p>"
            : rows
                .map(
                    (row, i) =>
                        `<details open><summary>${Array.isArray(data) ? "Record " + (i + 1) : "Details"} ${esc(row.status || row.state || "")}</summary><dl>${Object.entries(
                            row,
                        )
                            .map(
                                ([k, v]) =>
                                    `<div><dt>${esc(k.replaceAll("_", " "))}</dt><dd>${typeof v === "object" ? `<pre>${esc(JSON.stringify(v, null, 2))}</pre>` : esc(v)}</dd></div>`,
                            )
                            .join("")}</dl></details>`,
                )
                .join("")
    }</section>`;
}

function stopTracking() {
    if (tracking?.timer) clearTimeout(tracking.timer);
    tracking = null;
}

function startTracking(id) {
    stopTracking();
    const current = generation;
    const track = {id, count: 0, timer: null};
    tracking = track;
    const tick = async () => {
        if (tracking !== track || current !== generation || !host?.isConnected)
            return;
        if (document.hidden) {
            track.timer = setTimeout(tick, 5000);
            return;
        }
        track.count++;
        try {
            const request = buildRequest(
                actions.find((a) => a.id === "operation"),
                {id},
            );
            const response = await fetch(request.url, {
                headers: {Authorization: `Bearer ${token}`},
                signal: AbortSignal.timeout(15000),
            });
            const data = await response.json();
            if (tracking !== track || current !== generation) return;
            if (!response.ok)
                throw new Error(
                    `Operation tracking failed (${response.status}). Check your token and retry.`,
                );
            result = {data, status: 200};
            error = "";
            if (settledOperation(data.status)) {
                recoveryNotice = `Operation reached ${data.status}. ${data.status === "MANUAL_REVIEW" ? "Reconcile the operation using the recovery tools." : ""}`;
                stopTracking();
            } else if (track.count >= 30) {
                recoveryNotice =
                    "Tracking paused after 30 checks. The operation is still processing. You can resume tracking.";
                stopTracking();
            } else track.timer = setTimeout(tick, 5000);
            render();
        } catch (e) {
            if (tracking !== track || current !== generation) return;
            error = e.message;
            stopTracking();
            render();
        }
    };
    tick();
    render();
}
