package dev.oms.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvelopeTest {
    @Test
    void rejectsUnsupportedVersions() {
        UUID id = UUID.randomUUID();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Envelope(
                                id,
                                "OrderCreated",
                                id,
                                1,
                                2,
                                id,
                                null,
                                Instant.now(),
                                new ObjectMapper().createObjectNode()));
        assertEquals(Envelope.id("stable"), Envelope.id("stable"));
    }
}
