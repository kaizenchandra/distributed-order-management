package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

@Component
public class ProviderClient {
    final HttpClient http;
    final String base, key;
    final Duration timeout;
    final Json json;

    public ProviderClient(
            Json json,
            @Value("${PROVIDER_URL:http://payment-provider:8080}") String base,
            @Value("${PROVIDER_KEY}") String key,
            @Value("${oms.provider.timeout-ms:500}") long timeout) {
        this.json = json;
        this.base = base;
        this.key = key;
        this.timeout = Duration.ofMillis(timeout);
        http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    public JsonNode lookup(UUID op) {
        return call("GET", "/internal/operations/" + op, null);
    }

    public JsonNode submit(UUID op, UUID payment, String kind, JsonNode payload) {
        return call(
                "POST",
                "/internal/operations",
                json.obj()
                        .put("operationId", op.toString())
                        .put("paymentId", payment.toString())
                        .put("kind", kind)
                        .set("request", payload));
    }

    public JsonNode lookupMigration(UUID id) {
        return call("GET", "/internal/legacy-balances/" + id, null);
    }

    public JsonNode importMigration(UUID id, JsonNode manifest, String digest) {
        return call(
                "POST",
                "/internal/legacy-balances/" + id,
                json.obj().put("digest", digest).set("manifest", manifest));
    }

    JsonNode call(String method, String path, JsonNode payload) {
        try {
            var request =
                    HttpRequest.newBuilder(URI.create(base + path))
                            .timeout(timeout)
                            .header("X-Provider-Key", key)
                            .header("Content-Type", "application/json")
                            .method(
                                    method,
                                    payload == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofString(json.write(payload)))
                            .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (method.equals("GET") && response.statusCode() == 404) return null;
            if (response.statusCode() != 200)
                throw new Uncertain("Provider returned HTTP " + response.statusCode());
            var result = json.read(response.body());
            if (!result
                    .path("status")
                    .asText()
                    .matches(
                            path.startsWith("/internal/legacy-balances/") ? "IMPORTED" : "SUCCEEDED|REJECTED"))
                throw new Uncertain("Invalid provider outcome");
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Uncertain("Interrupted provider call");
        } catch (java.io.IOException e) {
            throw new Uncertain("Provider transport outcome unknown");
        }
    }

    static class Uncertain extends RuntimeException {
        Uncertain(String reason) {
            super(reason);
        }
    }
}
