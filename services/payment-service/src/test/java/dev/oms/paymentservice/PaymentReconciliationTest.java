package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import dev.oms.testsupport.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PaymentReconciliationTest {
    static TestPostgres pg = new TestPostgres();
    JdbcTemplate db;
    TransactionTemplate tx;
    Json json;
    Messaging bus;
    PaymentOperations operations;
    HttpServer server;
    ExecutorService pool;
    AtomicBoolean delay = new AtomicBoolean(),
            hidden = new AtomicBoolean(),
            beforeCommit = new AtomicBoolean(),
            reject = new AtomicBoolean();
    AtomicInteger submits = new AtomicInteger(), lookups = new AtomicInteger();

    @BeforeAll
    static void start() {
        pg.start();
    }

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        db = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        json = new Json(new ObjectMapper().findAndRegisterModules());
        bus = mock(Messaging.class);
        Flyway.configure().dataSource(ds).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(ds).load().migrate();
        db.execute(
                "create table simulated_provider_effects(id uuid primary key,payment_id uuid not"
                        + " null,result jsonb not null)");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newFixedThreadPool(4);
        server.setExecutor(pool);
        server.createContext("/internal/operations", this::provider);
        server.start();
        operations = newWorker(3);
    }

    PaymentOperations newWorker(int attempts) {
        var client =
                new ProviderClient(
                        json, "http://127.0.0.1:" + server.getAddress().getPort(), "test-provider-key", 750);
        return new PaymentOperations(db, json, bus, tx, client, new PaymentLedger(db, json), attempts);
    }

    @AfterEach
    void close() {
        server.stop(0);
        pool.shutdownNow();
    }

    void provider(HttpExchange exchange) throws java.io.IOException {
        try {
            if (!"test-provider-key".equals(exchange.getRequestHeaders().getFirst("X-Provider-Key"))) {
                respond(exchange, 403, "{}");
                return;
            }
            if (exchange.getRequestMethod().equals("GET")) {
                lookups.incrementAndGet();
                if (hidden.get()) {
                    respond(exchange, 503, "{}");
                    return;
                }
                UUID id =
                        UUID.fromString(
                                exchange.getRequestURI().getPath().substring("/internal/operations/".length()));
                var result =
                        db.queryForList("select result from simulated_provider_effects where id=?", id);
                respond(
                        exchange,
                        result.isEmpty() ? 404 : 200,
                        result.isEmpty() ? "{}" : result.getFirst().get("result").toString());
                return;
            }
            submits.incrementAndGet();
            if (beforeCommit.getAndSet(false)) {
                respond(exchange, 503, "{}");
                return;
            }
            JsonNode body =
                    json.read(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8));
            UUID id = UUID.fromString(body.path("operationId").asText()),
                    payment = UUID.fromString(body.path("paymentId").asText());
            String kind = body.path("kind").asText();
            long captured = kind.equals("Payment.Authorize") ? 0 : 100,
                    refunded = kind.equals("Payment.Reverse") || kind.equals("Payment.Refund") ? 100 : 0;
            var result =
                    json.obj()
                            .put("operationId", id.toString())
                            .put("paymentId", payment.toString())
                            .put("kind", kind)
                            .put("status", reject.get() ? "REJECTED" : "SUCCEEDED")
                            .put("captured", captured)
                            .put("refunded", refunded)
                            .put("reason", reject.get() ? "Declined" : "");
            // Provider's durable idempotency store is committed independently from the caller.
            db.update(
                    "insert into simulated_provider_effects values (?,?,?::jsonb) on conflict do nothing",
                    id,
                    payment,
                    json.write(result));
            String persisted =
                    db.queryForObject(
                            "select result::text from simulated_provider_effects where id=?", String.class, id);
            if (hidden.get()) {
                respond(exchange, 503, "{}");
                return;
            }
            if (delay.getAndSet(false)) Thread.sleep(2000);
            respond(exchange, 200, persisted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    void respond(HttpExchange e, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, bytes.length);
        e.getResponseBody().write(bytes);
    }

    ObjectNode payload(UUID id) {
        return json.obj()
                .put("orderId", id.toString())
                .put("customerId", "alice")
                .put("amount", 100)
                .put("currency", "USD");
    }

    UUID enqueue(UUID payment, String kind) {
        UUID op = UUID.randomUUID();
        tx.executeWithoutResult(s -> operations.enqueue(payment, op, kind, payload(payment), null));
        return op;
    }

    void due() {
        db.update(
                "update provider_operations set next_attempt=now()-interval '1"
                        + " second',lease_until=now()-interval '1 second' where status in"
                        + " ('UNKNOWN','SUBMITTING')");
    }

    String status(UUID id) {
        return db.queryForObject(
                "select status from provider_operations where operation_id=?", String.class, id);
    }

    void authorized(UUID payment) {
        UUID op = enqueue(payment, "Payment.Authorize");
        operations.tick();
        assertEquals("SUCCEEDED", status(op));
    }

    @Test
    void invalidMoneyCannotQueueOrReachProvider() {
        UUID payment = UUID.randomUUID();
        var fractional = payload(payment);
        fractional.put("amount", new java.math.BigDecimal("1.25"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tx.executeWithoutResult(
                                t ->
                                        operations.enqueue(
                                                payment, UUID.randomUUID(), "Payment.Authorize", fractional, null)));
        var invalidRefund = payload(payment).put("refundAmount", -1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tx.executeWithoutResult(
                                t ->
                                        operations.enqueue(
                                                payment, UUID.randomUUID(), "Payment.Refund", invalidRefund, null)));
        assertEquals(0, db.queryForObject("select count(*) from provider_operations", Integer.class));
        assertEquals(0, db.queryForObject("select count(*) from payment_intents", Integer.class));
        assertEquals(0, submits.get());
    }

    @Test
    void lostCaptureAndRefundResponsesReconcileWithoutDuplicateEffects() {
        UUID payment = UUID.randomUUID();
        authorized(payment);
        UUID capture = enqueue(payment, "Payment.Capture");
        delay.set(true);
        operations.tick();
        assertEquals("UNKNOWN", status(capture));
        assertEquals(
                0L,
                db.queryForObject("select captured from payment_intents where id=?", Long.class, payment));
        due();
        operations.tick();
        assertEquals("SUCCEEDED", status(capture));
        assertEquals(
                100L,
                db.queryForObject("select captured from payment_intents where id=?", Long.class, payment));
        UUID refund = enqueue(payment, "Payment.Refund");
        delay.set(true);
        operations.tick();
        assertEquals("UNKNOWN", status(refund));
        due();
        operations.tick();
        assertEquals("SUCCEEDED", status(refund));
        assertEquals(
                100L,
                db.queryForObject("select refunded from payment_intents where id=?", Long.class, payment));
        assertEquals(3, submits.get());
        assertEquals(2, lookups.get());
        assertEquals(
                3, db.queryForObject("select count(*) from simulated_provider_effects", Integer.class));
    }

    @Test
    void crashAfterProviderCommitAndStaleWorkerAreFenced() {
        UUID payment = UUID.randomUUID();
        authorized(payment);
        UUID capture = enqueue(payment, "Payment.Capture");
        var stale = operations.claim();
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        JsonNode answer = operations.invoke(stale);
        assertEquals("SUBMITTING", status(capture));
        due();
        var restarted = newWorker(3);
        var fresh = restarted.claim();
        operations.complete(stale, answer);
        assertEquals(
                0L,
                db.queryForObject("select captured from payment_intents where id=?", Long.class, payment));
        restarted.complete(fresh, restarted.invoke(fresh));
        assertEquals("SUCCEEDED", status(capture));
        assertEquals(2, submits.get());
        assertEquals(1, lookups.get());
    }

    @Test
    void ambiguousCaptureBlocksReversalUntilManualReconciliation() {
        UUID payment = UUID.randomUUID();
        authorized(payment);
        UUID capture = enqueue(payment, "Payment.Capture");
        hidden.set(true);
        operations.tick();
        UUID reversal = enqueue(payment, "Payment.Reverse");
        assertNull(operations.claim());
        for (int n = 0; n < 2; n++) {
            due();
            operations.tick();
        }
        assertEquals("MANUAL_REVIEW", status(capture));
        assertEquals("PENDING", status(reversal));
        assertEquals(
                0L,
                db.queryForObject("select captured from payment_intents where id=?", Long.class, payment));
        hidden.set(false);
        tx.executeWithoutResult(s -> operations.retry(capture));
        operations.tick();
        assertEquals("SUCCEEDED", status(capture));
        assertEquals("SUCCEEDED", status(reversal));
        assertEquals(
                100L,
                db.queryForObject("select refunded from payment_intents where id=?", Long.class, payment));
        assertEquals(3, submits.get());
    }

    @Test
    void definitiveRejectionDoesNotCreateFinancialLedgerEffect() {
        UUID payment = UUID.randomUUID();
        UUID op = enqueue(payment, "Payment.Authorize");
        reject.set(true);
        operations.tick();
        assertEquals("REJECTED", status(op));
        assertEquals(0, db.queryForObject("select count(*) from payment_operations", Integer.class));
        assertEquals(
                "NEW",
                db.queryForObject("select state from payment_intents where id=?", String.class, payment));
    }

    @Test
    void lookupAbsentAfterFailedSubmissionRetriesTheSameIdentity() {
        UUID payment = UUID.randomUUID();
        authorized(payment);
        UUID capture = enqueue(payment, "Payment.Capture");
        beforeCommit.set(true);
        operations.tick();
        assertEquals("UNKNOWN", status(capture));
        assertEquals(
                1, db.queryForObject("select count(*) from simulated_provider_effects", Integer.class));
        due();
        operations.tick();
        assertEquals("SUCCEEDED", status(capture));
        assertEquals(3, submits.get());
        assertEquals(1, lookups.get());
        assertEquals(
                2, db.queryForObject("select count(*) from simulated_provider_effects", Integer.class));
    }

    @Test
    void concurrentClaimsAndChangedIdentityCannotDuplicateWork() throws Exception {
        UUID payment = UUID.randomUUID(), op = enqueue(payment, "Payment.Authorize");
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                s -> operations.enqueue(payment, op, "Payment.Capture", payload(payment), null)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var tasks = new ArrayList<Future<PaymentOperations.Work>>();
            for (int n = 0; n < 2; n++)
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return operations.claim();
                                }));
            gate.countDown();
            var claimed = new ArrayList<PaymentOperations.Work>();
            for (var f : tasks) {
                var work = f.get();
                if (work != null) claimed.add(work);
            }
            assertEquals(1, claimed.size());
            operations.complete(claimed.getFirst(), operations.invoke(claimed.getFirst()));
            assertEquals(1, submits.get());
        }
    }
}
