package dev.oms.orderqueryservice;

import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class Queries {
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;

    public Queries(JdbcTemplate d, Json j, Messaging b) {
        db = d;
        json = j;
        bus = b;
    }

    void project(Envelope e) {
        UUID id = e.aggregateId();
        db.update(
                "insert into order_read_models(order_id,customer_id) values (?,?) on conflict do nothing",
                id,
                e.payload().path("customerId").asText());
        var row = db.queryForMap("select * from order_read_models where order_id=? for update", id);
        db.update(
                "insert into projection_events values (?,?,?::jsonb) on conflict do nothing",
                id,
                e.aggregateVersion(),
                json.write(e));
        long version = ((Number) row.get("version")).longValue();
        while (true) {
            var next =
                    db.queryForList(
                            "select event from projection_events where order_id=? and version=?",
                            id,
                            version + 1);
            if (next.isEmpty()) break;
            Envelope n = json.envelope(next.getFirst().get("event").toString());
            db.update(
                    "update order_read_models set state=?::jsonb,version=? where order_id=?",
                    json.write(n.payload()),
                    n.aggregateVersion(),
                    id);
            db.update(
                    "insert into order_timeline values (?,?,?,?) on conflict do nothing",
                    id,
                    n.aggregateVersion(),
                    n.eventType(),
                    java.sql.Timestamp.from(n.timestamp()));
            version++;
        }
    }

    @KafkaListener(topics = "oms.order-events")
    public void event(String raw) {
        bus.receive(raw, this::project);
    }

    @GetMapping("/query/orders/{id}")
    public Object get(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") long minVersion) {
        var rows =
                db.queryForList(
                        "select * from order_read_models where order_id=? and customer_id=?",
                        id,
                        jwt.getSubject());
        if (rows.isEmpty())
            return org.springframework.http.ResponseEntity.status(404)
                    .body(
                            Map.of(
                                    "detail", "Projection unavailable or order not found", "retryAfterSeconds", 1));
        var row = rows.getFirst();
        if (((Number) row.get("version")).longValue() < minVersion)
            return org.springframework.http.ResponseEntity.status(202)
                    .header("Retry-After", "1")
                    .body(Map.of("version", row.get("version"), "detail", "Projection catching up"));
        return json.read(row.get("state").toString()).deepCopy().toString();
    }

    @GetMapping("/query/customers/me/orders")
    public Object list(
            @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int offset) {
        return db.queryForList(
                "select order_id,version,state from order_read_models where customer_id=? order by order_id"
                        + " limit 50 offset ?",
                jwt.getSubject(),
                Math.max(0, offset));
    }

    @GetMapping("/query/orders/{id}/timeline")
    public Object timeline(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return db.queryForList(
                "select t.* from order_timeline t join order_read_models r on t.order_id=r.order_id where"
                        + " t.order_id=? and r.customer_id=? order by t.version",
                id,
                jwt.getSubject());
    }

    // Rebuild from immutable buffered event history; locks block concurrent handlers until this
    // transaction commits.
    @PostMapping("/admin/projections/rebuild")
    @Transactional
    public Object rebuild() {
        db.execute("lock table order_read_models in exclusive mode");
        long count = db.queryForObject("select count(*) from projection_events", Long.class);
        db.update("delete from order_timeline");
        db.update("delete from order_read_models");
        // Integration payloads contain the complete order state. Select only the contiguous prefix;
        // row_number cannot catch up after a gap because (order_id,version) is unique. This avoids
        // reissuing several SQL statements per historical event while preserving gap semantics.
        db.update(
                """
                        with ranked as (
                          select *,row_number() over(partition by order_id order by version) position
                          from projection_events
                        ), latest as (
                          select distinct on(order_id) order_id,version,event
                          from ranked where version=position order by order_id,version desc
                        )
                        insert into order_read_models(order_id,customer_id,version,state)
                        select distinct on(e.order_id) e.order_id,e.event#>>'{payload,customerId}',
                               coalesce(l.version,0),coalesce(l.event->'payload','{}'::jsonb)
                        from projection_events e left join latest l using(order_id)
                        order by e.order_id,e.version
                        """);
        db.update(
                """
                        insert into order_timeline(order_id,version,type,occurred_at)
                        select order_id,version,event->>'eventType',
                          case when jsonb_typeof(event->'timestamp')='number'
                            then to_timestamp((event->>'timestamp')::double precision)
                            else (event->>'timestamp')::timestamptz end
                        from (
                          select *,row_number() over(partition by order_id order by version) position
                          from projection_events
                        ) ranked where version=position
                        """);
        return Map.of("eventsReplayed", count);
    }
}
