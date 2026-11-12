package org.fuin.porelo.planner.store.git.stories;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The constraints documented by story-1.json, with the command's existing diagnostics. */
final class StorySchema {
    private static final List<String> REQUIRED =
            List.of("id", "title", "state", "priority", "gain", "test", "issues", "tested");
    private static final List<String> PROPERTIES =
            List.of("id", "title", "priority", "gain", "test", "issues", "tested", "state");
    private static final String ID = "^ST[1-9][0-9]{0,4}$";
    private static final String LINE = "^[^\\s](.*[^\\s])?$";
    static final String ISSUE = "^[a-z0-9][a-z0-9._-]{0,99}#[A-Z]{1,3}[0-9]{2,5}$";
    private static final String DAY = "^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$";
    private static final List<String> PRIORITIES =
            List.of("Highest", "High", "Medium", "Low", "Lowest");

    private StorySchema() {}

    static List<String> faults(JsonNode data) {
        return faults(data, data.has("$schema") ? 2 : 1);
    }

    static List<String> faults(JsonNode data, int version) {
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
                case "id" -> {
                    if (type(value, "string", at, faults)) pattern(value, ID, at, faults);
                }
                case "title", "gain", "test" -> {
                    if (type(value, "string", at, faults)) {
                        if (value.asText().isEmpty())
                            faults.add(at + ": must be at least 1 characters long");
                        pattern(value, LINE, at, faults);
                        final int max = field.equals("title") ? 120 : 400;
                        if (value.asText().codePointCount(0, value.asText().length()) > max)
                            faults.add(at + ": must be at most " + max + " characters long");
                    }
                }
                case "state" -> {
                    if (type(value, "string", at, faults)) {
                        if (value.asText().isEmpty())
                            faults.add(at + ": must be at least 1 characters long");
                        pattern(value, LINE, at, faults);
                    }
                }
                case "priority" -> {
                    final List<String> values = PRIORITIES;
                    if (!value.isTextual() || !values.contains(value.asText()))
                        faults.add(
                                at
                                        + ": does not have a value in the enumeration "
                                        + "[\""
                                        + String.join("\", \"", values)
                                        + "\"]");
                }
                case "issues" -> {
                    final boolean array = type(value, "array", at, faults);
                    final Set<JsonNode> seen = new HashSet<>();
                    boolean duplicate = false;
                    for (final JsonNode item : value) if (!seen.add(item)) duplicate = true;
                    if (duplicate) faults.add(at + ": must have only unique items in the array");
                    if (array) {
                        if (value.size() > 200)
                            faults.add(
                                    at + ": must have at most 200 items but found " + value.size());
                        for (int i = 0; i < value.size(); i++) {
                            if (type(value.get(i), "string", at + "/" + i, faults))
                                pattern(value.get(i), ISSUE, at + "/" + i, faults);
                        }
                    }
                }
                case "tested" -> {
                    if (!value.isNull()
                            && !(value.isTextual()
                                    && Pattern.compile(DAY).matcher(value.asText()).matches())) {
                        type(value, "null", at, faults);
                        if (type(value, "string", at, faults)) pattern(value, DAY, at, faults);
                    }
                }
                default -> throw new AssertionError(field);
            }
        }
        data.fieldNames()
                .forEachRemaining(
                        field -> {
                            if (!PROPERTIES.contains(field)
                                    && !(version == 2 && field.equals("$schema")))
                                faults.add(
                                        "/: property '"
                                                + field
                                                + "' is not defined in the schema and the schema does not allow additional properties");
                        });
        for (final String field : REQUIRED)
            if (!(version == 2 && field.equals("state")) && !data.has(field))
                faults.add("/: required property '" + field + "' not found");
        if (version == 2) {
            if (!data.has("$schema")) faults.add("/: required property '$schema' not found");
            else if (!data.path("$schema").asText().equals("story-2.json")
                    || !data.path("$schema").isTextual())
                faults.add("/$schema: must be a constant value story-2.json");
            if (data.path("issues").isArray()
                    && data.path("issues").isEmpty()
                    && !data.has("state")) faults.add("/: required property 'state' not found");
        }
        return faults;
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
