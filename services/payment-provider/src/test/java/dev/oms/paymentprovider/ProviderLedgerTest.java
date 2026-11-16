package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Json;
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

class ProviderLedgerTest {
    static TestPostgres pg = new TestPostgres();
    JdbcTemplate db;
    TransactionTemplate tx;
    Json json;

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
    void operationReplayAndRefundBudgetAreDurable() {
        var ledger = new ProviderLedger(db, json);
        UUID payment = UUID.randomUUID(),
                auth = UUID.randomUUID(),
                capture = UUID.randomUUID(),
                refund = UUID.randomUUID();
        var p = payload(payment);
        tx.executeWithoutResult(s -> ledger.execute(auth, payment, "Payment.Authorize", p));
        tx.executeWithoutResult(
                s -> {
                    var one = ledger.execute(capture, payment, "Payment.Capture", p);
                    var replay = ledger.execute(capture, payment, "Payment.Capture", p);
                    assertEquals(one.result(), replay.result());
                    assertFalse(replay.fresh());
                });
        tx.executeWithoutResult(s -> ledger.execute(refund, payment, "Payment.Refund", p));
        var rejected = tx.execute(s -> ledger.execute(UUID.randomUUID(), payment, "Payment.Refund", p));
        assertEquals("REJECTED", rejected.result().path("status").asText());
        assertEquals(
                100L,
                db.queryForObject(
                        "select refunded from provider_accounts where id=?", Long.class, payment));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> tx.executeWithoutResult(s -> ledger.execute(capture, payment, "Payment.Void", p)));
    }
}
