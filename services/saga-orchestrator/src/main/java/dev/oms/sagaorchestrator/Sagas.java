package dev.oms.sagaorchestrator;

import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/sagas")
public class Sagas {
    static final String[] FORWARD = {
            "Inventory.Try",
            "Payment.Authorize",
            "Inventory.Confirm",
            "Payment.Capture",
            "Order.Confirm",
            "Fulfillment.Start"
    };
    static final String[] REVERSE = {
            "Fulfillment.Cancel", "Payment.Reverse", "Inventory.Release", "Order.Cancel"
    };
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;
    final org.springframework.transaction.support.TransactionTemplate tx;

    public Sagas(
            JdbcTemplate d,
            Json j,
            Messaging b,
            org.springframework.transaction.support.TransactionTemplate t) {
        db = d;
        json = j;
        bus = b;
        tx = t;
    }

    static boolean isCurrentReply(Map<String, Object> row, Envelope reply) {
        return Set.of("RUNNING", "MANUAL_REVIEW").contains(row.get("state"))
                && Objects.equals(row.get("current_command"), reply.causationId());
    }

    Map<String, Object> lock(UUID id) {
        return db.queryForMap("select * from saga_instances where id=? for update", id);
    }

    void dispatch(UUID id, Map<String, Object> r) {
        int step = ((Number) r.get("step")).intValue();
        boolean c = (boolean) r.get("compensating");
        String[] actions = c ? REVERSE : FORWARD;
        if (step >= actions.length) {
            db.update(
                    "update saga_instances set state=?,deadline=null,version=version+1 where id=?",
                    c ? "COMPENSATED" : "COMPLETED",
                    id);
            return;
        }
        String action = actions[step];
        UUID cmd = Envelope.id(id + ":" + (c ? "compensate" : "forward") + ":" + step);
        String target =
                switch (action.substring(0, action.indexOf('.'))) {
                    case "Inventory" -> "inventory-service";
                    case "Payment" -> "payment-service";
                    case "Order" -> "order-service";
                    default -> "fulfillment-service";
                };
        bus.emit(
                target,
                bus.message(cmd.toString(), action, id, 0, null, json.read(r.get("payload").toString())));
        // message identity is derived in exactly one place
        UUID messageId = Envelope.id(cmd.toString());
        db.update(
                "update saga_instances set"
                        + " state='RUNNING',current_command=?,attempts=attempts+1,deadline=now()+interval '20"
                        + " seconds',version=version+1 where id=?",
                messageId,
                id);
        db.update(
                "insert into saga_steps(saga_id,command_id,action,outcome) values (?,?,?,'DISPATCHED')",
                id,
                messageId,
                action);
    }

    void compensate(UUID id) {
        db.update(
                "update saga_instances set compensating=true,step=0,attempts=0,state='RUNNING' where id=?",
                id);
        dispatch(id, lock(id));
    }

    @KafkaListener(topics = "oms.saga-orchestrator")
    public void message(String raw) {
        bus.receive(
                raw,
                e -> {
                    UUID id = e.aggregateId();
                    if (e.eventType().equals("Checkout.Start")) {
                        db.update(
                                "insert into saga_instances(id,state,step,payload) values (?,'STARTED',0,?::jsonb)"
                                        + " on conflict do nothing",
                                id,
                                json.write(e.payload()));
                        var r = lock(id);
                        if (r.get("state").equals("STARTED")) dispatch(id, r);
                        return;
                    }
                    var r = lock(id);
                    if (e.eventType().equals("Checkout.Cancel")) {
                        if (r.get("state").equals("COMPLETED")) compensate(id);
                        else db.update("update saga_instances set cancel_requested=true where id=?", id);
                        return;
                    }
                    if (!isCurrentReply(r, e)) return;
                    boolean ok = e.eventType().endsWith(".Ok");
                    boolean c = (boolean) r.get("compensating");
                    db.update(
                            "insert into saga_steps(saga_id,command_id,action,outcome) values (?,?,?,?)",
                            id,
                            e.causationId(),
                            e.eventType(),
                            ok ? "SUCCEEDED" : "REJECTED");
                    if (!ok) {
                        db.update(
                                "update saga_instances set last_error=? where id=?",
                                e.payload().path("reason").asText(),
                                id);
                        if (c) {
                            db.update(
                                    "update saga_instances set state='MANUAL_REVIEW',deadline=null where id=?", id);
                        } else compensate(id);
                        return;
                    }
                    if (!c && (boolean) r.get("cancel_requested")) {
                        compensate(id);
                        return;
                    }
                    db.update("update saga_instances set step=step+1,attempts=0 where id=?", id);
                    dispatch(id, lock(id));
                });
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 2000)
    public void recover() {
        tx.executeWithoutResult(
                s -> {
                    for (var r :
                            db.queryForList(
                                    "select * from saga_instances where state='RUNNING' and deadline<now() limit 32"
                                            + " for update skip locked")) {
                        UUID id = (UUID) r.get("id");
                        if (((Number) r.get("attempts")).intValue() >= 5) {
                            db.update(
                                    "update saga_instances set state='MANUAL_REVIEW',last_error='Ambiguous outcome:"
                                            + " inspect participant before retry or compensation',deadline=null where"
                                            + " id=?",
                                    id);
                            continue;
                        }
                        // Reopen identical outbox message, preserving participant idempotency keys.
                        db.update(
                                "update outbox set published_at=null,next_attempt=now() where event_id=?",
                                r.get("current_command"));
                        db.update(
                                "update saga_instances set attempts=attempts+1,deadline=now()+interval '20"
                                        + " seconds',version=version+1 where id=?",
                                id);
                    }
                });
    }

    @GetMapping("/{id}")
    public Object inspect(@PathVariable UUID id) {
        return db.queryForMap("select * from saga_instances where id=?", id);
    }

    @GetMapping("/{id}/steps")
    public Object steps(@PathVariable UUID id) {
        return db.queryForList("select * from saga_steps where saga_id=? order by id", id);
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public Object retry(@PathVariable UUID id) {
        var r = lock(id);
        if (!r.get("state").equals("MANUAL_REVIEW"))
            throw new IllegalStateException("Requires manual review");
        db.update(
                "update outbox set published_at=null,next_attempt=now() where event_id=?",
                r.get("current_command"));
        db.update(
                "update saga_instances set state='RUNNING',attempts=0,deadline=now()+interval '20 seconds'"
                        + " where id=?",
                id);
        return inspect(id);
    }

    @PostMapping("/{id}/compensate")
    @Transactional
    public Object cancel(@PathVariable UUID id) {
        var r = lock(id);
        if (r.get("state").equals("COMPLETED")) compensate(id);
        else if (r.get("state").equals("RUNNING"))
            db.update("update saga_instances set cancel_requested=true where id=?", id);
        else throw new IllegalStateException("Reconcile ambiguous commands before compensation");
        return inspect(id);
    }
}
