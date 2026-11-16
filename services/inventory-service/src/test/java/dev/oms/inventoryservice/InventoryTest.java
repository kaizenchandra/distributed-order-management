package dev.oms.inventoryservice;

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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class InventoryTest {
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
    void lastUnitCannotOversell() throws Exception {
        db.update("update inventory_items set on_hand=1 where sku='SKU-1'");
        var i = new Inventory(db, json, bus, tx);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int n = 0; n < 12; n++)
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    UUID id = UUID.randomUUID();
                                    try {
                                        tx.executeWithoutResult(s -> i.apply(id, "Inventory.Try", payload(id)));
                                        return true;
                                    } catch (IllegalStateException e) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int successes = 0;
            for (var f : tasks) if (f.get()) successes++;
            assertEquals(1, successes);
            assertEquals(
                    1L,
                    db.queryForObject("select reserved from inventory_items where sku='SKU-1'", Long.class));
        }
    }

    @Test
    void emptyRollbackAndDelayedTry() {
        var i = new Inventory(db, json, bus, tx);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> i.apply(id, "Inventory.Cancel", payload(id)));
        assertThrows(
                IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> i.apply(id, "Inventory.Try", payload(id))));
        assertEquals(
                0L,
                db.queryForObject("select reserved from inventory_items where sku='SKU-1'", Long.class));
    }

    @Test
    void duplicateConfirmCancelAndExpiry() {
        var i = new Inventory(db, json, bus, tx);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(
                s -> {
                    i.apply(id, "Inventory.Try", payload(id));
                    i.apply(id, "Inventory.Confirm", payload(id));
                    i.apply(id, "Inventory.Confirm", payload(id));
                });
        assertThrows(
                IllegalStateException.class,
                () -> tx.executeWithoutResult(s -> i.apply(id, "Inventory.Cancel", payload(id))));
        tx.executeWithoutResult(
                s -> {
                    i.apply(id, "Inventory.Release", payload(id));
                    i.apply(id, "Inventory.Release", payload(id));
                });
        assertEquals(
                0L,
                db.queryForObject("select allocated from inventory_items where sku='SKU-1'", Long.class));
        UUID exp = UUID.randomUUID();
        tx.executeWithoutResult(s -> i.apply(exp, "Inventory.Try", payload(exp)));
        db.update(
                "update inventory_reservations set expires_at=now()-interval '1 second' where id=?", exp);
        i.expire();
        assertEquals(
                "EXPIRED",
                db.queryForObject(
                        "select state from inventory_reservations where id=?", String.class, exp));
    }

    @Test
    void confirmCancelRace() throws Exception {
        var i = new Inventory(db, json, bus, tx);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(s -> i.apply(id, "Inventory.Try", payload(id)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (String action : List.of("Inventory.Confirm", "Inventory.Cancel"))
                tasks.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        tx.executeWithoutResult(s -> i.apply(id, action, payload(id)));
                                        return true;
                                    } catch (IllegalStateException ex) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int won = 0;
            for (var f : tasks) if (f.get()) won++;
            assertEquals(1, won);
            assertEquals(
                    0L,
                    db.queryForObject("select reserved from inventory_items where sku='SKU-1'", Long.class));
        }
    }
}
