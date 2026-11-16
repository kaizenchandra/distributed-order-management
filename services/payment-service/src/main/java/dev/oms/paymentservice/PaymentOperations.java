package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import dev.oms.infra.Messaging;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class PaymentOperations {
    final JdbcTemplate db;
    final Json json;
    final Messaging bus;
    final TransactionTemplate tx;
    final ProviderClient provider;
    final PaymentLedger ledger;
    final int maxAttempts;

    public PaymentOperations(
            JdbcTemplate db,
            Json json,
            Messaging bus,
            TransactionTemplate tx,
            ProviderClient provider,
            PaymentLedger ledger,
            @Value("${oms.provider.max-attempts:8}") int maxAttempts) {
        this.db = db;
        this.json = json;
        this.bus = bus;
        this.tx = tx;
        this.provider = provider;
        this.ledger = ledger;
        this.maxAttempts = maxAttempts;
    }

    static boolean positiveMinorUnits(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0;
    }

    // Caller owns the transaction (HTTP application transaction or consumer inbox transaction).
    public Map<String, Object> enqueue(
            UUID payment, UUID operation, String kind, JsonNode payload, Envelope command) {
        if (!Set.of(
                        "Payment.Authorize",
                        "Payment.Capture",
                        "Payment.Void",
                        "Payment.Refund",
                        "Payment.Reverse")
                .contains(kind)) throw new IllegalArgumentException("Unknown payment operation");
        if ((kind.equals("Payment.Authorize") || command != null)
                && (!positiveMinorUnits(payload.path("amount"))
                || !payload.path("currency").asText().matches("[A-Z]{3}")
                || payload.path("customerId").asText().isBlank()))
            throw new IllegalArgumentException(
                    "Positive integer minor units, currency and customer are required");
        if (payload.has("refundAmount") && !positiveMinorUnits(payload.path("refundAmount")))
            throw new IllegalArgumentException("Refund must use positive integer minor units");
        db.update(
                "insert into payment_intents(id,customer_id,amount,currency,state) select ?,?,?,?,'NEW'"
                        + " where not exists(select 1 from payment_intents where id=?) on conflict do nothing",
                payment,
                payload.path("customerId").asText(),
                payload.path("amount").asLong(),
                payload.path("currency").asText(),
                payment);
        var intent = db.queryForMap("select * from payment_intents where id=? for update", payment);
        if (!Set.of("NATIVE", "MIGRATED").contains(intent.get("provider_origin")))
            throw new IllegalStateException("Legacy balance is blocked until migration is verified");
        // Fill REST capture/refund context from the authoritative intent, never from a read model.
        var request =
                json.obj()
                        .put("orderId", payment.toString())
                        .put("customerId", intent.get("customer_id").toString())
                        .put("amount", ((Number) intent.get("amount")).longValue())
                        .put("currency", intent.get("currency").toString());
        if (command != null)
            request = (com.fasterxml.jackson.databind.node.ObjectNode) payload.deepCopy();
        else {
            if (payload.has("refundAmount")) request.set("refundAmount", payload.get("refundAmount"));
            if (payload.has("paymentScenario"))
                request.set("paymentScenario", payload.get("paymentScenario"));
        }
        if (kind.equals("Payment.Authorize")
                && (payload.path("amount").asLong() != ((Number) intent.get("amount")).longValue()
                || !payload.path("currency").asText().equals(intent.get("currency"))))
            throw new IllegalStateException("Payment amount mismatch");
        db.update(
                "insert into provider_operations(operation_id,payment_id,kind,request,command) values"
                        + " (?,?,?,?::jsonb,?::jsonb) on conflict do nothing",
                operation,
                payment,
                kind,
                json.write(request),
                command == null ? null : json.write(command));
        var row = db.queryForMap("select * from provider_operations where operation_id=?", operation);
        if (!payment.equals(row.get("payment_id"))
                || !kind.equals(row.get("kind"))
                || !json.read(row.get("request").toString())
                .equals(
                        json.read(
                                json.write(
                                        (boolean) row.get("imported_legacy")
                                                ? LegacyMigration.financial(request, json)
                                                : request))))
            throw new IllegalStateException("Payment operation identity mismatch");
        return row;
    }

    Work claim() {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "select p.* from provider_operations p where ((p.status in ('PENDING','UNKNOWN')"
                                            + " and p.next_attempt<=now()) or (p.status='SUBMITTING' and"
                                            + " p.lease_until<now())) and not exists (select 1 from provider_operations"
                                            + " prior where prior.payment_id=p.payment_id and prior.sequence<p.sequence"
                                            + " and prior.status not in ('SUCCEEDED','REJECTED')) and exists (select 1"
                                            + " from payment_intents i where i.id=p.payment_id and i.provider_origin in"
                                            + " ('NATIVE','MIGRATED')) order by p.sequence"
                                            + " limit 1 for update of p skip locked");
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    UUID fence = UUID.randomUUID(), id = (UUID) row.get("operation_id");
                    int attempt = ((Number) row.get("attempts")).intValue() + 1;
                    db.update(
                            "update provider_operations set"
                                    + " status='SUBMITTING',lease_token=?,lease_until=now()+interval '10"
                                    + " seconds',attempts=? where operation_id=?",
                            fence,
                            attempt,
                            id);
                    return new Work(
                            id,
                            (UUID) row.get("payment_id"),
                            row.get("kind").toString(),
                            json.read(row.get("request").toString()),
                            fence,
                            attempt,
                            !row.get("status").equals("PENDING") || attempt > 1);
                });
    }

    JsonNode invoke(Work work) {
        JsonNode outcome = work.reconcile() ? provider.lookup(work.id()) : null;
        JsonNode result =
                outcome == null
                        ? provider.submit(work.id(), work.payment(), work.kind(), work.request())
                        : outcome;
        if (!result.path("operationId").asText().equals(work.id().toString())
                || !result.path("paymentId").asText().equals(work.payment().toString())
                || !result.path("kind").asText().equals(work.kind()))
            throw new ProviderClient.Uncertain("Provider outcome identity mismatch");
        return result;
    }

    void complete(Work work, JsonNode result) {
        tx.executeWithoutResult(
                s -> {
                    var rows =
                            db.queryForList(
                                    "select * from provider_operations where operation_id=? and status='SUBMITTING'"
                                            + " and lease_token=? and lease_until>now() for update",
                                    work.id(),
                                    work.fence());
                    if (rows.isEmpty()) return;
                    var row = rows.getFirst();
                    boolean ok = result.path("status").asText().equals("SUCCEEDED");
                    if (ok) {
                        var local = ledger.apply(work.payment(), work.id(), work.kind(), work.request());
                        JsonNode state = json.read(json.write(local));
                        if (result.has("captured")
                                && (result.path("captured").asLong() != state.path("captured").asLong()
                                || result.path("refunded").asLong() != state.path("refunded").asLong()))
                            throw new IllegalStateException("Provider/local ledger divergence");
                    }
                    db.update(
                            "update provider_operations set"
                                    + " status=?,provider_result=?::jsonb,completed_at=now(),lease_until=null,last_error=null"
                                    + " where operation_id=?",
                            ok ? "SUCCEEDED" : "REJECTED",
                            json.write(result),
                            work.id());
                    if (row.get("command") != null)
                        bus.reply(
                                json.envelope(row.get("command").toString()), ok, result.path("reason").asText(""));
                });
    }

    void uncertain(Work work, String reason) {
        tx.executeWithoutResult(
                s ->
                        db.update(
                                "update provider_operations set status=?,last_error=?,next_attempt=now()+(? *"
                                        + " interval '1 second'),lease_until=null where operation_id=? and"
                                        + " lease_token=? and status='SUBMITTING'",
                                work.attempt() >= maxAttempts ? "MANUAL_REVIEW" : "UNKNOWN",
                                reason,
                                Math.min(30, 1 << Math.min(5, work.attempt() - 1)),
                                work.id(),
                                work.fence()));
    }

    @Scheduled(fixedDelayString = "${oms.provider.poll-ms:250}")
    public void tick() {
        for (int i = 0; i < 4; i++) {
            Work work = claim();
            if (work == null) return;
            try {
                complete(work, invoke(work));
            } catch (RuntimeException failure) {
                uncertain(
                        work,
                        failure instanceof ProviderClient.Uncertain
                                ? failure.getMessage()
                                : "Local result could not be committed; reconcile provider");
            }
        }
    }

    public void retry(UUID operation) {
        int changed =
                db.update(
                        "update provider_operations set"
                                + " status='UNKNOWN',attempts=0,next_attempt=now(),last_error=null where"
                                + " operation_id=? and status='MANUAL_REVIEW'",
                        operation);
        if (changed != 1) throw new IllegalStateException("Operation requires manual review");
    }

    record Work(
            UUID id,
            UUID payment,
            String kind,
            JsonNode request,
            UUID fence,
            int attempt,
            boolean reconcile) {
    }
}
