package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every answer record generated from {@code openapi/planner.yaml}, written by the mapper the app's
 * Spring Boot sets up, against what its schema says of each field.
 */
class AnswerRecordsTest {

    private static final String GENERATED = "org.fuin.porelo.planner.app.answers";

    private static com.fasterxml.jackson.databind.JsonNode schemas;

    private static Map<String, Class<?>> answers;

    private static JsonMapper mapper;

    @BeforeAll
    static void read() throws Exception {
        final com.fasterxml.jackson.databind.JsonNode description;
        try (InputStream in =
                AnswerRecordsTest.class.getResourceAsStream("/openapi/planner.yaml")) {
            assertThat(in).as("openapi/planner.yaml on the class path").isNotNull();
            description = new ObjectMapper(new YAMLFactory()).readTree(in);
        }
        schemas = description.get("components").get("schemas");
        answers = new LinkedHashMap<>();
        for (final String name : ApiMatchesDescriptionTest.answered(description)) {
            if (schemas.get(name).has("properties") || schemas.get(name).has("allOf")) {
                answers.put(name, Class.forName(GENERATED + "." + name));
            }
        }
        final JsonMapper[] found = new JsonMapper[1];
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .run(context -> found[0] = context.getBean(JsonMapper.class));
        mapper = found[0];
    }

