package dev.oms.paymentservice;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class PaymentsTest {
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
    void captureRetryAndRefundLimit() {
        var service = new PaymentLedger(db, json);
        UUID id = UUID.randomUUID(), capture = UUID.randomUUID();
        ObjectNode p = payload(id);
        tx.executeWithoutResult(
                s -> {
                    service.apply(id, UUID.randomUUID(), "Payment.Authorize", p);
                    service.apply(id, capture, "Payment.Capture", p);
                    service.apply(id, capture, "Payment.Capture", p);
                });
        assertEquals(
                100L, db.queryForObject("select captured from payment_intents where id=?", Long.class, id));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                s ->
                                        service.apply(
                                                id,
                                                UUID.randomUUID(),
                                                "Payment.Refund",
                                                p.deepCopy().put("refundAmount", 101))));
        tx.executeWithoutResult(
                s -> {
                    service.apply(id, UUID.randomUUID(), "Payment.Reverse", p);
                    service.apply(id, UUID.randomUUID(), "Payment.Reverse", p);
                });
        assertEquals(1, db.queryForObject("select count(*) from refunds", Integer.class));
    }

    @Test
    void conflictingOperationKeyIsRejected() {
        var service = new PaymentLedger(db, json);
        UUID id = UUID.randomUUID(), key = UUID.randomUUID();
        tx.executeWithoutResult(s -> service.apply(id, key, "Payment.Authorize", payload(id)));
        assertThrows(
                IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> service.apply(id, key, "Payment.Capture", payload(id))));
    }
}
