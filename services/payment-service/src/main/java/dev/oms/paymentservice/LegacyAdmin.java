package dev.oms.paymentservice;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/legacy-payments")
public class LegacyAdmin {
    final LegacyMigration migrations;
    final JdbcTemplate db;

    public LegacyAdmin(LegacyMigration migrations, JdbcTemplate db) {
        this.migrations = migrations;
        this.db = db;
    }

    @GetMapping
    public Object candidates() {
        return db.queryForList(
                "select id,state,amount,captured,refunded,provider_origin from payment_intents where"
                        + " provider_origin in ('LEGACY_BLOCKED','MIGRATING') order by id limit 100");
    }

    @PostMapping("/{id}/prepare")
    public Object prepare(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @RequestBody JsonNode body) {
        return migrations.prepare(id, jwt.getSubject(), body.path("source").asText());
    }

    @GetMapping("/migrations/{id}")
    public Object inspect(@PathVariable UUID id) {
        return db.queryForMap(
                "select id,payment_id,digest,status,attempts,last_error,actor,verified_at from"
                        + " legacy_migrations where id=?",
                id);
    }

    @PostMapping("/migrations/{id}/retry")
    public Object retry(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        migrations.retry(id, jwt.getSubject());
        return inspect(id);
    }
}
