package dev.backed.webhook;

import tools.jackson.databind.JsonNode;

final class Json {

    private Json() {
    }

    /** The field as text, or null when it is missing, null or not a scalar. */
    static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isContainer()) {
            return null;
        }
        return value.asString();
    }
}
