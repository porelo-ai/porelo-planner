package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
                    Map.entry("IssueDetail", Answers.IssueDetail.class),
                    Map.entry("Board", Answers.Board.class),
                    Map.entry("Refreshed", Answers.Refreshed.class),
                    Map.entry("Moved", Answers.Moved.class),
                    Map.entry("IssueAdded", Answers.IssueAdded.class),
                    Map.entry("StoryWorkProposalRequest", WorkRequests.Proposal.class),
                    Map.entry("StoryWorkRequest", WorkRequests.Request.class),
                    Map.entry("StoryWorkProposal", WorkAnswers.Proposal.class),
                    Map.entry("StoryWorkOrder", WorkAnswers.Order.class),
                    Map.entry("PlanRepositoryWork", WorkAnswers.Repository.class),
                    Map.entry("PlanWorker", WorkAnswers.Worker.class),
                    Map.entry("PlanAssignment", WorkAnswers.Assignment.class),
                    Map.entry("IssueReadingRounds", WorkAnswers.IssueRounds.class),
                    Map.entry("PlanningStartTask", WorkAnswers.StartTask.class),
                    Map.entry("TaskEventRequest", WorkRequests.TaskEventRequest.class),
                    Map.entry("TaskEnded", WorkRequests.TaskEnded.class),
                    Map.entry("ReadingRequest", WorkRequests.ReadingRequest.class),
                    Map.entry("StoryWorkReply", WorkAnswers.StoryWorkReply.class),
                    Map.entry("TaskEventReply", WorkAnswers.TaskEventReply.class),
                    Map.entry("ReadingReply", WorkAnswers.ReadingReply.class),
                    Map.entry("PlanWork", WorkAnswers.PlanWork.class),
                    Map.entry("PlanWorkTask", WorkAnswers.PlanWorkTask.class),
                    Map.entry("PlanWorkIssue", WorkAnswers.PlanWorkIssue.class),
                    Map.entry("Finding", WorkAnswers.Finding.class));

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
        // An undocumented or missing operation breaks the window's calls.
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
                    served.put(
                            "GET " + path(get.value(), get.path()),
                            schemaOf(method.getReturnType()));
                }
                if (put != null) {
                    served.put("PUT " + path(put.value(), put.path()), "");
                }
                if (post != null) {
                    served.put(
                            "POST " + path(post.value(), post.path()),
                            schemaOf(method.getReturnType()));
                }
            }
        }
        assertThat(described)
                .as("the description's operations and their answers, against the controllers'")
                .isNotEmpty()
                .containsKeys(
                        "GET /health",
                        "PUT /projects/{project}",
                        "POST /projects/{project}/stories/{story}/proposal",
                        "POST /projects/{project}/stories/{story}/work")
                .isEqualTo(served);
    }

    @Test
    void everyAnswerRecordHasExactlyTheFieldsOfItsSchema() {
        // Missing or unexpectedly required fields can silently change the window's decisions.
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

    @Test
    void bothWorkOperationsDescribeTheirBodiesAndEveryRefusal() {
        // A refusal omitted from the contract can be treated as a successful decision by a client.
        for (final String suffix : List.of("proposal", "work")) {
            final JsonNode operation =
                    description
                            .get("paths")
                            .get("/projects/{project}/stories/{story}/" + suffix)
                            .get("post");
            assertThat(operation.at("/requestBody/required").asBoolean())
                    .as("both operations need a decision body")
                    .isTrue();
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                    .as("the body read by %s", suffix)
                    .isEqualTo(
                            "#/components/schemas/"
                                    + (suffix.equals("proposal")
                                            ? "StoryWorkProposalRequest"
                                            : "StoryWorkRequest"));
            final Set<String> statuses = new TreeSet<>();
            operation.get("responses").fieldNames().forEachRemaining(statuses::add);
            assertThat(statuses)
                    .as("documented answers for %s", suffix)
                    .isEqualTo(
                            suffix.equals("proposal")
                                    ? Set.of("200", "400", "401", "404", "409", "502", "503", "504")
                                    : Set.of(
                                            "200", "400", "401", "404", "409", "502", "503",
                                            "504"));
        }
        assertThat(
                        description
                                .at(
                                        "/components/schemas/Problem/properties/parameters/properties/tasks/type")
                                .asText())
                .as("running task names are an array rather than a string")
                .isEqualTo("array");
        assertThat(
                        description
                                .at(
                                        "/components/schemas/Problem/properties/parameters/properties/tasks/items/type")
                                .asText())
                .as("each running task has a string name")
                .isEqualTo("string");
    }

    @Test
    void everyNestedWorkFieldMatchesItsWireType() {
        // Flat field checks alone miss an array or nested record becoming the wrong wire shape.
        final JsonNode schemas = description.get("components").get("schemas");
        final Set<Class<?>> workTypes =
                Set.of(
                        WorkRequests.Proposal.class,
                        WorkRequests.Request.class,
                        WorkAnswers.Proposal.class,
                        WorkAnswers.Order.class,
                        WorkAnswers.Repository.class,
                        WorkAnswers.Worker.class,
                        WorkAnswers.Assignment.class,
                        WorkAnswers.IssueRounds.class,
                        WorkAnswers.StartTask.class,
                        WorkRequests.TaskEventRequest.class,
                        WorkRequests.TaskEnded.class,
                        WorkRequests.ReadingRequest.class,
                        WorkAnswers.StoryWorkReply.class,
                        WorkAnswers.TaskEventReply.class,
                        WorkAnswers.ReadingReply.class,
                        WorkAnswers.PlanWork.class,
                        WorkAnswers.PlanWorkTask.class,
                        WorkAnswers.PlanWorkIssue.class,
                        WorkAnswers.Finding.class);
        assertThat(workTypes)
                .as("every work request, response and nested wire record participates")
                .hasSize(19)
                .isSubsetOf(SCHEMAS.values());
        for (final Class<?> type : workTypes) {
            final JsonNode properties = schemas.get(schemaOf(type)).get("properties");
            for (final RecordComponent component : type.getRecordComponents()) {
                wireType(
                        schemas,
                        properties.get(component.getName()),
                        component.getGenericType(),
                        type.getSimpleName() + "." + component.getName());
            }
        }
        assertThat(names(WorkRequests.IssueLimit.class.getRecordComponents()))
                .as("the input issue-limit record shares the answer's complete field set")
                .isEqualTo(names(WorkAnswers.IssueRounds.class.getRecordComponents()));
        for (final RecordComponent component :
                WorkRequests.IssueLimit.class.getRecordComponents()) {
            wireType(
                    schemas,
                    schemas.get("IssueReadingRounds").get("properties").get(component.getName()),
                    component.getGenericType(),
                    "IssueLimit." + component.getName());
        }
    }

    private static void wireType(
            final JsonNode schemas, final JsonNode field, final Type type, final String name) {
        if (field.has("oneOf")) {
            final JsonNode value =
                    java.util.stream.StreamSupport.stream(field.get("oneOf").spliterator(), false)
                            .filter(option -> !option.path("type").asText().equals("null"))
                            .findFirst()
                            .orElseThrow();
            wireType(schemas, value, type, name);
            return;
        }
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == List.class) {
            assertThat(field.path("type").asText()).as("%s is a list", name).isEqualTo("array");
            wireType(
                    schemas,
                    field.get("items"),
                    parameterized.getActualTypeArguments()[0],
                    name + "[]");
            return;
        }
        final Class<?> raw = (Class<?>) type;
        if (raw.isRecord()) {
            assertThat(field.path("$ref").asText())
                    .as("%s refers to its complete nested schema", name)
                    .isEqualTo(
                            "#/components/schemas/"
                                    + (raw == WorkRequests.IssueLimit.class
                                            ? "IssueReadingRounds"
                                            : schemaOf(raw)));
            return;
        }
        final JsonNode resolved =
                field.has("$ref")
                        ? schemas.get(
                                field.get("$ref").asText().replace("#/components/schemas/", ""))
                        : field;
        final JsonNode declared = resolved.path("type");
        final String primitive = declared.isArray() ? declared.get(0).asText() : declared.asText();
        assertThat(primitive)
                .as("%s has the correct primitive wire type", name)
                .isEqualTo(
                        raw == int.class || raw == Integer.class || raw == Number.class
                                ? "integer"
                                : raw == boolean.class ? "boolean" : "string");
    }

    @Test
    void taskReportsRequireAMachineAndWorkTasksKeepAnExplicitNullableMachine() {
        // Prevents missing machine routing and confusing an unknown machine with a name.
        final JsonNode schemas = description.path("components").path("schemas");
        for (final String name : List.of("TaskEventRequest", "PlanWorkTask")) {
            final JsonNode schema = schemas.path(name);
            final Set<String> required = new TreeSet<>();
            schema.path("required").forEach(field -> required.add(field.asText()));
            assertThat(required)
                    .as("%s always carries the machine field", name)
                    .contains("machine");
            final JsonNode machine = schema.path("properties").path("machine");
            assertThat(machine.path("minLength").asInt())
                    .as("%s excludes an empty machine name", name)
                    .isEqualTo(1);
            assertThat(machine.path("maxLength").asInt())
                    .as("%s bounds the machine name in characters", name)
                    .isEqualTo(128);
            final var pattern = java.util.regex.Pattern.compile(machine.path("pattern").asText());
            for (final String valid :
                    List.of("WSL Ubuntu Äpfel", "😀".repeat(128), " ", "../machine")) {
                assertThat(pattern.matcher(valid).find())
                        .as("%s permits a verbatim display name", name)
                        .isTrue();
            }
            for (final String invalid :
                    List.of(
                            "line\nbreak",
                            "line\rbreak",
                            "tab\tname",
                            "nul\u0000name",
                            "delete\u007fname",
                            "control\u0085name",
                            "line\u2028break",
                            "paragraph\u2029break",
                            "trailing\n",
                            "trailing\r",
                            "trailing\u2028",
                            "trailing\u2029")) {
                assertThat(pattern.matcher(invalid).find())
                        .as("%s excludes controls and line breaks", name)
                        .isFalse();
            }
        }
        assertThat(schemas.at("/TaskEventRequest/properties/machine/type").asText())
                .as("reports require a string machine name")
                .isEqualTo("string");
        final Set<String> types = new TreeSet<>();
        schemas.at("/PlanWorkTask/properties/machine/type")
                .forEach(type -> types.add(type.asText()));
        assertThat(types)
                .as("a work task always answers either a machine name or null")
                .containsExactlyInAnyOrder("string", "null");
    }

    @Test
    void lifecycleBodiesAndAllNamedRefusalsArePartOfTheContract() {
        // Prevents a window from treating an unknown report or lost record as permission to start
        // work.
        for (final String path :
                List.of(
                        "/projects/{project}/tasks/{task}/events",
                        "/projects/{project}/issues/{issue}/reading")) {
            final JsonNode operation = description.path("paths").path(path).path("post");
            final Set<String> statuses = new TreeSet<>();
            operation.path("responses").fieldNames().forEachRemaining(statuses::add);
            assertThat(statuses)
                    .as("every lifecycle refusal at %s is documented", path)
                    .containsExactlyInAnyOrder(
                            "200", "400", "401", "404", "409", "422", "501", "502", "503", "504");
        }
        final JsonNode stop =
                description
                        .path("paths")
                        .path("/projects/{project}/stories/{story}/work/stop")
                        .path("post");
        assertThat(stop.has("requestBody")).as("stop takes no JSON object").isFalse();
        final JsonNode event = description.path("components").path("schemas").path("TaskEnded");
        assertThat(event.path("properties").has("outcome"))
                .as("the window sends Porelo's end unmapped")
                .isFalse();
        assertThat(event.path("required").toString())
                .as("all of Porelo's facts are required")
                .contains("at", "finished", "why", "answer", "unpushed");
    }

    private static String answer(final JsonNode operation) {
        final JsonNode ok = operation.at("/responses/200/content/application~1json/schema/$ref");
        return ok.isMissingNode() ? "" : ok.asText().replace("#/components/schemas/", "");
    }

    private static String path(final String[] value, final String[] path) {
        return value.length > 0 ? value[0] : path[0];
    }

    private static String schemaOf(final Class<?> type) {
        if (type == ResponseEntity.class || type == SseEmitter.class) {
            return "";
        }
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
