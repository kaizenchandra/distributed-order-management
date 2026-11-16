package dev.oms.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oms.testsupport.TestPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MessagingTest {
    static TestPostgres pg = new TestPostgres();

    static {
        pg.start();
    }

    JdbcTemplate db;
    TransactionTemplate tx;
    Json json;
    KafkaTemplate<String, String> kafka;
    Messaging bus;

    @AfterAll
    static void stop() {
        pg.stop();
    }

    @BeforeEach
    void setup() {
        var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        db = new JdbcTemplate(ds);
        db.execute("drop table if exists outbox,inbox,business_effects");
        new ResourceDatabasePopulator(new ClassPathResource("messaging.sql")).execute(ds);
        db.execute("create table business_effects(id int primary key,amount int)");
        db.update("insert into business_effects values (1,0)");
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        json = new Json(new ObjectMapper().findAndRegisterModules());
        kafka = mock(KafkaTemplate.class);
        bus = new Messaging(db, tx, json, kafka, "consumer");
    }

    void emit(int n) {
        tx.executeWithoutResult(
                s -> {
                    for (int i = 0; i < n; i++)
                        bus.emit(
                                "test", bus.message("event-" + i, "Fact", UUID.randomUUID(), 1, null, json.obj()));
                });
    }

    @Test
    void sendsArePipelinedBeforeWaitingForAnyAcknowledgment() {
        emit(3);
        var sent = new ArrayList<CompletableFuture<SendResult<String, String>>>();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            var f = new CompletableFuture<SendResult<String, String>>();
                            sent.add(f);
                            if (sent.size() == 3) sent.forEach(v -> v.complete(null));
                            return f;
                        });
        bus.relay();
        assertEquals(3, sent.size());
        assertEquals(
                3,
                db.queryForObject(
                        "select count(*) from outbox where published_at is not null", Integer.class));
    }

    @Test
    void onlyAcknowledgedRowsAreMarkedAndDuplicateDeliveriesHaveOneLocalEffect() {
        emit(3);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                calls.incrementAndGet() == 2
                                        ? CompletableFuture.failedFuture(
                                        new IllegalStateException("Broker unavailable"))
                                        : CompletableFuture.completedFuture(null));
        bus.relay();
        assertEquals(
                2,
                db.queryForObject(
                        "select count(*) from outbox where published_at is not null", Integer.class));
        assertEquals(
                1,
                db.queryForObject(
                        "select count(*) from outbox where published_at is null and next_attempt>now()",
                        Integer.class));
        String event = db.queryForObject("select payload::text from outbox limit 1", String.class);
        bus.receive(event, e -> db.update("update business_effects set amount=amount+1"));
        bus.receive(event, e -> db.update("update business_effects set amount=amount+1"));
        assertEquals(1, db.queryForObject("select amount from business_effects", Integer.class));
    }
}
