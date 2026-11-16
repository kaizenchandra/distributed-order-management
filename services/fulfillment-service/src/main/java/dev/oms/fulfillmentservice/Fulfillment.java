package dev.oms.fulfillmentservice;

import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fulfillments")
public class Fulfillment {
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;

    public Fulfillment(JdbcTemplate d, Json j, Messaging b) {
        db = d;
        json = j;
        bus = b;
    }

    void apply(Envelope e) {
        db.update(
                "insert into fulfillment_requests values (?,?,'NEW',?::jsonb) on conflict do nothing",
                e.aggregateId(),
                e.payload().path("customerId").asText(),
                json.write(e.payload()));
        var r =
                db.queryForMap("select * from fulfillment_requests where id=? for update", e.aggregateId());
        String state = r.get("state").toString();
        if (e.eventType().equals("Fulfillment.Start")) {
            if (state.equals("CANCELLED")) throw new IllegalStateException("Cancelled fulfillment");
            db.update(
                    "update fulfillment_requests set state='PREPARING' where id=? and state='NEW'",
                    e.aggregateId());
            db.update(
                    "insert into shipments values (?,?,'PREPARING',null) on conflict do nothing",
                    e.aggregateId(),
                    e.aggregateId());
        } else if (e.eventType().equals("Fulfillment.Cancel")) {
            if (state.equals("SHIPPED")) throw new IllegalStateException("Return required after handoff");
            db.update("update fulfillment_requests set state='CANCELLED' where id=?", e.aggregateId());
            db.update("update shipments set state='CANCELLED' where fulfillment_id=?", e.aggregateId());
        } else throw new IllegalArgumentException("Unknown fulfillment command");
    }

    @PostMapping("/{id}/handoff")
    @Transactional
    public Object handoff(@PathVariable UUID id) {
        var r = db.queryForMap("select * from fulfillment_requests where id=? for update", id);
        if (!r.get("state").equals("PREPARING")) throw new IllegalStateException("Cannot handoff");
        db.update("update fulfillment_requests set state='SHIPPED' where id=?", id);
        db.update(
                "update shipments set state='SHIPPED',carrier_ref=? where fulfillment_id=?",
                "LOCAL-" + id,
                id);
        return Map.of("state", "SHIPPED");
    }

    @KafkaListener(topics = "oms.fulfillment-service")
    public void command(String raw) {
        try {
            bus.receive(
                    raw,
                    e -> {
                        apply(e);
                        bus.reply(e, true, "");
                    });
        } catch (IllegalStateException failure) {
            bus.receive(raw, e -> bus.reply(e, false, failure.getMessage()));
        }
    }
}