    @Test
    void anAnswerBuiltWithNullInARequiredFieldThatIsNotNullableIsRefusedNamingTheField()
            throws Exception {
        // A null the window does not expect reaching it, where the description promises a value.
        final List<String> checked = new ArrayList<>();
        for (final Map.Entry<String, Class<?>> answer : answers.entrySet()) {
            final RecordComponent[] components = answer.getValue().getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                final String field = components[i].getName();
                final com.fasterxml.jackson.databind.JsonNode property =
                        property(answer.getKey(), field);
                if (!required(answer.getKey()).contains(field) || nullable(property)) {
                    continue;
                }
                final Object[] values = values(answer.getValue());
                values[i] = null;
                assertThat(catchThrowable(() -> construct(answer.getValue(), values)))
                        .as("what refuses %s built with null in %s", answer.getKey(), field)
                        .isInstanceOf(NullPointerException.class)
                        .hasMessageContaining(field);
                checked.add(answer.getKey() + "." + field);
            }
        }
        assertThat(checked)
                .as("the required, not nullable fields tried")
                .contains("Health.status", "Issue.attention", "PlanWork.run", "Landing.commit");
    }

    @Test
    void everyAnswerWritesAnOptionalNullAsLeftOutARequiredNullAsNullAnEmptyListAsEmpty()
            throws Exception {
        // The window reading null or [] where a field meant "not known" or "none recorded".
        final Set<String> leftOut = new TreeSet<>();
        final Set<String> writtenNull = new TreeSet<>();
        for (final Map.Entry<String, Class<?>> answer : answers.entrySet()) {
            final String schema = answer.getKey();
            final Class<?> type = answer.getValue();
            final RecordComponent[] components = type.getRecordComponents();
            final JsonNode full = mapper.valueToTree(construct(type, values(type)));
            assertThat(names(full))
                    .as("the fields %s writes with every field set", schema)
                    .isEqualTo(names(components));
            for (int i = 0; i < components.length; i++) {
                final String field = components[i].getName();
                final boolean required = required(schema).contains(field);
                if (!required || nullable(property(schema, field))) {
                    final Object[] values = values(type);
                    values[i] = null;
                    final JsonNode written = mapper.valueToTree(construct(type, values));
                    if (required) {
                        assertThat(written.path(field).toString())
                                .as("%s.%s, required and null, is written as null", schema, field)
                                .isEqualTo("null");
                        writtenNull.add(schema + "." + field);
                    } else {
                        assertThat(written.has(field))
                                .as("%s.%s, optional and null, is left out", schema, field)
                                .isFalse();
                        leftOut.add(schema + "." + field);
                    }
                }
                if (List.class.isAssignableFrom(components[i].getType())) {
                    final Object[] values = values(type);
                    values[i] = List.of();
                    final JsonNode written = mapper.valueToTree(construct(type, values));
                    assertThat(written.path(field).toString())
                            .as("%s.%s, an empty list, is written as []", schema, field)
                            .isEqualTo("[]");
                }
            }
        }
        assertThat(leftOut)
                .as("the optional fields left out when null")
                .contains(
                        "Issue.rounds",
                        "Issue.findings",
                        "IssueDetail.whatMustBeTrue",
                        "Finding.line",
                        "PlanningStartTask.repository",
                        "PlanningStartTask.result",
                        "PlanWorker.effort",
                        "TaskEnded.landed",
                        "TaskEnded.outcome");
        assertThat(writtenNull)
                .as("the required fields written as null when null")
                .contains(
                        "Story.misfits",
                        "Story.work",
                        "Issue.parent",
                        "PlanWorkTask.ended",
                        "StoryWorkReply.work",
                        "Health.commit");
    }

    @Test
    void everyEnumOfAnAnswerIsWrittenAsTheValuesItsSchemaNames() throws Exception {
        // A value spelled otherwise than the description, which the window does not recognize.
        final List<String> checked = new ArrayList<>();
        for (final Map.Entry<String, Class<?>> answer : answers.entrySet()) {
            final Class<?> type = answer.getValue();
            final RecordComponent[] components = type.getRecordComponents();
            for (int i = 0; i < components.length; i++) {
                final Class<?> raw = components[i].getType();
                if (!raw.isEnum()) {
                    continue;
                }
                final String field = components[i].getName();
                final Set<String> written = new TreeSet<>();
                for (final Object constant : raw.getEnumConstants()) {
                    final Object[] values = values(type);
                    values[i] = constant;
                    written.add(mapper.valueToTree(construct(type, values)).get(field).asString());
                }
                assertThat(written)
                        .as("the values %s.%s is written as", answer.getKey(), field)
                        .isEqualTo(described(property(answer.getKey(), field)));
                checked.add(answer.getKey() + "." + field);
            }
        }
        assertThat(checked)
                .as("the enums tried")
                .contains(
                        "Health.status",
                        "State.kind",
                        "WorkRule.per",
                        "Issue.worker",
                        "Issue.attention",
                        "PlanWorkTask.role",
                        "Finding.severity",
                        "TaskEnded.outcome",
                        "MoveWarning.kind",
                        "StoryWorkProposal.coordinator");
    }

    /** The values the schema names for a property, by its enum or its constant. */
    private static Set<String> described(final com.fasterxml.jackson.databind.JsonNode property) {
        final com.fasterxml.jackson.databind.JsonNode resolved = resolve(property);
        final Set<String> values = new TreeSet<>();
        if (resolved.has("const")) {
            values.add(resolved.get("const").asText());
        }
        resolved.path("enum").forEach(value -> values.add(value.asText()));
        return values;
    }

    /** A record of the given type with every field set. */
    private static Object[] values(final Class<?> type) throws Exception {
        final RecordComponent[] components = type.getRecordComponents();
        final Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = sample(components[i].getGenericType());
        }
        return values;
    }

    private static Object sample(final Type type) throws Exception {
        if (type instanceof ParameterizedType parameterized) {
            final Class<?> raw = (Class<?>) parameterized.getRawType();
            if (raw == List.class) {
                return List.of(sample(parameterized.getActualTypeArguments()[0]));
            }
            if (raw == Map.class) {
                return Map.of("claude", sample(parameterized.getActualTypeArguments()[1]));
            }
            throw new IllegalArgumentException("No sample for " + type);
        }
        final Class<?> raw = (Class<?>) type;
        if (raw == String.class) {
            return "text";
        }
        if (raw == Integer.class) {
            return 1;
        }
        if (raw == Boolean.class) {
            return Boolean.TRUE;
        }
        if (raw == URI.class) {
            return URI.create("https://example.org/a");
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (raw.isRecord()) {
            return construct(raw, values(raw));
        }
        throw new IllegalArgumentException("No sample for " + type);
    }

    private static Object construct(final Class<?> type, final Object[] values) throws Exception {
        final Constructor<?> constructor =
                type.getDeclaredConstructor(
                        Arrays.stream(type.getRecordComponents())
                                .map(RecordComponent::getType)
                                .toArray(Class<?>[]::new));
        try {
            return constructor.newInstance(values);
        } catch (final InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException refused) {
                throw refused;
            }
            throw e;
        }
    }

    /** A schema's property, looked up through the parts it is made of. */
    private static com.fasterxml.jackson.databind.JsonNode property(
            final String schema, final String field) {
        final com.fasterxml.jackson.databind.JsonNode found = find(schemas.get(schema), field);
        if (found == null) {
            throw new IllegalArgumentException(schema + " has no " + field);
        }
        return found;
    }

    private static com.fasterxml.jackson.databind.@Nullable JsonNode find(
            final com.fasterxml.jackson.databind.JsonNode node, final String field) {
        if (node.has("$ref")) {
            return find(schemas.get(name(node)), field);
        }
        if (node.path("properties").has(field)) {
            return node.get("properties").get(field);
        }
        for (final com.fasterxml.jackson.databind.JsonNode part : node.path("allOf")) {
            final com.fasterxml.jackson.databind.JsonNode found = find(part, field);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** The fields a schema requires, through the parts it is made of. */
    private static Set<String> required(final String schema) {
        final com.fasterxml.jackson.databind.JsonNode node = schemas.get(schema);
        final Set<String> required = new TreeSet<>();
        node.path("required").forEach(name -> required.add(name.asText()));
        for (final com.fasterxml.jackson.databind.JsonNode part : node.path("allOf")) {
            if (part.has("$ref")) {
                required.addAll(required(name(part)));
            }
            part.path("required").forEach(name -> required.add(name.asText()));
        }
        return required;
    }

    /** Whether a property's schema lets it be null, by its type or one of its choices. */
    private static boolean nullable(final com.fasterxml.jackson.databind.JsonNode property) {
        for (final com.fasterxml.jackson.databind.JsonNode type : property.path("type")) {
            if (type.asText().equals("null")) {
                return true;
            }
        }
        for (final com.fasterxml.jackson.databind.JsonNode choice : property.path("oneOf")) {
            if (choice.path("type").asText().equals("null")) {
                return true;
            }
        }
        return false;
    }

    private static com.fasterxml.jackson.databind.JsonNode resolve(
            final com.fasterxml.jackson.databind.JsonNode property) {
        if (property.has("$ref")) {
            return resolve(schemas.get(name(property)));
        }
        if (property.has("allOf")) {
            return resolve(property.get("allOf").get(0));
        }
        return property;
    }

    private static String name(final com.fasterxml.jackson.databind.JsonNode ref) {
        return ref.get("$ref").asText().replace("#/components/schemas/", "");
    }

    private static Set<String> names(final JsonNode written) {
        return new TreeSet<>(written.propertyNames());
    }

    private static Set<String> names(final RecordComponent[] components) {
        final Set<String> names = new TreeSet<>();
        Arrays.stream(components).forEach(component -> names.add(component.getName()));
        return names;
    }
}
