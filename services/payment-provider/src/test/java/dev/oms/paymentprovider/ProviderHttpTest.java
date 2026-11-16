package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oms.testsupport.TestPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderHttpTest {
    static TestPostgres pg = new TestPostgres();

    static {
        pg.start();
    }

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final ObjectMapper json = new ObjectMapper();
    @LocalServerPort
    int port;

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.username", pg::getUsername);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("PROVIDER_KEY", () -> "test-provider-key");
    }

    HttpResponse<String> call(String path, String body, boolean authorized) throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json");
        if (authorized) b.header("X-Provider-Key", "test-provider-key");
        return http.send(
                b.method(
                                body == null ? "GET" : "POST",
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void fractionalMinorUnitsAreRejectedWithoutAnOperation() throws Exception {
        UUID operation = UUID.randomUUID();
        String body =
                json.createObjectNode()
                        .put("operationId", operation.toString())
                        .put("paymentId", UUID.randomUUID().toString())
                        .put("kind", "Payment.Authorize")
                        .set(
                                "request",
                                json.createObjectNode()
                                        .put("amount", new java.math.BigDecimal("1.25"))
                                        .put("currency", "USD"))
                        .toString();
        assertEquals(400, call("/internal/operations", body, true).statusCode());
        assertEquals(404, call("/internal/operations/" + operation, null, true).statusCode());
    }

    @Test
    void authenticationAndDefinitiveAbsencePreserveHttpStatus() throws Exception {
        String path = "/internal/operations/" + UUID.randomUUID();
        assertEquals(403, call(path, null, false).statusCode());
        assertEquals(404, call(path, null, true).statusCode());
        assertEquals(200, call("/actuator/health", null, false).statusCode());
    }

    @Test
    void committedButHiddenCaptureIs503UntilRevealed() throws Exception {
        UUID payment = UUID.randomUUID(),
                authorization = UUID.randomUUID(),
                capture = UUID.randomUUID();
        String p = "{\"amount\":100,\"currency\":\"USD\",\"paymentScenario\":\"capture-unknown\"}";
        String prefix = "{\"paymentId\":\"" + payment + "\",\"request\":" + p;
        assertEquals(
                200,
                call(
                        "/internal/operations",
                        prefix
                                + ",\"operationId\":\""
                                + authorization
                                + "\",\"kind\":\"Payment.Authorize\"}",
                        true)
                        .statusCode());
        assertEquals(
                503,
                call(
                        "/internal/operations",
                        prefix + ",\"operationId\":\"" + capture + "\",\"kind\":\"Payment.Capture\"}",
                        true)
                        .statusCode());
        assertEquals(503, call("/internal/operations/" + capture, null, true).statusCode());
        assertEquals(200, call("/internal/operations/" + capture + "/reveal", "{}", true).statusCode());
        var result = call("/internal/operations/" + capture, null, true);
        assertEquals(200, result.statusCode());
        assertEquals(100, json.readTree(result.body()).path("captured").asLong());
    }
}
