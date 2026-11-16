package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class PaymentLedger {
    final JdbcTemplate db;
    final Json json;

    public PaymentLedger(JdbcTemplate db, Json json) {
        this.db = db;
        this.json = json;
    }

    Object apply(UUID id, UUID op, String kind, JsonNode p) {
        // Stable operation key models a provider with durable idempotency. No card data or external
        // network effects.
        db.update(
                "insert into payment_intents(id,customer_id,amount,currency,state) select ?,?,?,?,'NEW'"
                        + " where not exists(select 1 from payment_intents where id=?) on conflict do nothing",
                id,
                p.path("customerId").asText(),
                p.path("amount").asLong(),
                p.path("currency").asText(),
                id);
        var row = db.queryForMap("select * from payment_intents where id=? for update", id);
        var old = db.queryForList("select * from payment_operations where operation_id=?", op);
        if (!old.isEmpty()) {
            if (!id.equals(old.getFirst().get("payment_id"))
                    || !kind.equals(old.getFirst().get("kind"))
                    || !json.read(old.getFirst().get("request").toString()).equals(p))
                throw new IllegalStateException("Payment idempotency mismatch");
            return old.getFirst().get("result");
        }
        String state = row.get("state").toString();
        long amount = ((Number) row.get("amount")).longValue(),
                captured = ((Number) row.get("captured")).longValue(),
                refunded = ((Number) row.get("refunded")).longValue();
        if (kind.equals("Payment.Authorize")
                && (!p.path("currency").asText().equals(row.get("currency"))
                || p.path("amount").asLong() != amount))
            throw new IllegalStateException("Payment amount mismatch");
        switch (kind) {
            case "Payment.Authorize":
                if (state.equals("NEW")) {
                    if (p.path("paymentScenario").asText().equals("decline"))
                        throw new IllegalStateException("Simulated decline");
                    state = "AUTHORIZED";
                } else if (!state.equals("AUTHORIZED")) throw new IllegalStateException("Cannot authorize");
                break;
            case "Payment.Capture":
                if (state.equals("AUTHORIZED")) {
                    state = "CAPTURED";
                    captured = amount;
                } else if (!state.equals("CAPTURED")) throw new IllegalStateException("Cannot capture");
                break;
            case "Payment.Void":
                if (state.equals("NEW") || state.equals("AUTHORIZED")) state = "VOIDED";
                else if (!state.equals("VOIDED"))
                    throw new IllegalStateException("Captured payment requires refund");
                break;
            case "Payment.Refund":
                long refund = p.path("refundAmount").asLong(captured - refunded);
                if (refund <= 0 || refund > captured - refunded)
                    throw new IllegalStateException("Refund exceeds eligible amount");
                refunded = Math.addExact(refunded, refund);
                db.update("insert into refunds values (?,?,?)", op, id, refund);
                state = refunded == captured ? "REFUNDED" : "CAPTURED";
                break;
            case "Payment.Reverse":
                if (captured > refunded) {
                    db.update("insert into refunds values (?,?,?)", op, id, captured - refunded);
                    refunded = captured;
                    state = "REFUNDED";
                } else if (captured == 0) state = "VOIDED";
                break;
            default:
                throw new IllegalArgumentException("Unknown payment operation");
        }
        db.update(
                "update payment_intents set state=?,captured=?,refunded=? where id=?",
                state,
                captured,
                refunded,
                id);
        var result =
                json.obj()
                        .put("paymentId", id.toString())
                        .put("state", state)
                        .put("captured", captured)
                        .put("refunded", refunded);
        db.update(
                "insert into payment_operations(operation_id,payment_id,kind,request,result) values"
                        + " (?,?,?,?::jsonb,?::jsonb)",
                op,
                id,
                kind,
                json.write(p),
                json.write(result));
        return result;
    }
}
