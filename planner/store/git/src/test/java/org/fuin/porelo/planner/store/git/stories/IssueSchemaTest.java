package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class IssueSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String VALID =
            """
            {"$schema": "issue-1.json", "id": "BT62", "title": "An Issue's Data In A Head",
             "state": "Implementing", "size": "M", "priority": "Medium", "rank": 1,
             "blockedBy": ["BT46"], "covers": "An issue's data in a JSON head."}
            """;

    // Prevents a head the published schema refuses from passing the library's own check, and back.
    @Test
    void agreesWithTheSchemaOnValidAndFaultyHeads() throws Exception {
        final JsonSchema schema;
        try (var in =
                getClass()
                        .getResourceAsStream(
                                "/org/fuin/porelo/planner/store/git/stories/schemas/issue-1.json")) {
            schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(
                                    in,
                                    SchemaValidatorsConfig.builder()
                                            .locale(Locale.ENGLISH)
                                            .pathType(PathType.JSON_POINTER)
                                            .build());
        }
        final JsonNode valid = JSON.readTree(VALID);
        final List<JsonNode> good = new ArrayList<>();
        final List<JsonNode> bad = new ArrayList<>();
        good.add(valid);
        for (final String patch :
                List.of(
                        "{\"size\": \"-\"}",
                        "{\"size\": \"XL\"}",
                        "{\"rank\": 99999}",
                        "{\"rank\": 2.0}",
                        "{\"blockedBy\": []}",
                        "{\"blockedBy\": [\"BT56.1\", \"B559\"]}",
                        "{\"blockedBy\": [\"porelo#B539\", \"porelo-frontend#F12.1\"]}",
                        "{\"blockedBy\": [\"foo-1#TP01\", \"foo-2#TQ01.1\"]}",
                        "{\"id\": \"BT56.1\", \"parent\": \"BT56\", \"role\": \"builder\"}",
                        "{\"severity\": \"Major\", \"kind\": \"Bug\", \"openQuestions\": 0}",
                        "{\"worker\": \"Agent\"}",
                        "{\"worker\": \"Manual\"}",
                        "{\"state\": \"Not in the workflow\"}",
                        "{\"title\": \"" + "x".repeat(120) + "\"}")) {
            final ObjectNode head = valid.deepCopy();
            head.setAll((ObjectNode) JSON.readTree(patch));
            good.add(head);
        }
        for (final String field :
                List.of(
                        "$schema",
                        "id",
                        "title",
                        "state",
                        "size",
                        "priority",
                        "rank",
                        "blockedBy",
                        "covers")) {
            final ObjectNode missing = valid.deepCopy();
            missing.remove(field);
            bad.add(missing);
            for (final String value : List.of("null", "false", "{}", "[]", "\"\"", "\" x\"")) {
                final ObjectNode wrong = valid.deepCopy();
                wrong.set(field, JSON.readTree(value));
                (field.equals("blockedBy") && value.equals("[]") ? good : bad).add(wrong);
            }
        }
        for (final String patch :
                List.of(
                        "{\"extra\": 1}",
                        "{\"$schema\": \"story-2.json\"}",
                        "{\"id\": \"bt62\"}",
                        "{\"id\": \"BT6\"}",
                        "{\"id\": \"BT56.0\"}",
                        "{\"id\": \"BT56.1\"}",
                        "{\"id\": 62, \"parent\": \"BT56\"}",
                        "{\"parent\": \"BT56.1\"}",
                        "{\"size\": \"XXL\"}",
                        "{\"priority\": \"Urgent\"}",
                        "{\"rank\": 0}",
                        "{\"rank\": 100000}",
                        "{\"rank\": 1.5}",
                        "{\"blockedBy\": [\"BT46\", \"BT46\"]}",
                        "{\"blockedBy\": [\"Porelo#B12\"]}",
                        "{\"blockedBy\": [\"porelo#\"]}",
                        "{\"blockedBy\": [\"foo/1#TP01\"]}",
                        "{\"role\": \"writer\"}",
                        "{\"severity\": \"High\"}",
                        "{\"kind\": \"Story\"}",
                        "{\"openQuestions\": -1}",
                        "{\"worker\": \"agent\"}",
                        "{\"worker\": \"MANUAL\"}",
                        "{\"worker\": \"Robot\"}",
                        "{\"worker\": null}",
                        "{\"title\": \"" + "x".repeat(121) + "\"}",
                        "{\"covers\": \"line\\nline\"}")) {
            final ObjectNode head = valid.deepCopy();
            head.setAll((ObjectNode) JSON.readTree(patch));
            bad.add(head);
        }
        for (final String faulty : List.of("null", "[]", "42", "{}"))
            bad.add(JSON.readTree(faulty));
        assertThat(good).as("the valid reference set").hasSize(16);
        assertThat(bad).as("the faulty reference set").hasSize(9 * 7 - 1 + 27 + 4);

        for (final JsonNode head : good) {
            assertThat(schema.validate(head)).as("the schema takes %s", head).isEmpty();
            assertThat(IssueSchema.faults(head)).as("the library takes %s", head).isEmpty();
        }
        for (final JsonNode head : bad) {
            assertThat(schema.validate(head)).as("the schema refuses %s", head).isNotEmpty();
            assertThat(IssueSchema.faults(head)).as("the library refuses %s", head).isNotEmpty();
        }
    }
}
