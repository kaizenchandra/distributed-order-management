package dev.oms.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * Wire-contract canonicalization, independent of domain entities and JSONB field ordering.
 */
public final class CanonicalJson {
    private CanonicalJson() {
    }

    public static JsonNode sorted(JsonNode n) {
        if (n.isObject()) {
            var o = JsonNodeFactory.instance.objectNode();
            var names = new TreeSet<String>();
            n.fieldNames().forEachRemaining(names::add);
            for (String name : names) o.set(name, sorted(n.get(name)));
            return o;
        }
        if (n.isArray()) {
            var a = JsonNodeFactory.instance.arrayNode();
            n.forEach(v -> a.add(sorted(v)));
            return a;
        }
        return n;
    }

    public static String digest(JsonNode n) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(sorted(n).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
