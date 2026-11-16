package dev.oms.orderservice;

import com.fasterxml.jackson.databind.node.ObjectNode;

public record Order(long version, ObjectNode state) {
    public Order evolve(String type, ObjectNode next) {
        String old = state == null ? "NONE" : state.path("status").asText();
        boolean valid =
                switch (type) {
                    case "OrderCreated" -> old.equals("NONE");
                    case "OrderConfirmed" -> old.equals("PENDING");
                    case "OrderCancelled" -> old.equals("PENDING") || old.equals("CONFIRMED");
                    default -> false;
                };
        if (!valid) throw new IllegalStateException("Invalid order transition: " + old + " / " + type);
        return new Order(version + 1, next.deepCopy());
    }
}
