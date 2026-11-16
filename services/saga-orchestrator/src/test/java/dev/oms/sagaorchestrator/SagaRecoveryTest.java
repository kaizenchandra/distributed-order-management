package dev.oms.sagaorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SagaRecoveryTest {
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
    void deadlineRecoveryPreservesIdentityAndEscalates() {
        UUID id = UUID.randomUUID(), command = UUID.randomUUID();
        db.update(
                "insert into saga_instances(id,state,step,payload,current_command,deadline) values"
                        + " (?,'RUNNING',3,?::jsonb,?,now()-interval '1 second')",
                id,
                json.write(payload(id)),
                command);
        db.update(
                "insert into"
                        + " outbox(event_id,aggregate_id,aggregate_version,event_type,schema_version,topic,payload,published_at)"
                        + " values (?,?,0,'Payment.Capture',1,'oms.payment-service','{}',now())",
                command,
                id);
        var saga = new Sagas(db, json, bus, tx);
        saga.recover();
        assertEquals(
                command, db.queryForObject("select current_command from saga_instances", UUID.class));
        assertNull(db.queryForObject("select published_at from outbox", java.sql.Timestamp.class));
        db.update("update saga_instances set attempts=5,deadline=now()-interval '1 second'");
        saga.recover();
        assertEquals(
                "MANUAL_REVIEW", db.queryForObject("select state from saga_instances", String.class));
        assertFalse(db.queryForObject("select compensating from saga_instances", Boolean.class));
    }
}
