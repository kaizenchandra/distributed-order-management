package dev.oms.paymentprovider;

import com.fasterxml.jackson.databind.JsonNode;
import dev.oms.infra.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class ProviderApi {
    final ProviderLedger ledger;
    final LegacyBalances legacy;
    final JdbcTemplate db;
    final Json json;
    final byte[] key;

    public ProviderApi(
            ProviderLedger ledger,
            LegacyBalances legacy,
            JdbcTemplate db,
            Json json,
            @Value("${PROVIDER_KEY}") String key) {
        this.ledger = ledger;
        this.legacy = legacy;
        this.db = db;
        this.json = json;
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    void authenticate(String supplied) {
        if (!MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    @PostMapping("/operations")
    public Object submit(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @RequestBody JsonNode input)
            throws InterruptedException {
        authenticate(key);
        UUID op = UUID.fromString(input.path("operationId").asText()),
                payment = UUID.fromString(input.path("paymentId").asText());
        String kind = input.path("kind").asText();
        JsonNode request = input.path("request");
        var result =
                ledger.execute(op, payment, kind, request); // proxy transaction commits BEFORE delay/fault
        if (result.hidden())
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Ambiguous simulator response");
        String scenario = request.path("paymentScenario").asText();
        boolean delay =
                (scenario.equals("authorize-timeout") && kind.equals("Payment.Authorize"))
                        || (scenario.equals("capture-timeout") && kind.equals("Payment.Capture"))
                        || (scenario.equals("refund-timeout")
                        && (kind.equals("Payment.Reverse") || kind.equals("Payment.Refund")));
        if (result.fresh() && delay) Thread.sleep(2000);
        return result.result();
    }

    @GetMapping("/operations/{id}")
    public Object lookup(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @PathVariable UUID id) {
        authenticate(key);
        var rows = db.queryForList("select * from provider_requests where operation_id=?", id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var row = rows.getFirst();
        if ((boolean) row.get("hidden"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return json.read(row.get("result").toString());
    }

    // Deterministic simulator fault switch, not a real provider administrative contract.
    @PostMapping("/operations/{id}/reveal")
    public Object reveal(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @PathVariable UUID id) {
        authenticate(key);
        db.update("update provider_requests set hidden=false where operation_id=?", id);
        return Map.of("operationId", id, "visible", true);
    }

    @GetMapping("/payments/{id}")
    public Object inspect(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @PathVariable UUID id) {
        authenticate(key);
        return db.queryForMap(
                "select *, (select count(*) from provider_requests where payment_id=?) as operation_count"
                        + " from provider_accounts where id=?",
                id,
                id);
    }

    @PostMapping("/legacy-balances/{id}")
    public Object importLegacy(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @PathVariable UUID id,
            @RequestBody JsonNode body) {
        authenticate(key);
        return legacy.importBalance(id, body);
    }

    @GetMapping("/legacy-balances/{id}")
    public Object lookupLegacy(
            @RequestHeader(value = "X-Provider-Key", defaultValue = "") String key,
            @PathVariable UUID id) {
        authenticate(key);
        var value = legacy.lookup(id);
        if (value == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return value;
    }
}
