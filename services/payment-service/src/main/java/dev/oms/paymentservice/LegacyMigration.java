package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.CanonicalJson;
import dev.oms.infra.Envelope;
import dev.oms.infra.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class LegacyMigration {
    final JdbcTemplate db;
    final Json json;
    final TransactionTemplate tx;
    final ProviderClient provider;

    public LegacyMigration(
            JdbcTemplate db, Json json, TransactionTemplate tx, ProviderClient provider) {
        this.db = db;
        this.json = json;
        this.tx = tx;
        this.provider = provider;
    }

    static ObjectNode financial(JsonNode p, Json json) {
        var o = json.obj();
        for (String field :
                List.of("orderId", "customerId", "amount", "currency", "refundAmount", "paymentScenario"))
            if (p.has(field)) o.set(field, p.get(field));
        return o;
    }

    ObjectNode manifest(UUID payment) {
        var row = db.queryForMap("select * from payment_intents where id=? for update", payment);
        long amount = ((Number) row.get("amount")).longValue(),
                captured = ((Number) row.get("captured")).longValue(),
                refunded = ((Number) row.get("refunded")).longValue();
        String state = row.get("state").toString();
        boolean valid =
                switch (state) {
                    case "NEW", "AUTHORIZED", "VOIDED" -> captured == 0 && refunded == 0;
                    case "CAPTURED" -> captured == amount && refunded < captured;
                    case "REFUNDED" -> captured == amount && refunded == captured;
                    default -> false;
                };
        if (!valid) throw new IllegalStateException("Invalid legacy state/accounting");
        long refunds =
                db.queryForObject(
                        "select coalesce(sum(amount),0)::bigint from refunds where payment_id=?",
                        Long.class,
                        payment);
        if (refunds != refunded) throw new IllegalStateException("Refund audit does not match balance");
        var out =
                json.obj()
                        .put("schemaVersion", 1)
                        .put("source", "oms-local-simulator-v1")
                        .put("paymentId", payment.toString())
                        .put("customerId", row.get("customer_id").toString())
                        .put("amount", amount)
                        .put("currency", row.get("currency").toString())
                        .put("state", state)
                        .put("captured", captured)
                        .put("refunded", refunded);
        var ops = out.putArray("operations");
        long maxCaptured = 0, maxRefunded = 0;
        for (var op :
                db.queryForList(
                        "select * from payment_operations where payment_id=? order by created_at,operation_id",
                        payment)) {
            var result = json.read(op.get("result").toString());
            long c = result.path("captured").asLong(-1), f = result.path("refunded").asLong(-1);
            if (c < 0 || c > amount || f < 0 || f > c)
                throw new IllegalStateException("Invalid legacy audit result");
            maxCaptured = Math.max(maxCaptured, c);
            maxRefunded = Math.max(maxRefunded, f);
            ops.addObject()
                    .put("operationId", op.get("operation_id").toString())
                    .put("kind", op.get("kind").toString())
                    .set("request", json.read(op.get("request").toString()));
        }
        if (ops.isEmpty() || maxCaptured != captured || maxRefunded != refunded)
            throw new IllegalStateException("Insufficient financial audit evidence");
        var rs = out.putArray("refunds");
        for (var refund :
                db.queryForList("select id,amount from refunds where payment_id=? order by id", payment))
            rs.addObject()
                    .put("id", refund.get("id").toString())
                    .put("amount", ((Number) refund.get("amount")).longValue());
        return out;
    }

    public Map<String, Object> prepare(UUID payment, String actor, String source) {
        if (!source.equals("oms-local-simulator-v1"))
            throw new IllegalArgumentException("Explicit local-simulator source attestation required");
        return tx.execute(
                s -> {
                    var intent =
                            db.queryForMap("select * from payment_intents where id=? for update", payment);
                    var old = db.queryForList("select * from legacy_migrations where payment_id=?", payment);
                    if (!old.isEmpty()) return old.getFirst();
                    if (!intent.get("provider_origin").equals("LEGACY_BLOCKED"))
                        throw new IllegalStateException("Not an eligible legacy payment");
                    if (db.queryForObject(
                            "select count(*) from provider_operations where payment_id=?",
                            Integer.class,
                            payment)
                            != 0) throw new IllegalStateException("Provider operations already exist");
                    var m = manifest(payment);
                    UUID id = Envelope.id("oms-local-simulator-v1:" + payment);
                    String hash = CanonicalJson.digest(m);
                    db.update(
                            "insert into legacy_migrations(id,payment_id,manifest,digest,status,actor) values"
                                    + " (?,?,?::jsonb,?,'PREPARED',?)",
                            id,
                            payment,
                            json.write(m),
                            hash,
                            actor);
                    db.update("update payment_intents set provider_origin='MIGRATING' where id=?", payment);
                    audit(id, "PREPARED", actor, hash);
                    return db.queryForMap("select * from legacy_migrations where id=?", id);
                });
    }

    void audit(UUID id, String action, String actor, String detail) {
        db.update(
                "insert into legacy_migration_audit(migration_id,action,actor,detail) values (?,?,?,?)",
                id,
                action,
                actor,
                detail);
    }

    Work claim() {
        return tx.execute(
                s -> {
                    var rows =
                            db.queryForList(
                                    "select * from legacy_migrations where (status in ('PREPARED','UNKNOWN') and"
                                            + " next_attempt<=now()) or (status='IMPORTING' and lease_until<now()) order"
                                            + " by created_at limit 1 for update skip locked");
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    UUID id = (UUID) row.get("id"), fence = UUID.randomUUID();
                    int attempts = ((Number) row.get("attempts")).intValue() + 1;
                    db.update(
                            "update legacy_migrations set status='IMPORTING',fence=?,lease_until=now()+interval"
                                    + " '10 seconds',attempts=? where id=?",
                            fence,
                            attempts,
                            id);
                    audit(id, "IMPORTING", "worker", Integer.toString(attempts));
                    return new Work(
                            id,
                            (UUID) row.get("payment_id"),
                            json.read(row.get("manifest").toString()),
                            row.get("digest").toString(),
                            fence,
                            attempts);
                });
    }

    void finish(Work w, JsonNode result) {
        tx.executeWithoutResult(
                s -> {
                    var rows =
                            db.queryForList(
                                    "select * from legacy_migrations where id=? and fence=? and status='IMPORTING'"
                                            + " and lease_until>now() for update",
                                    w.id(),
                                    w.fence());
                    if (rows.isEmpty()) return;
                    if (!result.path("status").asText().equals("IMPORTED")
                            || !result.path("migrationId").asText().equals(w.id().toString())
                            || !result.path("paymentId").asText().equals(w.payment().toString())
                            || !result.path("digest").asText().equals(w.hash())
                            || !CanonicalJson.digest(result.path("manifest")).equals(w.hash())
                            || !CanonicalJson.digest(manifest(w.payment())).equals(w.hash()))
                        throw new IllegalStateException("Migration verification mismatch");
                    for (var op : w.manifest().path("operations")) {
                        UUID id = UUID.fromString(op.path("operationId").asText());
                        db.update(
                                "insert into"
                                        + " provider_operations(operation_id,payment_id,kind,request,status,provider_result,imported_legacy,completed_at)"
                                        + " values (?,?,?,?::jsonb,'SUCCEEDED',?::jsonb,true,now())",
                                id,
                                w.payment(),
                                op.path("kind").asText(),
                                json.write(financial(op.path("request"), json)),
                                json.write(
                                        json.obj()
                                                .put("status", "IMPORTED_HISTORY")
                                                .put("migrationId", w.id().toString())));
                    }
                    db.update(
                            "update payment_intents set provider_origin='MIGRATED' where id=?", w.payment());
                    db.update(
                            "update legacy_migrations set"
                                    + " status='VERIFIED',verified_at=now(),lease_until=null,last_error=null where"
                                    + " id=?",
                            w.id());
                    audit(w.id(), "VERIFIED", "worker", w.hash());
                });
    }

    @Scheduled(fixedDelay = 500)
    public void tick() {
        Work w = claim();
        if (w == null) return;
        try {
            var found = provider.lookupMigration(w.id());
            finish(w, found == null ? provider.importMigration(w.id(), w.manifest(), w.hash()) : found);
        } catch (RuntimeException failure) {
            tx.executeWithoutResult(
                    s -> {
                        String status = w.attempts() >= 5 ? "MANUAL_REVIEW" : "UNKNOWN";
                        db.update(
                                "update legacy_migrations set status=?,last_error=?,next_attempt=now()+interval '2"
                                        + " seconds',lease_until=null where id=? and fence=? and status='IMPORTING'",
                                status,
                                "Provider import or verification unavailable; inspect existing manifest",
                                w.id(),
                                w.fence());
                        audit(w.id(), status, "worker", failure.getClass().getSimpleName());
                    });
        }
    }

    public void retry(UUID id, String actor) {
        tx.executeWithoutResult(
                s -> {
                    if (db.update(
                            "update legacy_migrations set status='UNKNOWN',attempts=0,next_attempt=now()"
                                    + " where id=? and status='MANUAL_REVIEW'",
                            id)
                            != 1) throw new IllegalStateException("Migration is not in manual review");
                    audit(id, "RETRY", actor, "Same manifest and identity");
                });
    }

    record Work(UUID id, UUID payment, JsonNode manifest, String hash, UUID fence, int attempts) {
    }
}
