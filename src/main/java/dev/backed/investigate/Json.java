package dev.backed.investigate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Optional;

/** The JSON mapper this module uses for bundles, drafts and model output. */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private Json() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /** Parses text as JSON, or empty when it is null or not JSON. */
    static Optional<JsonNode> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readTree(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
