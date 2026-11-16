package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.CanonicalJson;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LegacyBalancesTest {
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

    com.fasterxml.jackson.databind.node.ObjectNode manifest(UUID id, UUID key) {
        var m =
                json.obj()
                        .put("schemaVersion", 1)
                        .put("source", "oms-local-simulator-v1")
                        .put("paymentId", id.toString())
                        .put("amount", 100)
                        .put("currency", "USD")
                        .put("state", "CAPTURED")
                        .put("captured", 100)
                        .put("refunded", 30);
        m.putArray("operations")
                .addObject()
                .put("operationId", key.toString())
                .put("kind", "Payment.Capture")
                .set("request", payload(id));
        return m;
    }

    JsonNode input(JsonNode m) {
        return json.obj().put("digest", CanonicalJson.digest(m)).set("manifest", m);
    }

    @Test
    void openingBalanceIsNotAChargeAndOldOperationKeysAreNotExecutable() {
        UUID payment = UUID.randomUUID(), id = UUID.randomUUID(), key = UUID.randomUUID();
        var m = manifest(payment, key);
        var imports = new LegacyBalances(db, json);
        tx.executeWithoutResult(
                s -> {
                    imports.importBalance(id, input(m));
                    imports.importBalance(id, input(m));
                });
        assertEquals(0, db.queryForObject("select count(*) from provider_requests", Integer.class));
        assertEquals(100L, db.queryForObject("select captured from provider_accounts", Long.class));
        var ledger = new ProviderLedger(db, json);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        tx.executeWithoutResult(
                                s -> ledger.execute(key, payment, "Payment.Capture", payload(payment))));
        tx.executeWithoutResult(
                s ->
                        ledger.execute(
                                UUID.randomUUID(),
                                payment,
                                "Payment.Refund",
                                payload(payment).put("refundAmount", 70)));
        assertEquals(100L, db.queryForObject("select refunded from provider_accounts", Long.class));
        assertEquals(1, db.queryForObject("select count(*) from provider_requests", Integer.class));
    }

    @Test
    void nativeAccountAndChangedManifestAreNeverOverwritten() {
        UUID payment = UUID.randomUUID(), id = UUID.randomUUID(), key = UUID.randomUUID();
        var ledger = new ProviderLedger(db, json);
        tx.executeWithoutResult(
                s -> ledger.execute(UUID.randomUUID(), payment, "Payment.Authorize", payload(payment)));
        var imports = new LegacyBalances(db, json);
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        tx.executeWithoutResult(s -> imports.importBalance(id, input(manifest(payment, key)))));
        assertEquals(0L, db.queryForObject("select captured from provider_accounts", Long.class));
        UUID other = UUID.randomUUID();
        var m = manifest(other, key);
        tx.executeWithoutResult(s -> imports.importBalance(id, input(m)));
        assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () ->
                        tx.executeWithoutResult(
                                s -> imports.importBalance(id, input(m.deepCopy().put("refunded", 20)))));
        assertEquals(
                30L,
                db.queryForObject("select refunded from provider_accounts where id=?", Long.class, other));
    }

    @Test
    void nativeExecutionAndCrossAccountImportSerializeTheSameOperationKey() throws Exception {
        UUID key = UUID.randomUUID(),
                nativePayment = UUID.randomUUID(),
                legacyPayment = UUID.randomUUID();
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var nativeWork =
                    workers.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                new ProviderLedger(db, json)
                                                        .execute(
                                                                key, nativePayment, "Payment.Authorize", payload(nativePayment));
                                                held.countDown();
                                                try {
                                                    if (!release.await(10, TimeUnit.SECONDS))
                                                        throw new IllegalStateException("Test latch expired");
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new RuntimeException(e);
                                                }
                                            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            var importing =
                    workers.submit(
                            () ->
                                    assertThrows(
                                            org.springframework.web.server.ResponseStatusException.class,
                                            () ->
                                                    tx.executeWithoutResult(
                                                            s ->
                                                                    new LegacyBalances(db, json)
                                                                            .importBalance(
                                                                                    UUID.randomUUID(),
                                                                                    input(manifest(legacyPayment, key))))));
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (db.queryForObject(
                        "select count(*) from pg_stat_activity where wait_event='advisory'",
                        Integer.class)
                        == 0
                        && System.nanoTime() < until) Thread.sleep(20);
                assertTrue(
                        db.queryForObject(
                                "select count(*) from pg_stat_activity where wait_event='advisory'",
                                Integer.class)
                                > 0);
            } finally {
                release.countDown();
            }
            nativeWork.get(10, TimeUnit.SECONDS);
            importing.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        assertEquals(
                0, db.queryForObject("select count(*) from provider_legacy_imports", Integer.class));
        assertEquals(1, db.queryForObject("select count(*) from provider_requests", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from provider_accounts where id=?", Integer.class, legacyPayment));
    }
}
