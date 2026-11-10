package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.fuin.porelo.planner.app.mock.MockController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/** The window builds against openapi/planner.yaml; the planner must serve exactly what it says. */
class ApiMatchesDescriptionTest {

    private static final Map<String, Class<?>> SCHEMAS =
            Map.ofEntries(
                    Map.entry("ProjectSource", BoardController.ProjectSource.class),
                    Map.entry("Workflow", Answers.Workflow.class),
                    Map.entry("State", Answers.State.class),
                    Map.entry("Phase", Answers.Phase.class),
                    Map.entry("WorkRule", Answers.WorkRule.class),
                    Map.entry("StoryList", Answers.StoryList.class),
                    Map.entry("StoryReply", Answers.StoryReply.class),
                    Map.entry("Story", Answers.Story.class),
                    Map.entry("StoryDetail", Answers.StoryDetail.class),
                    Map.entry("IssueList", Answers.IssueList.class),
                    Map.entry("IssueReply", Answers.IssueReply.class),
                    Map.entry("Issue", Answers.Issue.class),
                    Map.entry("IssueDetail", Answers.IssueDetail.class));

    /** Every controller of the planner, found as Spring finds them, so none escapes the check. */
    private static Set<Class<?>> controllers() throws ClassNotFoundException {
        final ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        final Set<Class<?>> found = new HashSet<>();
        for (final BeanDefinition bean :
                scanner.findCandidateComponents(PlannerApplication.class.getPackageName())) {
            found.add(Class.forName(bean.getBeanClassName()));
        }
        return found;
    }

    private static JsonNode description;

    @BeforeAll
    static void read() throws Exception {
        try (InputStream in =
                ApiMatchesDescriptionTest.class.getResourceAsStream("/openapi/planner.yaml")) {
            assertThat(in).as("openapi/planner.yaml on the class path").isNotNull();
            description = new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }

    @Test
    void everyOperationIsServedAndEveryServedPathIsDescribedWithItsAnswer() throws Exception {
        final Map<String, String> described = new TreeMap<>();
        description
                .get("paths")
                .properties()
                .forEach(
                        path ->
                                path.getValue()
                                        .properties()
                                        .forEach(
                                                operation -> {
                                                    if (Set.of("get", "put", "post", "delete")
                                                            .contains(operation.getKey())) {
                                                        described.put(
                                                                operation.getKey().toUpperCase()
                                                                        + " "
                                                                        + path.getKey(),
                                                                answer(operation.getValue()));
                                                    }
                                                }));
        final Map<String, String> served = new TreeMap<>();
        final Set<Class<?>> controllers = controllers();
        assertThat(controllers)
                .as("the controllers found")
                .contains(BoardController.class, HealthController.class, MockController.class);
        for (final Class<?> controller : controllers) {
            for (final Method method : controller.getDeclaredMethods()) {
                final GetMapping get = method.getAnnotation(GetMapping.class);
                final PutMapping put = method.getAnnotation(PutMapping.class);
                final PostMapping post = method.getAnnotation(PostMapping.class);
                if (get != null) {
                    served.put("GET " + get.value()[0], schemaOf(method.getReturnType()));
                }
                if (put != null) {
                    served.put("PUT " + put.value()[0], "");
                }
                if (post != null) {
                    served.put("POST " + post.value()[0], "");
                }
            }
        }
        assertThat(described)
                .as("the description's operations and their answers, against the controllers'")
                .isNotEmpty()
                .containsKeys("GET /health", "PUT /projects/{project}")
                .isEqualTo(served);
    }

    @Test
    void everyAnswerRecordHasExactlyTheFieldsOfItsSchema() {
        final JsonNode schemas = description.get("components").get("schemas");
        for (final Map.Entry<String, Class<?>> entry : SCHEMAS.entrySet()) {
            final JsonNode schema = schemas.get(entry.getKey());
            assertThat(schema).as("schema %s in the description", entry.getKey()).isNotNull();
            final Set<String> properties = new TreeSet<>();
            final Set<String> required = new TreeSet<>();
            fields(schemas, schema, properties, required);
            final RecordComponent[] components = entry.getValue().getRecordComponents();
            assertThat(names(components))
                    .as("the fields of %s against its schema", entry.getValue().getSimpleName())
                    .isNotEmpty()
                    .isEqualTo(properties);
            final Set<String> always = new TreeSet<>();
            for (final RecordComponent component : components) {
                final JsonInclude include =
                        component.getAccessor().getAnnotation(JsonInclude.class);
                final boolean leftOut =
                        include != null && include.value() == JsonInclude.Include.NON_NULL;
                if (!leftOut) {
                    always.add(component.getName());
                }
            }
            if (!entry.getKey().equals("ProjectSource")) {
                assertThat(always)
                        .as(
                                "the fields %s always writes, against those its schema requires",
                                entry.getValue().getSimpleName())
                        .isEqualTo(required);
            }
        }
    }

    private static String answer(final JsonNode operation) {
        final JsonNode ok = operation.at("/responses/200/content/application~1json/schema/$ref");
        return ok.isMissingNode() ? "" : ok.asText().replace("#/components/schemas/", "");
    }

    private static String schemaOf(final Class<?> type) {
        return SCHEMAS.entrySet().stream()
                .filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(type == Map.class ? "Health" : type.getSimpleName());
    }

    private static void fields(
            final JsonNode schemas,
            final JsonNode schema,
            final Set<String> properties,
            final Set<String> required) {
        if (schema.has("$ref")) {
            fields(
                    schemas,
                    schemas.get(schema.get("$ref").asText().replace("#/components/schemas/", "")),
                    properties,
                    required);
            return;
        }
        if (schema.has("allOf")) {
            schema.get("allOf").forEach(part -> fields(schemas, part, properties, required));
        }
        if (schema.has("properties")) {
            schema.get("properties").fieldNames().forEachRemaining(properties::add);
        }
        if (schema.has("required")) {
            schema.get("required").forEach(name -> required.add(name.asText()));
        }
    }

    private static Set<String> names(final RecordComponent[] components) {
        final Set<String> names = new TreeSet<>();
        Arrays.stream(components).forEach(component -> names.add(component.getName()));
        return names;
    }
}
