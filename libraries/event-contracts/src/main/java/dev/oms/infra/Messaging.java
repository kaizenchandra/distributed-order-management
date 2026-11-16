package dev.oms.infra;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class Messaging {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Json json;
    private final KafkaTemplate<String, String> kafka;
    private final String consumer;

    public Messaging(
            JdbcTemplate db,
            TransactionTemplate tx,
            Json json,
            KafkaTemplate<String, String> kafka,
            @Value("${spring.application.name}") String consumer) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.kafka = kafka;
        this.consumer = consumer;
    }

    public void emit(String target, Envelope e) {
        db.update(
                "insert into"
                        + " outbox(event_id,aggregate_id,aggregate_version,event_type,schema_version,topic,payload)"
                        + " values (?,?,?,?,?,?,?::jsonb) on conflict do nothing",
                e.eventId(),
                e.aggregateId(),
                e.aggregateVersion(),
                e.eventType(),
                e.schemaVersion(),
                "oms." + target,
                json.write(e));
    }

    public Envelope message(
            String key,
            String type,
            UUID id,
            long version,
            UUID cause,
            com.fasterxml.jackson.databind.JsonNode payload) {
        return new Envelope(Envelope.id(key), type, id, version, 1, id, cause, Instant.now(), payload);
    }

    public void receive(String raw, Consumer<Envelope> handler) {
        Envelope e = json.envelope(raw);
        tx.executeWithoutResult(
                s -> {
                    if (db.update(
                            "insert into inbox(consumer_name,event_id) values (?,?) on conflict do nothing",
                            consumer,
                            e.eventId())
                            == 1) handler.accept(e);
                });
    }

    // Short bounded transaction holds row locks until Kafka acknowledgment. A crash after
    // acknowledgment still duplicates.
    @Scheduled(fixedDelayString = "${oms.relay-delay:250}")
    public void relay() {
        try {
            tx.executeWithoutResult(
                    s -> {
                        var rows =
                                db.queryForList(
                                        "select * from outbox where published_at is null and next_attempt<=now() order"
                                                + " by created_at limit 32 for update skip locked");
                        // Queue a bounded batch before awaiting acknowledgments. An ack deadline and producer
                        // max.block.ms bound network waiting.
                        var futures = new ArrayList<java.util.concurrent.CompletableFuture<?>>();
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                        for (var row : rows) {
                            if (System.nanoTime() >= deadline) {
                                futures.add(
                                        java.util.concurrent.CompletableFuture.failedFuture(
                                                new java.util.concurrent.TimeoutException("Batch enqueue deadline")));
                                continue;
                            }
                            try {
                                futures.add(
                                        kafka.send(
                                                (String) row.get("topic"),
                                                row.get("aggregate_id").toString(),
                                                row.get("payload").toString()));
                            } catch (Exception failure) {
                                futures.add(java.util.concurrent.CompletableFuture.failedFuture(failure));
                            }
                        }
                        for (int i = 0; i < rows.size(); i++) {
                            var row = rows.get(i);
                            boolean acknowledged = false;
                            try {
                                var future = futures.get(i);
                                if (future.isDone()) future.get();
                                else future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                                acknowledged = true;
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            } catch (Exception failed) {
                                /* Retain publication intent; an acknowledgment gap may duplicate. */
                            }
                            if (acknowledged)
                                db.update(
                                        "update outbox set published_at=clock_timestamp(),attempts=attempts+1 where"
                                                + " event_id=?",
                                        row.get("event_id"));
                            else
                                db.update(
                                        "update outbox set attempts=attempts+1,next_attempt=clock_timestamp()+interval"
                                                + " '10 seconds' where event_id=?",
                                        row.get("event_id"));
                        }
                    });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Outbox relay unavailable: {}", e.getClass().getSimpleName());
        }
    }

    public void reply(Envelope command, boolean ok, String reason) {
        var p = command.payload().deepCopy();
        if (p instanceof com.fasterxml.jackson.databind.node.ObjectNode o) o.put("reason", reason);
        emit(
                "saga-orchestrator",
                message(
                        command.eventId() + ":reply",
                        command.eventType() + (ok ? ".Ok" : ".Rejected"),
                        command.aggregateId(),
                        0,
                        command.eventId(),
                        p));
    }
}
