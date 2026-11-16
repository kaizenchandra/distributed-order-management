package dev.oms.orderservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Orders {
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;
    final org.springframework.web.client.RestClient catalog;

    public Orders(
            JdbcTemplate db,
            Json json,
            Messaging bus,
            @org.springframework.beans.factory.annotation.Value(
                    "${CATALOG_URL:http://catalog-service:8080}")
            String url) {
        this.db = db;
        this.json = json;
        this.bus = bus;
        var f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(3));
        catalog =
                org.springframework.web.client.RestClient.builder().baseUrl(url).requestFactory(f).build();
    }

    public Order load(UUID id) {
        Order a = new Order(0, null);
        for (var e :
                db.queryForList(
                        "select * from order_events where aggregate_id=? order by aggregate_version", id))
            a =
                    a.evolve(
                            e.get("event_type").toString(), (ObjectNode) json.read(e.get("payload").toString()));
        if (a.state() == null) throw new IllegalArgumentException("Unknown order");
        return a;
    }

    void append(UUID id, Order old, String type, ObjectNode state, UUID cause) {
        Order next = old.evolve(type, state);
        UUID eid = Envelope.id(id + ":" + next.version());
        db.update(
                "insert into"
                        + " order_events(event_id,aggregate_id,aggregate_version,event_type,payload,correlation_id,causation_id)"
                        + " values (?,?,?,?,?::jsonb,?,?)",
                eid,
                id,
                next.version(),
                type,
                json.write(state),
                id,
                cause);
        bus.emit(
                "order-events",
                bus.message(id + ":" + next.version(), type, id, next.version(), cause, state));
    }

    @PostMapping("/orders")
    @Transactional
    public org.springframework.http.ResponseEntity<JsonNode> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody ObjectNode request) {
        if (key.isBlank() || key.length() > 128)
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        UUID id = UUID.randomUUID();
        int inserted =
                db.update(
                        "insert into order_idempotency values (?,?,?::jsonb,?) on conflict do nothing",
                        jwt.getSubject(),
                        key,
                        json.write(request),
                        id);
        var previous =
                db.queryForMap(
                        "select * from order_idempotency where customer_id=? and key=?", jwt.getSubject(), key);
        if (!json.read(previous.get("request").toString()).equals(request))
            throw new IllegalStateException("Idempotency key payload mismatch");
        id = (UUID) previous.get("order_id");
        if (inserted == 1) {
            JsonNode quote =
                    catalog
                            .post()
                            .uri("/api/v1/catalog/quotes")
                            .header("Authorization", "Bearer " + jwt.getTokenValue())
                            .body(request)
                            .retrieve()
                            .body(JsonNode.class);
            ObjectNode state = (ObjectNode) quote;
            state
                    .put("customerId", jwt.getSubject())
                    .put("orderId", id.toString())
                    .put("status", "PENDING");
            append(id, new Order(0, null), "OrderCreated", state, null);
            bus.emit(
                    "saga-orchestrator", bus.message(id + ":start", "Checkout.Start", id, 1, null, state));
        }
        Order a = load(id);
        return org.springframework.http.ResponseEntity.accepted()
                .body(a.state().deepCopy().put("version", a.version()));
    }

    @GetMapping("/orders/{id}")
    public JsonNode get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Order a = load(id);
        owner(a, jwt);
        return a.state().deepCopy().put("version", a.version());
    }

    void owner(Order a, Jwt jwt) {
        if (!a.state().path("customerId").asText().equals(jwt.getSubject()))
            throw new org.springframework.security.access.AccessDeniedException("Order ownership");
    }

    @GetMapping("/orders")
    public Object history(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int offset) {
        return db.queryForList(
                "select distinct aggregate_id from order_events where payload->>'customerId'=? order by"
                        + " aggregate_id limit 50 offset ?",
                jwt.getSubject(),
                Math.max(0, offset));
    }

    @PostMapping("/orders/{id}/cancel")
    @Transactional
    public Object cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Order a = load(id);
        owner(a, jwt);
        bus.emit(
                "saga-orchestrator",
                bus.message(id + ":cancel-request", "Checkout.Cancel", id, a.version(), null, a.state()));
        return Map.of("orderId", id, "status", "CANCELLATION_REQUESTED");
    }

    @GetMapping("/admin/orders/{id}/events")
    public Object events(@PathVariable UUID id) {
        return db.queryForList(
                "select * from order_events where aggregate_id=? order by aggregate_version", id);
    }

    @KafkaListener(topics = "oms.order-service")
    public void command(String raw) {
        bus.receive(
                raw,
                e -> {
                    Order a = load(e.aggregateId());
                    String type =
                            switch (e.eventType()) {
                                case "Order.Confirm" -> "OrderConfirmed";
                                case "Order.Cancel" -> "OrderCancelled";
                                default -> throw new IllegalArgumentException("Unknown command");
                            };
                    String status = type.equals("OrderConfirmed") ? "CONFIRMED" : "CANCELLED";
                    if (!a.state().path("status").asText().equals(status))
                        append(
                                e.aggregateId(), a, type, a.state().deepCopy().put("status", status), e.eventId());
                    bus.reply(e, true, "");
                });
    }
}
