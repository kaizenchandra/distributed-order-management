package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.CanonicalJson;
import dev.oms.infra.Json;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Component
public class LegacyBalances {
    final JdbcTemplate db;
    final Json json;

    public LegacyBalances(JdbcTemplate db, Json json) {
        this.db = db;
        this.json = json;
    }

    JsonNode response(Map<String, Object> row) {
        return json.obj()
                .put("status", "IMPORTED")
                .put("migrationId", row.get("id").toString())
                .put("paymentId", row.get("payment_id").toString())
                .put("digest", row.get("digest").toString())
                .set("manifest", json.read(row.get("manifest").toString()));
    }

    public JsonNode lookup(UUID id) {
        var rows = db.queryForList("select * from provider_legacy_imports where id=?", id);
        return rows.isEmpty() ? null : response(rows.getFirst());
    }

    @Transactional
    public JsonNode importBalance(UUID id, JsonNode input) {
        JsonNode m = input.path("manifest");
        String hash = input.path("digest").asText();
        if (!hash.equals(CanonicalJson.digest(m))
                || !m.path("source").asText().equals("oms-local-simulator-v1")
                || m.path("schemaVersion").asInt() != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid migration manifest");
        UUID payment = UUID.fromString(m.path("paymentId").asText());
        long amount = m.path("amount").asLong(-1),
                captured = m.path("captured").asLong(-1),
                refunded = m.path("refunded").asLong(-1);
        String state = m.path("state").asText();
        boolean valid =
                switch (state) {
                    case "NEW", "AUTHORIZED", "VOIDED" -> captured == 0 && refunded == 0;
                    case "CAPTURED" -> captured == amount && refunded < captured;
                    case "REFUNDED" -> captured == amount && refunded == captured;
                    default -> false;
                };
        if (!m.path("amount").isIntegralNumber()
                || !m.path("amount").canConvertToLong()
                || !m.path("captured").isIntegralNumber()
                || !m.path("captured").canConvertToLong()
                || !m.path("refunded").isIntegralNumber()
                || !m.path("refunded").canConvertToLong()
                || amount <= 0
                || captured < 0
                || refunded < 0
                || refunded > captured
                || !valid
                || !m.path("currency").asText().matches("[A-Z]{3}")
                || !m.path("operations").isArray()
                || m.path("operations").isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Invalid opening balance");
        // Acquire all operation identities before account locks, in a consistent order. This closes
        // the race between a native execution and an import reserving the same global key.
        var keys = new TreeSet<UUID>();
        for (var op : m.path("operations")) keys.add(UUID.fromString(op.path("operationId").asText()));
        if (keys.size() != m.path("operations").size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate historical operation ID");
        for (UUID key : keys)
            db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", key.toString());
        int inserted =
                db.update(
                        "insert into provider_accounts(id,amount,currency,state,captured,refunded) values"
                                + " (?,?,?,?,?,?) on conflict do nothing",
                        payment,
                        amount,
                        m.path("currency").asText(),
                        state,
                        captured,
                        refunded);
        var account = db.queryForMap("select * from provider_accounts where id=? for update", payment);
        var old = db.queryForList("select * from provider_legacy_imports where payment_id=?", payment);
        if (!old.isEmpty()) {
            var row = old.getFirst();
            if (!id.equals(row.get("id")) || !hash.equals(row.get("digest")))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing opening balance differs");
            return response(row);
        }
        // An account without this import's ownership must never be overwritten, even if amounts match.
        if (inserted == 0
                || db.queryForObject(
                "select count(*) from provider_requests where payment_id=?", Integer.class, payment)
                > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Native provider history exists");
        if (((Number) account.get("amount")).longValue() != amount
                || !account.get("currency").equals(m.path("currency").asText())
                || !account.get("state").equals(state)
                || ((Number) account.get("captured")).longValue() != captured
                || ((Number) account.get("refunded")).longValue() != refunded)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Provider account conflict");
        db.update(
                "insert into provider_legacy_imports(id,payment_id,digest,manifest) values"
                        + " (?,?,?,?::jsonb)",
                id,
                payment,
                hash,
                json.write(m));
        for (var op : m.path("operations")) {
            UUID operation = UUID.fromString(op.path("operationId").asText());
            if (db.queryForObject(
                    "select count(*) from provider_requests where operation_id=?",
                    Integer.class,
                    operation)
                    > 0)
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Historical operation ID is already native");
            db.update("insert into provider_legacy_keys values (?,?,?)", operation, payment, id);
        }
        return lookup(id);
    }
}
