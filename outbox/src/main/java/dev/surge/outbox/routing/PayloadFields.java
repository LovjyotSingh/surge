package dev.surge.outbox.routing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Best-effort reads of top-level scalar fields from a JSON payload; never throws. */
final class PayloadFields {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private PayloadFields() {
    }

    /** The field as a string when it is a non-null scalar; null for missing, null, object, array or invalid JSON. */
    static String scalar(String payload, String field) {
        try {
            JsonNode node = JSON.readTree(payload).get(field);
            if (node == null || !node.isValueNode() || node.isNull()) {
                return null;
            }
            String text = node.asString();
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
