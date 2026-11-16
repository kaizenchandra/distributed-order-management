package dev.oms.orderservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderTest {
    @Test
    void replayIsPureAndTransitionsValidated() {
        var json = new ObjectMapper();
        var created = json.createObjectNode().put("status", "PENDING");
        var confirmed = json.createObjectNode().put("status", "CONFIRMED");
        Order a =
                new Order(0, null).evolve("OrderCreated", created).evolve("OrderConfirmed", confirmed);
        assertEquals(2, a.version());
        assertEquals("CONFIRMED", a.state().path("status").asText());
        assertThrows(IllegalStateException.class, () -> a.evolve("OrderCreated", created));
        assertEquals(
                3,
                a.evolve("OrderCancelled", json.createObjectNode().put("status", "CANCELLED")).version());
    }
}
