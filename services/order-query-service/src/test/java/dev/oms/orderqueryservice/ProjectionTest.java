package dev.oms.orderqueryservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import dev.oms.testsupport.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ProjectionTest {
    static TestPostgres pg = new TestPostgres();
    JdbcTemplate db;
    TransactionTemplate tx;
    Json json;
    Messaging bus;

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @BeforeEach
    void init() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        json = new Json(new ObjectMapper().findAndRegisterModules());
        bus = mock(Messaging.class);
        Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).load().migrate();
    }

    ObjectNode payload(UUID id) {
        var p =
                json.obj()
                        .put("orderId", id.toString())
                        .put("customerId", "alice")
                        .put("amount", 100)
                        .put("currency", "USD");
        p.putArray("items").addObject().put("sku", "SKU-1").put("quantity", 1);
        return p;
    }

    @Test
    void outOfOrderDuplicateAndRebuild() {
        var q = new Queries(db, json, bus);
        UUID id = UUID.randomUUID();
        var one =
                new Envelope(
                        UUID.randomUUID(),
                        "OrderCreated",
                        id,
                        1,
                        1,
                        id,
                        null,
                        java.time.Instant.now(),
                        payload(id).put("status", "PENDING"));
        var two =
                new Envelope(
                        UUID.randomUUID(),
                        "OrderConfirmed",
                        id,
                        2,
                        1,
                        id,
                        null,
                        java.time.Instant.now(),
                        payload(id).put("status", "CONFIRMED"));
        tx.executeWithoutResult(
                s -> {
                    q.project(two);
                    assertEquals(0L, db.queryForObject("select version from order_read_models", Long.class));
                    q.project(one);
                    q.project(one);
                });
        assertEquals(2L, db.queryForObject("select version from order_read_models", Long.class));
        // Historical numeric epoch-seconds and the current ISO timestamp must both rebuild.
        db.update(
                "update projection_events set event=jsonb_set(event,'{timestamp}',to_jsonb(?::text)) where"
                        + " version=1",
                one.timestamp().toString());
        tx.executeWithoutResult(s -> q.rebuild());
        assertEquals(2L, db.queryForObject("select version from order_read_models", Long.class));
        assertEquals(2, db.queryForObject("select count(*) from order_timeline", Integer.class));
        assertEquals(
                one.timestamp().getEpochSecond(),
                db.queryForObject(
                                "select occurred_at from order_timeline where version=1", java.sql.Timestamp.class)
                        .toInstant()
                        .getEpochSecond());
    }

    @Test
    void rebuildDoesNotCrossGapsOrRepeatPublication() {
        var q = new Queries(db, json, bus);
        UUID id = UUID.randomUUID();
        for (int version : new int[]{1, 3, 4}) {
            var e =
                    new Envelope(
                            UUID.randomUUID(),
                            "OrderConfirmed",
                            id,
                            version,
                            1,
                            id,
                            null,
                            java.time.Instant.now(),
                            payload(id).put("status", version == 1 ? "PENDING" : "CONFIRMED"));
            tx.executeWithoutResult(s -> q.project(e));
        }
        UUID missingFirst = UUID.randomUUID();
        var delayed =
                new Envelope(
                        UUID.randomUUID(),
                        "OrderConfirmed",
                        missingFirst,
                        2,
                        1,
                        missingFirst,
                        null,
                        java.time.Instant.now(),
                        payload(missingFirst).put("status", "CONFIRMED"));
        tx.executeWithoutResult(s -> q.project(delayed));
        tx.executeWithoutResult(s -> q.rebuild());
        assertEquals(
                1L,
                db.queryForObject(
                        "select version from order_read_models where order_id=?", Long.class, id));
        assertEquals(
                "PENDING",
                db.queryForObject(
                        "select state->>'status' from order_read_models where order_id=?", String.class, id));
        assertEquals(
                0L,
                db.queryForObject(
                        "select version from order_read_models where order_id=?", Long.class, missingFirst));
        assertEquals(1, db.queryForObject("select count(*) from order_timeline", Integer.class));
        assertEquals(4, db.queryForObject("select count(*) from projection_events", Integer.class));
        verifyNoInteractions(bus);
    }
}
