package dev.oms.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class Json {
    public final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public JsonNode read(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public ObjectNode obj() {
        return mapper.createObjectNode();
    }

    public Envelope envelope(String s) {
        try {
            return mapper.readValue(s, Envelope.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
