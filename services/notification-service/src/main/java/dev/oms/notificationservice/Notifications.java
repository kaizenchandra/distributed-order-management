package dev.oms.notificationservice;

import dev.oms.infra.Messaging;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class Notifications {
    final JdbcTemplate db;
    final Messaging bus;

    public Notifications(JdbcTemplate d, Messaging b) {
        db = d;
        bus = b;
    }

    @KafkaListener(topics = "oms.order-events")
    public void event(String raw) {
        bus.receive(
                raw,
                e ->
                        db.update(
                                "insert into notifications(id,order_id,kind) values (?,?,?) on conflict do nothing",
                                e.eventId(),
                                e.aggregateId(),
                                e.eventType()));
    }
}
