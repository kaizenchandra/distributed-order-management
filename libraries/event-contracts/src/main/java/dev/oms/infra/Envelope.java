package dev.oms.infra;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record Envelope(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        long aggregateVersion,
        int schemaVersion,
        UUID correlationId,
        UUID causationId,
        Instant timestamp,
        JsonNode payload) {
    public Envelope {
        if (eventId == null
                || aggregateId == null
                || correlationId == null
                || timestamp == null
                || payload == null
                || schemaVersion != 1
                || eventType == null) throw new IllegalArgumentException("Unsupported envelope");
    }

    public static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
