package dev.oms.catalogservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.oms.infra.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;

@RestController
@RequestMapping("/api/v1/catalog")
public class Catalog {
    final JdbcTemplate db;
    final Json json;

    public Catalog(JdbcTemplate d, Json j) {
        db = d;
        json = j;
    }

    @GetMapping("/products")
    public Object products() {
        return db.queryForList("select * from products where active=true");
    }

    @PostMapping("/quotes")
    public JsonNode quote(@RequestBody ObjectNode request) {
        if (!request.path("items").isArray()
                || request.path("items").isEmpty()
                || request.path("items").size() > 50)
            throw new IllegalArgumentException("1 to 50 items required");
        long total = 0;
        var out = json.obj().put("currency", "USD");
        var items = out.putArray("items");
        var seen = new HashSet<String>();
        for (var item : request.path("items")) {
            String sku = item.path("sku").asText();
            long q = item.path("quantity").asLong();
            if (q < 1 || q > 1000 || !seen.add(sku))
                throw new IllegalArgumentException("Invalid or duplicate item");
            var p = db.queryForMap("select * from products where sku=? and active=true", sku);
            long price = ((Number) p.get("price_minor")).longValue();
            total = Math.addExact(total, Math.multiplyExact(price, q));
            items.addObject().put("sku", sku).put("quantity", q).put("unitPrice", price);
        }
        out.put("amount", total);
        if (request.has("paymentScenario")) out.set("paymentScenario", request.get("paymentScenario"));
        return out;
    }
}
