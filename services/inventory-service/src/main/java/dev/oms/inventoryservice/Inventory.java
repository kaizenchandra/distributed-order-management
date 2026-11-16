package dev.oms.inventoryservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/inventory")
public class Inventory {
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;
    final org.springframework.transaction.support.TransactionTemplate tx;

    public Inventory(
            JdbcTemplate d,
            Json j,
            Messaging b,
            org.springframework.transaction.support.TransactionTemplate t) {
        db = d;
        json = j;
        bus = b;
        tx = t;
    }

    @GetMapping("/{sku}")
    public Object get(@PathVariable String sku) {
        return db.queryForMap(
                "select *,on_hand-reserved-allocated as available from inventory_items where sku=?", sku);
    }

    @PostMapping("/reservations/try")
    @Transactional
    public Object attempt(@RequestBody ObjectNode p) {
        return apply(UUID.fromString(p.path("reservationId").asText()), "Inventory.Try", p);
    }

    @PostMapping("/reservations/{id}/{action}")
    @Transactional
    public Object decide(
            @PathVariable UUID id, @PathVariable String action, @RequestBody ObjectNode p) {
        return apply(
                id,
                switch (action) {
                    case "confirm" -> "Inventory.Confirm";
                    case "cancel" -> "Inventory.Cancel";
                    default -> throw new IllegalArgumentException("Unknown action");
                },
                p);
    }

    Object apply(UUID id, String action, JsonNode p) {
        UUID owner = UUID.fromString(p.path("orderId").asText());
        db.update(
                "insert into inventory_reservations values (?,?,'NEW',?::jsonb,now()+interval '120"
                        + " seconds',0) on conflict do nothing",
                id,
                owner,
                json.write(p.path("items")));
        var row = db.queryForMap("select * from inventory_reservations where id=? for update", id);
        if (!owner.equals(row.get("owner_id")))
            throw new IllegalStateException("Reservation ownership mismatch");
        String state = row.get("state").toString();
        JsonNode items = json.read(row.get("items").toString());
        String next;
        switch (action) {
            case "Inventory.Try":
                if (!items.equals(p.path("items")))
                    throw new IllegalStateException("Reservation payload mismatch");
                if (state.equals("TRIED") || state.equals("CONFIRMED")) return row;
                if (!state.equals("NEW")) throw new IllegalStateException("Reservation terminal");
                next = "TRIED";
                break;
            case "Inventory.Confirm":
                if (state.equals("CONFIRMED")) return row;
                if (!state.equals("TRIED")
                        || ((java.sql.Timestamp) row.get("expires_at")).toInstant().isBefore(Instant.now()))
                    throw new IllegalStateException("Reservation not confirmable");
                next = "CONFIRMED";
                break;
            case "Inventory.Cancel":
                if (Set.of("CANCELLED", "EXPIRED", "RELEASED").contains(state)) return row;
                if (state.equals("CONFIRMED"))
                    throw new IllegalStateException("Use allocation release after confirmation");
                next = "CANCELLED";
                break;
            case "Inventory.Release":
                if (state.equals("RELEASED") || state.equals("CANCELLED") || state.equals("EXPIRED"))
                    return row;
                next = state.equals("CONFIRMED") ? "RELEASED" : "CANCELLED";
                break;
            default:
                throw new IllegalArgumentException("Unknown inventory operation");
        }
        var sorted = new TreeMap<String, Long>();
        if (items.isArray())
            for (var item : items) {
                long q = item.path("quantity").asLong();
                if (q <= 0 || q > 1000) throw new IllegalArgumentException("Invalid quantity");
                sorted.merge(item.path("sku").asText(), q, Math::addExact);
            }
        if (action.equals("Inventory.Try") && sorted.isEmpty())
            throw new IllegalArgumentException("Empty reservation");
        for (var item : sorted.entrySet()) {
            long q = item.getValue();
            int n = 1;
            if (next.equals("TRIED"))
                n =
                        db.update(
                                "update inventory_items set reserved=reserved+?,version=version+1 where sku=? and"
                                        + " on_hand-reserved-allocated>=?",
                                q,
                                item.getKey(),
                                q);
            else if (next.equals("CONFIRMED"))
                n =
                        db.update(
                                "update inventory_items set"
                                        + " reserved=reserved-?,allocated=allocated+?,version=version+1 where sku=?",
                                q,
                                q,
                                item.getKey());
            else if (state.equals("TRIED"))
                n =
                        db.update(
                                "update inventory_items set reserved=reserved-?,version=version+1 where sku=?",
                                q,
                                item.getKey());
            else if (state.equals("CONFIRMED"))
                n =
                        db.update(
                                "update inventory_items set allocated=allocated-?,version=version+1 where sku=?",
                                q,
                                item.getKey());
            if (n != 1) throw new IllegalStateException("Insufficient inventory");
            db.update(
                    "insert into inventory_movements(reservation_id,sku,quantity,kind) values (?,?,?,?)",
                    id,
                    item.getKey(),
                    q,
                    next);
        }
        db.update("update inventory_reservations set state=?,version=version+1 where id=?", next, id);
        return Map.of("id", id, "state", next);
    }

    @KafkaListener(topics = "oms.inventory-service")
    public void command(String raw) {
        try {
            bus.receive(
                    raw,
                    e -> {
                        apply(e.aggregateId(), e.eventType(), e.payload());
                        bus.reply(e, true, "");
                    });
        } catch (IllegalStateException | IllegalArgumentException failure) {
            bus.receive(raw, e -> bus.reply(e, false, failure.getMessage()));
        }
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    public void expire() {
        tx.executeWithoutResult(
                s -> {
                    for (var row :
                            db.queryForList(
                                    "select * from inventory_reservations where state='TRIED' and expires_at<now()"
                                            + " limit 50 for update skip locked")) {
                        UUID id = (UUID) row.get("id");
                        var p = json.obj().put("orderId", row.get("owner_id").toString());
                        apply(id, "Inventory.Cancel", p);
                        db.update("update inventory_reservations set state='EXPIRED' where id=?", id);
                    }
                });
    }
}
