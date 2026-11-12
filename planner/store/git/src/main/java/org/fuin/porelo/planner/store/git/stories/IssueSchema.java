package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The constraints documented by issue-1.json, worded as the story heads' diagnostics are. */
final class IssueSchema {
    static final String NAME = "issue-1.json";
    static final String ID = "^[A-Z]{1,3}[0-9]{2,3}(\\.[1-9][0-9]{0,2})?$";
    static final String PARENT = "^[A-Z]{1,3}[0-9]{2,3}$";
    static final String WAIT =
            "^([a-z0-9][a-z0-9._-]{0,99}#)?[A-Z]{1,3}[0-9]{2,3}(\\.[1-9][0-9]{0,2})?$";
    static final List<String> PRIORITIES = List.of("Highest", "High", "Medium", "Low", "Lowest");
    static final List<String> WORKERS = List.of("Agent", "Manual");
    static final List<String> RISKS = List.of("harm", "ordinary", "small");
    static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL", "-");
    private static final String LINE = "^[^\\s](.*[^\\s])?$";
    private static final List<String> REQUIRED =
            List.of(
                    "$schema",
                    "id",
                    "title",
                    "state",
                    "size",
                    "priority",
                    "rank",
                    "blockedBy",
                    "covers");
    private static final Map<String, List<String>> ENUMS =
            Map.of(
                    "size",
                    SIZES,
                    "priority",
                    PRIORITIES,
                    "role",
                    List.of("builder", "tester", "reader"),
                    "severity",
                    List.of("Blocker", "Critical", "Major", "Minor", "Trivial"),
                    "kind",
                    List.of("Feature", "Bug", "Task"),
                    "worker",
                    WORKERS,
                    "risk",
                    RISKS);
    private static final List<String> PROPERTIES =
            List.of(
                    "$schema",
                    "id",
                    "title",
                    "state",
                    "size",
                    "priority",
                    "rank",
                    "blockedBy",
                    "covers",
                    "parent",
                    "role",
                    "severity",
                    "kind",
                    "openQuestions",
                    "worker",
                    "risk");

    private IssueSchema() {}

    static List<String> faults(JsonNode data) {
        final List<String> faults = new ArrayList<>();
        if (!data.isObject()) {
            type(data, "object", "", faults);
            return faults;
        }
        for (final String field : PROPERTIES) {
            final JsonNode value = data.get(field);
            if (value == null) continue;
            final String at = "/" + field;
            switch (field) {
                case "$schema" -> {
                    if (!value.isTextual() || !value.asText().equals(NAME))
                        faults.add(at + ": must be a constant value " + NAME);
                }
                case "id" -> {
                    if (type(value, "string", at, faults)) pattern(value, ID, at, faults);
                }
                case "parent" -> {
                    if (type(value, "string", at, faults)) pattern(value, PARENT, at, faults);
                }
                case "title", "state", "covers" -> line(value, at, maximum(field), faults);
                case "size", "priority", "role", "severity", "kind", "worker", "risk" -> {
                    final List<String> values = java.util.Objects.requireNonNull(ENUMS.get(field));
                    if (!value.isTextual() || !values.contains(value.asText()))
                        faults.add(
                                at
                                        + ": does not have a value in the enumeration [\""
                                        + String.join("\", \"", values)
                                        + "\"]");
                }
                case "rank" -> integer(value, at, 1, 99999, faults);
                case "openQuestions" -> integer(value, at, 0, 999, faults);
                case "blockedBy" -> {
                    final boolean array = type(value, "array", at, faults);
                    final Set<JsonNode> seen = new HashSet<>();
                    boolean duplicate = false;
                    for (final JsonNode item : value) if (!seen.add(item)) duplicate = true;
                    if (duplicate) faults.add(at + ": must have only unique items in the array");
                    if (array) {
                        if (value.size() > 50)
                            faults.add(
                                    at + ": must have at most 50 items but found " + value.size());
                        for (int i = 0; i < value.size(); i++) {
                            if (type(value.get(i), "string", at + "/" + i, faults))
                                pattern(value.get(i), WAIT, at + "/" + i, faults);
                        }
                    }
                }
                default -> throw new AssertionError(field);
            }
        }
        data.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!PROPERTIES.contains(field))
                                faults.add(
                                        "/: property '"
                                                + field
                                                + "' is not defined in the schema and the schema does not allow additional properties");
                        });
        for (final String field : REQUIRED)
            if (!data.has(field)) faults.add("/: required property '" + field + "' not found");
        // As the schema's if: a pattern holds only for a string, so any other id asks too.
        final JsonNode id = data.get("id");
        if (id != null && (!id.isTextual() || id.asText().contains(".")) && !data.has("parent"))
            faults.add("/: required property 'parent' not found");
        return faults;
    }

    private static int maximum(String field) {
        return switch (field) {
            case "title" -> 120;
            case "covers" -> 1000;
            default -> Integer.MAX_VALUE;
        };
    }

    private static void line(JsonNode value, String at, int max, List<String> faults) {
        if (!type(value, "string", at, faults)) return;
        if (value.asText().isEmpty()) faults.add(at + ": must be at least 1 characters long");
        pattern(value, LINE, at, faults);
        if (value.asText().codePointCount(0, value.asText().length()) > max)
            faults.add(at + ": must be at most " + max + " characters long");
    }

    private static void integer(JsonNode value, String at, int min, int max, List<String> faults) {
        // JSON Schema counts 2.0 as an integer, as Jackson's isIntegralNumber does not.
        final boolean whole =
                value.isIntegralNumber()
                        || (value.isNumber()
                                && value.decimalValue().stripTrailingZeros().scale() <= 0);
        if (!whole) {
            type(value, "integer", at, faults);
            return;
        }
        final java.math.BigDecimal number = value.decimalValue();
        if (number.compareTo(java.math.BigDecimal.valueOf(min)) < 0)
            faults.add(at + ": must have a minimum value of " + min);
        if (number.compareTo(java.math.BigDecimal.valueOf(max)) > 0)
            faults.add(at + ": must have a maximum value of " + max);
    }

    private static boolean type(JsonNode value, String expected, String at, List<String> faults) {
        final String actual =
                value.isNull()
                        ? "null"
                        : value.isObject()
                                ? "object"
                                : value.isArray()
                                        ? "array"
                                        : value.isTextual()
                                                ? "string"
                                                : value.isBoolean()
                                                        ? "boolean"
                                                        : value.isIntegralNumber()
                                                                ? "integer"
                                                                : value.isNumber()
                                                                        ? "number"
                                                                        : "unknown";
        if (actual.equals(expected)) return true;
        faults.add((at.isEmpty() ? "/" : at) + ": " + actual + " found, " + expected + " expected");
        return false;
    }

    private static void pattern(JsonNode value, String regex, String at, List<String> faults) {
        if (!Pattern.compile(regex).matcher(value.asText()).matches())
            faults.add(at + ": does not match the regex pattern " + regex);
    }
}
