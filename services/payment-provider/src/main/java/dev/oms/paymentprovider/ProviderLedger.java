package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class ProviderLedger {
    final JdbcTemplate db;
    final Json json;

    public ProviderLedger(JdbcTemplate db, Json json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public Outcome execute(UUID op, UUID payment, String kind, JsonNode p) {
        if (!p.path("amount").isIntegralNumber()
                || !p.path("amount").canConvertToLong()
                || p.path("amount").asLong() <= 0
                || !p.path("currency").asText().matches("[A-Z]{3}")
                || (p.has("refundAmount")
                && (!p.path("refundAmount").isIntegralNumber()
                || !p.path("refundAmount").canConvertToLong()
                || p.path("refundAmount").asLong() <= 0)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Money must use positive integer minor units");
        // Share the identity lock with legacy imports, including when the same key targets a
        // different payment. Account row locks alone cannot serialize that cross-account race.
        db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", op.toString());
        db.update(
                "insert into provider_accounts(id,amount,currency,state) select ?,?,?,'NEW' where not"
                        + " exists(select 1 from provider_accounts where id=?) on conflict do nothing",
                payment,
                p.path("amount").asLong(),
                p.path("currency").asText(),
                payment);
        var row = db.queryForMap("select * from provider_accounts where id=? for update", payment);
        if (db.queryForObject(
                "select count(*) from provider_legacy_keys where operation_id=?", Integer.class, op)
                > 0)
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "Historical operation is reserved, not executable");
        var prior = db.queryForList("select * from provider_requests where operation_id=?", op);
        if (!prior.isEmpty()) {
            var old = prior.getFirst();
            if (!payment.equals(old.get("payment_id"))
                    || !kind.equals(old.get("kind"))
                    || !json.read(old.get("request").toString()).equals(p))
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Operation payload mismatch");
            return new Outcome(
                    json.read(old.get("result").toString()), false, (boolean) old.get("hidden"));
        }
        String state = row.get("state").toString();
        long amount = ((Number) row.get("amount")).longValue(),
                captured = ((Number) row.get("captured")).longValue(),
                refunded = ((Number) row.get("refunded")).longValue();
        String error = null;
        switch (kind) {
            case "Payment.Authorize":
                if (p.path("amount").asLong() != amount
                        || !p.path("currency").asText().equals(row.get("currency"))) error = "Amount mismatch";
                else if (p.path("paymentScenario").asText().equals("decline")) error = "Simulated decline";
                else if (state.equals("NEW")) state = "AUTHORIZED";
                else if (!state.equals("AUTHORIZED")) error = "Cannot authorize";
                break;
            case "Payment.Capture":
                if (state.equals("AUTHORIZED")) {
                    captured = amount;
                    state = "CAPTURED";
                } else if (!state.equals("CAPTURED")) error = "Cannot capture";
                break;
            case "Payment.Void":
                if (state.equals("NEW") || state.equals("AUTHORIZED")) state = "VOIDED";
                else if (!state.equals("VOIDED")) error = "Capture requires refund";
                break;
            case "Payment.Refund":
                long value = p.path("refundAmount").asLong(captured - refunded);
                if (value <= 0 || value > captured - refunded) error = "Refund exceeds eligible amount";
                else {
                    refunded += value;
                    state = refunded == captured ? "REFUNDED" : "CAPTURED";
                }
                break;
            case "Payment.Reverse":
                if (captured > 0) {
                    refunded = captured;
                    state = "REFUNDED";
                } else state = "VOIDED";
                break;
            default:
                error = "Unknown operation";
        }
        var result =
                json.obj()
                        .put("operationId", op.toString())
                        .put("paymentId", payment.toString())
                        .put("kind", kind)
                        .put("status", error == null ? "SUCCEEDED" : "REJECTED")
                        .put("state", state)
                        .put("captured", captured)
                        .put("refunded", refunded);
        if (error != null) result.put("reason", error);
        boolean hidden =
                p.path("paymentScenario").asText().equals("capture-unknown")
                        && kind.equals("Payment.Capture");
        if (error == null)
            db.update(
                    "update provider_accounts set state=?,captured=?,refunded=? where id=?",
                    state,
                    captured,
                    refunded,
                    payment);
        db.update(
                "insert into provider_requests(operation_id,payment_id,kind,request,result,hidden) values"
                        + " (?,?,?,?::jsonb,?::jsonb,?)",
                op,
                payment,
                kind,
                json.write(p),
                json.write(result),
                hidden);
        return new Outcome(json.read(json.write(result)), true, hidden);
    }

    record Outcome(JsonNode result, boolean fresh, boolean hidden) {
    }
}
