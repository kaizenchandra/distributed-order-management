import java.nio.file.*;
import java.sql.*;
import java.util.*;

class FinancialAudit {
    static Properties settings = new Properties();

    static Connection open(String db) throws Exception {
        var p = new Properties();
        p.setProperty("user", "postgres");
        p.setProperty("password", settings.getProperty("OMS_DB_PASSWORD"));
        p.setProperty("connectTimeout", "5");
        p.setProperty("socketTimeout", "30");
        return DriverManager.getConnection("jdbc:postgresql://localhost:15433/" + db, p);
    }

    static Map<UUID, Map<String, Object>> rows(String db, String query) throws Exception {
        var result = new LinkedHashMap<UUID, Map<String, Object>>();
        try (var c = open(db);
             var s = c.createStatement();
             var rs = s.executeQuery(query)) {
            while (rs.next()) {
                var row = new HashMap<String, Object>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                result.put((UUID) rs.getObject(1), row);
            }
        }
        return result;
    }

    static long number(Map<String, Object> row, String field) {
        return ((Number) row.get(field)).longValue();
    }

    public static void main(String[] args) throws Exception {
        settings.load(Files.newBufferedReader(Path.of(".env")));
        String label = args[0];
        if (!label.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
        var ids =
                rows(
                        "order_service",
                        "select order_id from order_idempotency where key like '" + label + "-%'");
        if (ids.isEmpty()) throw new IllegalStateException("No orders for label");
        String selected = String.join(",", ids.keySet().stream().map(id -> "'" + id + "'").toList());
        var local =
                rows(
                        "payment_service",
                        "select id,amount,captured,refunded,state from payment_intents where id in ("
                                + selected
                                + ")");
        var remote =
                rows(
                        "payment_provider",
                        "select id,amount,captured,refunded,state from provider_accounts where id in ("
                                + selected
                                + ")");
        var sagas =
                rows(
                        "saga_orchestrator",
                        "select id,state from saga_instances where id in (" + selected + ")");
        var orders =
                rows(
                        "order_service",
                        "select distinct on(aggregate_id) aggregate_id,payload->>'status' status from"
                                + " order_events where aggregate_id in ("
                                + selected
                                + ") order by aggregate_id,aggregate_version desc");
        var inventory =
                rows(
                        "inventory_service",
                        "select id,state from inventory_reservations where id in (" + selected + ")");
        int completed = 0, compensated = 0;
        for (var id : ids.keySet()) {
            var a = local.get(id);
            var b = remote.get(id);
            if (a == null || b == null || !a.equals(b))
                throw new IllegalStateException("Provider/local ledger mismatch " + id);
            String state = sagas.get(id).get("state").toString();
            long amount = number(a, "amount"),
                    captured = number(a, "captured"),
                    refunded = number(a, "refunded");
            if (state.equals("COMPLETED")) {
                completed++;
                if (captured != amount
                        || refunded != 0
                        || !"CONFIRMED".equals(orders.get(id).get("status"))
                        || !"CONFIRMED".equals(inventory.get(id).get("state")))
                    throw new IllegalStateException("Completion invariant " + id);
            } else if (state.equals("COMPENSATED")) {
                compensated++;
                if (captured != refunded
                        || !"CANCELLED".equals(orders.get(id).get("status"))
                        || !Set.of("CANCELLED", "EXPIRED", "RELEASED").contains(inventory.get(id).get("state")))
                    throw new IllegalStateException("Compensation invariant " + id);
            } else throw new IllegalStateException("Nonterminal saga " + id + " " + state);
        }
        System.out.println(
                "{\"run\":\""
                        + label
                        + "\",\"orders\":"
                        + ids.size()
                        + ",\"completed\":"
                        + completed
                        + ",\"compensated\":"
                        + compensated
                        + ",\"ledger_inventory_order_checks\":\"PASS\"}");
    }
}
