import {setSession} from "./session.js";

/** Uses the same-origin Keycloak proxy; never persists passwords or tokens. */
export async function signIn(username, password) {
    let response;
    try {
        response = await fetch("/realms/oms/protocol/openid-connect/token", {
            method: "POST",
            signal: AbortSignal.timeout(15000),
            body: new URLSearchParams({
                grant_type: "password",
                client_id: "oms-demo",
                username,
                password,
            }),
        });
    } catch {
        throw new Error(
            "Cannot reach Keycloak. Check that the authentication service and frontend proxy are running.",
        );
    }
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
        if (response.status === 404)
            throw new Error(
                "The OMS login realm was not found. Check that the frontend points to this project’s Keycloak instance.",
            );
        if (response.status >= 500)
            throw new Error(
                "Keycloak is unavailable. Check the authentication service and try again.",
            );
        if (data.error === "invalid_grant")
            throw new Error(
                data.error_description || "Username or password is incorrect.",
            );
        throw new Error(
            data.error_description ||
            data.error ||
            `Sign-in failed (${response.status}).`,
        );
    }
    if (!data.access_token)
        throw new Error(
            "Keycloak did not return an access token. Check the oms-demo client configuration.",
        );
    setSession(data);
    return data.access_token;
}
