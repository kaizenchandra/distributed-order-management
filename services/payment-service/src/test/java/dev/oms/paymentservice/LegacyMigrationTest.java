package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LegacyMigrationTest {
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

    UUID seed() {
        UUID id = UUID.randomUUID();
        var ledger = new PaymentLedger(db, json);
        tx.executeWithoutResult(
                s -> {
                    ledger.apply(id, UUID.randomUUID(), "Payment.Authorize", payload(id));
                    ledger.apply(id, UUID.randomUUID(), "Payment.Capture", payload(id));
                    ledger.apply(
                            id, UUID.randomUUID(), "Payment.Refund", payload(id).put("refundAmount", 30));
                });
        db.update("update payment_intents set provider_origin='LEGACY_BLOCKED' where id=?", id);
        return id;
    }

    LegacyMigration worker() {
        return new LegacyMigration(db, json, tx, mock(ProviderClient.class));
    }

    JsonNode response(LegacyMigration.Work w) {
        return json.obj()
                .put("status", "IMPORTED")
                .put("migrationId", w.id().toString())
                .put("paymentId", w.payment().toString())
                .put("digest", w.hash())
                .set("manifest", w.manifest());
    }

    @Test
    void auditedOpeningBalanceUnblocksOnlyAfterVerifiedImportAndReservesOldKeys() {
        UUID id = seed();
        var m = worker();
        var prepared = m.prepare(id, "operator", "oms-local-simulator-v1");
        var again = m.prepare(id, "operator", "oms-local-simulator-v1");
        assertEquals(prepared.get("id"), again.get("id"));
        var operations =
                new PaymentOperations(
                        db, json, bus, tx, mock(ProviderClient.class), new PaymentLedger(db, json), 3);
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                s ->
                                        operations.enqueue(
                                                id, UUID.randomUUID(), "Payment.Capture", payload(id), null)));
        var w = m.claim();
        m.finish(w, response(w));
        assertEquals(
                "MIGRATED",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, id));
        assertEquals(
                3,
                db.queryForObject(
                        "select count(*) from provider_operations where imported_legacy", Integer.class));
        var key =
                db.queryForObject(
                        "select operation_id from payment_operations where kind='Payment.Capture' and"
                                + " payment_id=?",
                        UUID.class,
                        id);
        var replay = tx.execute(s -> operations.enqueue(id, key, "Payment.Capture", payload(id), null));
        assertEquals("SUCCEEDED", replay.get("status"));
        assertEquals(
                30L, db.queryForObject("select refunded from payment_intents where id=?", Long.class, id));
        m.finish(w, response(w));
        assertEquals(3, db.queryForObject("select count(*) from provider_operations", Integer.class));
    }

    @Test
    void invalidRefundAuditCannotBeMigrated() {
        UUID id = seed();
        db.update("delete from refunds where payment_id=?", id);
        assertThrows(
                IllegalStateException.class,
                () -> worker().prepare(id, "operator", "oms-local-simulator-v1"));
        assertEquals(0, db.queryForObject("select count(*) from legacy_migrations", Integer.class));
        assertEquals(
                "LEGACY_BLOCKED",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, id));
    }

    @Test
    void staleImporterCannotUnblockAndSourceMutationFailsVerification() {
        UUID id = seed();
        var m = worker();
        m.prepare(id, "operator", "oms-local-simulator-v1");
        var stale = m.claim();
        db.update("update legacy_migrations set lease_until=now()-interval '1 second'");
        var fresh = m.claim();
        m.finish(stale, response(stale));
        assertEquals(
                "MIGRATING",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, id));
        var bad = (com.fasterxml.jackson.databind.node.ObjectNode) response(fresh);
        bad.put("digest", "wrong");
        assertThrows(IllegalStateException.class, () -> m.finish(fresh, bad));
        assertEquals(0, db.queryForObject("select count(*) from provider_operations", Integer.class));
        m.finish(fresh, response(fresh));
        assertEquals(
                "VERIFIED", db.queryForObject("select status from legacy_migrations", String.class));
    }

    @Test
    void flywayClassifiesOnlyLegacyRows() {
        var ds = db.getDataSource();
        Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).target("3").load().migrate();
        UUID legacy = UUID.randomUUID();
        UUID unproven = UUID.randomUUID(), nativePayment = UUID.randomUUID(), mixed = UUID.randomUUID();
        tx.executeWithoutResult(
                s ->
                        new PaymentLedger(db, json)
                                .apply(legacy, UUID.randomUUID(), "Payment.Authorize", payload(legacy)));
        tx.executeWithoutResult(
                s ->
                        new PaymentLedger(db, json)
                                .apply(mixed, UUID.randomUUID(), "Payment.Authorize", payload(mixed)));
        for (UUID id : List.of(unproven, nativePayment))
            db.update(
                    "insert into payment_intents(id,customer_id,amount,currency,state) values"
                            + " (?,'alice',100,'USD','NEW')",
                    id);
        db.update(
                "insert into provider_operations(operation_id,payment_id,kind,request) values"
                        + " (?,?,'Payment.Authorize','{}')",
                UUID.randomUUID(),
                nativePayment);
        db.update(
                "insert into provider_operations(operation_id,payment_id,kind,request) values"
                        + " (?,?,'Payment.Capture','{}')",
                UUID.randomUUID(),
                mixed);
        Flyway.configure().dataSource(ds).load().migrate();
        assertEquals(
                "LEGACY_BLOCKED",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, legacy));
        assertEquals(
                "LEGACY_BLOCKED",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, unproven));
        assertEquals(
                "NATIVE",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, nativePayment));
        assertEquals(
                "LEGACY_BLOCKED",
                db.queryForObject(
                        "select provider_origin from payment_intents where id=?", String.class, mixed));
        assertThrows(
                IllegalStateException.class,
                () -> worker().prepare(mixed, "operator", "oms-local-simulator-v1"));
        var processor =
                new PaymentOperations(
                        db, json, bus, tx, mock(ProviderClient.class), new PaymentLedger(db, json), 3);
        assertEquals(nativePayment, processor.claim().payment());
        assertNull(
                processor.claim(), "Quarantined mixed history must not dispatch pending provider work");
        assertThrows(
                IllegalStateException.class,
                () -> worker().prepare(unproven, "operator", "oms-local-simulator-v1"));
    }
}
