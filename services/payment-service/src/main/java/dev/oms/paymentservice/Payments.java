package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Messaging;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class Payments {
    final JdbcTemplate db;
    final Messaging bus;
    final PaymentOperations operations;

    public Payments(JdbcTemplate db, Messaging bus, PaymentOperations operations) {
        this.db = db;
        this.bus = bus;
        this.operations = operations;
    }

    @GetMapping("/api/v1/payments/{id}")
    public Object get(@PathVariable UUID id) {
        return db.queryForMap("select * from payment_intents where id=?", id);
    }

    @GetMapping("/api/v1/payments/operations/{id}")
    public Object operation(@PathVariable UUID id) {
        return db.queryForMap(
                "select operation_id,payment_id,kind,status,attempts,last_error,provider_result from"
                        + " provider_operations where operation_id=?",
                id);
    }

    @PostMapping("/api/v1/payments/authorize")
    @Transactional
    public Object authorize(@RequestBody ObjectNode p, @RequestHeader("Idempotency-Key") UUID key) {
        return accepted(
                operations.enqueue(
                        UUID.fromString(p.path("orderId").asText()), key, "Payment.Authorize", p, null));
    }

    @PostMapping("/api/v1/payments/{id}/{action}")
    @Transactional
    public Object mutate(
            @PathVariable UUID id,
            @PathVariable String action,
            @RequestHeader("Idempotency-Key") UUID key,
            @RequestBody ObjectNode p) {
        return accepted(
                operations.enqueue(
                        id,
                        key,
                        switch (action) {
                            case "capture" -> "Payment.Capture";
                            case "void" -> "Payment.Void";
                            case "refunds" -> "Payment.Refund";
                            default -> throw new IllegalArgumentException("Unknown operation");
                        },
                        p,
                        null));
    }

    Object accepted(Map<String, Object> row) {
        return ResponseEntity.accepted()
                .body(Map.of("operationId", row.get("operation_id"), "status", row.get("status")));
    }

    @GetMapping("/api/v1/admin/payments/{id}/operations")
    public Object operations(@PathVariable UUID id) {
        return db.queryForList(
                "select operation_id,kind,status,attempts,last_error from provider_operations where"
                        + " payment_id=? order by sequence",
                id);
    }

    @PostMapping("/api/v1/admin/payments/operations/{id}/retry")
    @Transactional
    public Object retry(@PathVariable UUID id) {
        operations.retry(id);
        return operation(id);
    }

    @KafkaListener(topics = "oms.payment-service")
    public void command(String raw) {
        bus.receive(
                raw, e -> operations.enqueue(e.aggregateId(), e.eventId(), e.eventType(), e.payload(), e));
    }
}
