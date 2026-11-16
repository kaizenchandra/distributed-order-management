package dev.oms.orderservice;

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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class EventStoreTest {
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
    void concurrentAppendAndImmutableHistory() throws Exception {
        UUID id = UUID.randomUUID();
        db.update(
                "insert into order_events values (?,?,1,'OrderCreated',1,'{}',?,null,now())",
                UUID.randomUUID(),
                id,
                id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int k = 0; k < 2; k++)
                futures.add(
                        pool.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        tx.executeWithoutResult(
                                                s ->
                                                        db.update(
                                                                "insert into order_events values"
                                                                        + " (?,?,2,'OrderConfirmed',1,'{}',?,null,now())",
                                                                UUID.randomUUID(),
                                                                id,
                                                                id));
                                        return true;
                                    } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                                        return false;
                                    }
                                }));
            gate.countDown();
            int success = 0;
            for (var f : futures) if (f.get()) success++;
            assertEquals(1, success);
        }
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("delete from order_events where aggregate_id=?", id));
    }
}
