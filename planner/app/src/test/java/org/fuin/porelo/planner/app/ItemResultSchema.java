package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

/** Validates batch refusals against the contract clients use to read them. */
final class ItemResultSchema {

    private ItemResultSchema() {}

    /** Checks a refused result and proves the schema rejects a null string parameter. */
    static void matches(final String reply, final int at) throws Exception {
        final ObjectMapper json = new ObjectMapper();
        final JsonNode result = json.readTree(reply).path("results").get(at);
        assertThat(result.path("outcome").asText()).as("the item is refused").isEqualTo("REFUSED");
        final ObjectNode schema = json.createObjectNode();
        try (var in = ItemResultSchema.class.getResourceAsStream("/openapi/planner.yaml")) {
            assertThat(in).as("the published contract is on the classpath").isNotNull();
            final JsonNode description = new ObjectMapper(new YAMLFactory()).readTree(in);
            schema.set("components", description.path("components"));
        }
        schema.put("$ref", "#/components/schemas/ItemResult");
        final var validator =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        assertThat(validator.validate(result))
                .as("the refusal matches planner.yaml's ItemResult")
                .isEmpty();
        result.at("/refusal/parameters")
                .properties()
                .forEach(
                        field ->
                                assertThat(field.getValue().isNull())
                                        .as("parameter %s is not null", field.getKey())
                                        .isFalse());
        final ObjectNode broken = result.deepCopy();
        ((ObjectNode) broken.at("/refusal/parameters")).putNull("project");
        assertThat(validator.validate(broken))
                .as("the schema rejects a null string parameter")
                .isNotEmpty();
    }
}
