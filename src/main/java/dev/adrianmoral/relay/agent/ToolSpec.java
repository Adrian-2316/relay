package dev.adrianmoral.relay.agent;

import java.util.List;
import java.util.Map;

/**
 * A tool the agent can call. Every property is required so the schema can run in strict mode.
 *
 * @param guarded true when the call moves money or changes an order: it is queued for a person, not run
 */
public record ToolSpec(String name, String description, Map<String, Map<String, Object>> properties,
                       boolean guarded) {

    public List<String> required() {
        return List.copyOf(properties.keySet());
    }

    static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    static Map<String, Object> number(String description) {
        return Map.of("type", "number", "description", description);
    }

    static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    static Map<String, Object> oneOf(String description, String... values) {
        return Map.of("type", "string", "description", description, "enum", List.of(values));
    }
}
