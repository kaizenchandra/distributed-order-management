package dev.oms.sagaorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.oms.infra.Envelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaReplyTest {
    @Test
    void definitiveLateOutcomeResolvesManualReviewButStaleCommandsCannotAdvance() {
        UUID id = UUID.randomUUID(), current = UUID.randomUUID();
        var row = new HashMap<String, Object>();
        row.put("state", "MANUAL_REVIEW");
        row.put("current_command", current);
        var outcome =
                new Envelope(
                        UUID.randomUUID(),
                        "Payment.Capture.Ok",
                        id,
                        0,
                        1,
                        id,
                        current,
                        Instant.now(),
                        new ObjectMapper().createObjectNode());
        assertTrue(Sagas.isCurrentReply(row, outcome));
        row.put("current_command", UUID.randomUUID());
        assertFalse(Sagas.isCurrentReply(row, outcome));
        row.put("current_command", current);
        row.put("state", "COMPENSATED");
        assertFalse(Sagas.isCurrentReply(row, outcome));
    }
}
