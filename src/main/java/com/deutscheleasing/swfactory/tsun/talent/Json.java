package com.deutscheleasing.swfactory.tsun.talent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Lenient JSON accessors. The TALENT API is undocumented and field names differ between
 * portal versions, so every read accepts a list of candidate names and tolerates absence.
 */
public final class Json {

    private Json() {
    }

    public static JsonNode at(JsonNode node, String... candidates) {
        if (node == null) {
            return MissingNode.getInstance();
        }
        for (var name : candidates) {
            var child = node.get(name);
            if (child != null && !child.isNull()) {
                return child;
            }
        }
        return MissingNode.getInstance();
    }

    public static Optional<String> text(JsonNode node, String... candidates) {
        var found = at(node, candidates);
        if (found.isMissingNode()) {
            return Optional.empty();
        }
        var value = found.isValueNode() ? found.asText() : found.toString();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Reads a numeric value. Values arrive as numbers or as strings ("230.4", "--", "");
     * anything unparseable is reported as absent rather than as zero.
     */
    public static OptionalDouble number(JsonNode node, String... candidates) {
        var found = at(node, candidates);
        if (found.isNumber()) {
            return OptionalDouble.of(found.asDouble());
        }
        if (found.isTextual()) {
            try {
                return OptionalDouble.of(Double.parseDouble(found.asText().trim()));
            } catch (NumberFormatException e) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }

    /** Rows of a paged list response: {@code rows}, or {@code data} when it is an array. */
    public static List<JsonNode> rows(JsonNode body) {
        var rows = at(body, "rows", "list");
        if (rows.isArray()) {
            return toList(rows);
        }
        var data = at(body, "data");
        if (data.isArray()) {
            return toList(data);
        }
        var nested = at(data, "rows", "list", "records");
        return nested.isArray() ? toList(nested) : List.of();
    }

    public static List<JsonNode> array(JsonNode node, String... candidates) {
        var found = at(node, candidates);
        return found.isArray() ? toList(found) : List.of();
    }

    private static List<JsonNode> toList(JsonNode array) {
        var out = new java.util.ArrayList<JsonNode>(array.size());
        array.forEach(out::add);
        return List.copyOf(out);
    }
}
