package dev.oms.apigateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@RestController
public class Gateway {
    final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    final Semaphore bulkhead = new Semaphore(64);
    final ConcurrentHashMap<String, Window> limits = new ConcurrentHashMap<>();

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest req, @AuthenticationPrincipal Jwt jwt)
            throws Exception {
        if (limits.size() > 10000)
            limits.entrySet().removeIf(e -> e.getValue().second() < Instant.now().getEpochSecond() - 60);
        long now = Instant.now().getEpochSecond();
        Window w =
                limits.compute(
                        jwt.getSubject(),
                        (k, v) ->
                                v == null || v.second() != now
                                        ? new Window(now, 1)
                                        : new Window(now, v.count() + 1));
        if (w.count() > 50 || !bulkhead.tryAcquire())
            return ResponseEntity.status(429).header("Retry-After", "1").build();
        try {
            byte[] body = req.getInputStream().readNBytes(65537);
            if (body.length > 65536) return ResponseEntity.status(413).build();
            String path = req.getRequestURI();
            String service =
                    path.startsWith("/api/v1/orders")
                            ? "order-service"
                            : path.startsWith("/api/v1/query/")
                            ? "order-query-service"
                            : path.startsWith("/api/v1/catalog/") ? "catalog-service" : null;
            if (service == null) return ResponseEntity.notFound().build();
            String base =
                    System.getenv()
                            .getOrDefault(
                                    service.toUpperCase().replace('-', '_') + "_URL", "http://" + service + ":8080");
            var b =
                    java.net.http.HttpRequest.newBuilder(
                                    URI.create(
                                            base
                                                    + path
                                                    + (req.getQueryString() == null ? "" : "?" + req.getQueryString())))
                            .timeout(Duration.ofSeconds(8))
                            .header("Authorization", req.getHeader("Authorization"))
                            .header("Content-Type", "application/json")
                            .header("X-Correlation-ID", UUID.randomUUID().toString());
            String key = req.getHeader("Idempotency-Key");
            if (key != null) b.header("Idempotency-Key", key);
            var response =
                    client.send(
                            b.method(req.getMethod(), java.net.http.HttpRequest.BodyPublishers.ofByteArray(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(response.statusCode())
                    .header("Content-Type", "application/json")
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Cache-Control", "no-store")
                    .body(response.body());
        } finally {
            bulkhead.release();
        }
    }

    record Window(long second, int count) {
    }
}
