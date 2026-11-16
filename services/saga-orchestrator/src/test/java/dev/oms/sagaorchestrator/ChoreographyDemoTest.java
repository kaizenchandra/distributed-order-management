package dev.oms.sagaorchestrator;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Isolated conceptual choreography: no production order or second coordinator.
 */
class ChoreographyDemoTest {
    @Test
    void participantsReactToFacts() {
        Queue<String> events = new ArrayDeque<>();
        Set<String> processed = new HashSet<>();
        List<String> history = new ArrayList<>();
        events.add("OrderCreated");
        events.add("OrderCreated");
        while (!events.isEmpty()) {
            String event = events.remove();
            if (!processed.add(event)) continue;
            history.add(event);
            switch (event) {
                case "OrderCreated" -> events.add("InventoryReserved");
                case "InventoryReserved" -> events.add("PaymentAuthorized");
                case "PaymentAuthorized" -> events.add("OrderConfirmed");
                default -> {
                }
            }
        }
        assertEquals(
                List.of("OrderCreated", "InventoryReserved", "PaymentAuthorized", "OrderConfirmed"),
                history);
    }
}
