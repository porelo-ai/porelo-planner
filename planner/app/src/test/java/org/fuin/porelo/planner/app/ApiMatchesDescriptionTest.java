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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.fuin.porelo.planner.app.mock.MockController;
import org.fuin.porelo.planner.core.NewIdeas;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkRun;
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

    /** The package the answer records are generated into from the description. */
    private static final String GENERATED = "org.fuin.porelo.planner.app.answers";

    /** Each schema's record: an answer's generated one, a body's read by hand. */
    private static final Map<String, Class<?>> SCHEMAS =
            Map.ofEntries(
                    Map.entry("Health", org.fuin.porelo.planner.app.answers.Health.class),
                    Map.entry("Proof", org.fuin.porelo.planner.app.answers.Proof.class),
                    Map.entry("Workflow", org.fuin.porelo.planner.app.answers.Workflow.class),
                    Map.entry("State", org.fuin.porelo.planner.app.answers.State.class),
                    Map.entry("Phase", org.fuin.porelo.planner.app.answers.Phase.class),
                    Map.entry("WorkRule", org.fuin.porelo.planner.app.answers.WorkRule.class),
                    Map.entry("StoryList", org.fuin.porelo.planner.app.answers.StoryList.class),
                    Map.entry("StoryReply", org.fuin.porelo.planner.app.answers.StoryReply.class),
                    Map.entry("Story", org.fuin.porelo.planner.app.answers.Story.class),
                    Map.entry("StoryDetail", org.fuin.porelo.planner.app.answers.StoryDetail.class),
                    Map.entry("IssueList", org.fuin.porelo.planner.app.answers.IssueList.class),
                    Map.entry("IssueReply", org.fuin.porelo.planner.app.answers.IssueReply.class),
                    Map.entry("Issue", org.fuin.porelo.planner.app.answers.Issue.class),
                    Map.entry("IssueDetail", org.fuin.porelo.planner.app.answers.IssueDetail.class),
                    Map.entry("Board", org.fuin.porelo.planner.app.answers.Board.class),
                    Map.entry("Refreshed", org.fuin.porelo.planner.app.answers.Refreshed.class),
                    Map.entry("Moved", org.fuin.porelo.planner.app.answers.Moved.class),
                    Map.entry("StoppedRun", org.fuin.porelo.planner.app.answers.StoppedRun.class),
                    Map.entry("StoppedTask", org.fuin.porelo.planner.app.answers.StoppedTask.class),
                    Map.entry("MovePreview", org.fuin.porelo.planner.app.answers.MovePreview.class),
                    Map.entry("MovedIssue", org.fuin.porelo.planner.app.answers.MovedIssue.class),
                    Map.entry("MovedStory", org.fuin.porelo.planner.app.answers.MovedStory.class),
                    Map.entry("MoveWarning", org.fuin.porelo.planner.app.answers.MoveWarning.class),
                    Map.entry("IssueAdded", org.fuin.porelo.planner.app.answers.IssueAdded.class),
                    Map.entry("IdeaAdded", org.fuin.porelo.planner.app.answers.IdeaAdded.class),
                    Map.entry("WorkerSaved", org.fuin.porelo.planner.app.answers.WorkerSaved.class),
                    Map.entry(
                            "PrioritySaved",
                            org.fuin.porelo.planner.app.answers.PrioritySaved.class),
                    Map.entry(
                            "StoryWorkProposal",
                            org.fuin.porelo.planner.app.answers.StoryWorkProposal.class),
                    Map.entry(
                            "StoryWorkOrder",
                            org.fuin.porelo.planner.app.answers.StoryWorkOrder.class),
                    Map.entry(
                            "PlanRepositoryWork",
                            org.fuin.porelo.planner.app.answers.PlanRepositoryWork.class),
                    Map.entry("PlanWorker", org.fuin.porelo.planner.app.answers.PlanWorker.class),
                    Map.entry(
                            "PlanAssignment",
                            org.fuin.porelo.planner.app.answers.PlanAssignment.class),
                    Map.entry(
                            "IssueReadingRounds",
                            org.fuin.porelo.planner.app.answers.IssueReadingRounds.class),
                    Map.entry(
                            "PlanningStartTask",
                            org.fuin.porelo.planner.app.answers.PlanningStartTask.class),
                    Map.entry("TaskEnded", org.fuin.porelo.planner.app.answers.TaskEnded.class),
                    Map.entry("Landing", org.fuin.porelo.planner.app.answers.Landing.class),
                    Map.entry(
                            "StoryWorkReply",
                            org.fuin.porelo.planner.app.answers.StoryWorkReply.class),
                    Map.entry(
                            "TaskEventReply",
                            org.fuin.porelo.planner.app.answers.TaskEventReply.class),
                    Map.entry(
                            "ReadingReply", org.fuin.porelo.planner.app.answers.ReadingReply.class),
                    Map.entry("PlanWork", org.fuin.porelo.planner.app.answers.PlanWork.class),
                    Map.entry(
                            "PlanWorkTask", org.fuin.porelo.planner.app.answers.PlanWorkTask.class),
                    Map.entry(
                            "PlanWorkIssue",
                            org.fuin.porelo.planner.app.answers.PlanWorkIssue.class),
                    Map.entry("Finding", org.fuin.porelo.planner.app.answers.Finding.class),
                    Map.entry("ProjectSource", BoardController.ProjectSource.class),
                    Map.entry("Level", org.fuin.porelo.planner.app.answers.Level.class),
                    Map.entry("IdeaRequest", WorkRequests.IdeaRequest.class),
                    Map.entry("Idea", NewIdeas.Idea.class),
                    Map.entry("IdeaStory", NewIdeas.Story.class),
                    Map.entry("WorkerRequest", WorkRequests.WorkerRequest.class),
                    Map.entry("PriorityRequest", WorkRequests.PriorityRequest.class),
                    Map.entry("StoryWorkProposalRequest", WorkRequests.Proposal.class),
                    Map.entry("StoryWorkRequest", WorkRequests.Request.class),
                    Map.entry("PassOver", WorkRun.PassOver.class),
                    Map.entry("StopRequest", WorkRequests.StopRequest.class),
                    Map.entry("TaskEventRequest", WorkRequests.TaskEventRequest.class),
                    Map.entry("ReadingRequest", WorkRequests.ReadingRequest.class));

    /** The bodies' own records of schemas an answer carries too, each read by hand. */
    private static final Map<String, Class<?>> READ_BY_HAND =
            Map.ofEntries(
                    Map.entry("PlanRepositoryWork", WorkRequests.Repository.class),
                    Map.entry("PlanWorker", WorkRequests.Main.class),
                    Map.entry("PlanAssignment", WorkRequests.Assignment.class),
                    Map.entry("IssueReadingRounds", WorkRequests.IssueLimit.class),
                    Map.entry("TaskEnded", WorkRequests.TaskEnded.class),
                    Map.entry("Landing", NextStep.Landing.class),
                    Map.entry("Level", LogLevelController.Chosen.class));

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
                    served.put(
                            "PUT " + path(put.value(), put.path()),
                            schemaOf(method.getReturnType()));
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
        final List<Map.Entry<String, Class<?>>> records = new ArrayList<>(SCHEMAS.entrySet());
        records.addAll(READ_BY_HAND.entrySet());
        for (final Map.Entry<String, Class<?>> entry : records) {
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
    void everySchemaAnOperationAnswersIsAGeneratedRecord() {
        // A hand-written answer record slipping back in drifts from the description unseen.
        final JsonNode schemas = description.get("components").get("schemas");
        final Set<String> answered = answered(description);
        assertThat(answered)
                .as("the schemas the operations answer, nested ones too")
                .contains("Health", "Board", "Issue", "PlanWork", "TaskEnded", "Landing");
        for (final String schema : answered) {
            if (!schemas.path(schema).has("properties") && !schemas.path(schema).has("allOf")) {
                continue;
            }
            final Class<?> record = SCHEMAS.get(schema);
            assertThat(record == null ? "none" : record.getPackageName())
                    .as("the package of the record answering %s", schema)
                    .isEqualTo(GENERATED);
        }
    }

    /**
     * The schemas the operations of a description answer with success, nested ones too.
     *
     * @param description the description
     * @return the schemas' names
     */
    static Set<String> answered(final JsonNode description) {
        final JsonNode schemas = description.get("components").get("schemas");
        final Set<String> answered = new TreeSet<>();
        description
                .get("paths")
                .forEach(
                        path ->
                                path.forEach(
                                        operation ->
                                                operation
                                                        .path("responses")
                                                        .properties()
                                                        .forEach(
                                                                response -> {
                                                                    if (response.getKey()
                                                                            .startsWith("2")) {
                                                                        reached(
                                                                                schemas,
                                                                                response.getValue(),
                                                                                answered);
                                                                    }
                                                                })));
        // The events stream names its data, a Board, in words only.
        reached(
                schemas,
                new ObjectMapper().createObjectNode().put("$ref", "#/components/schemas/Board"),
                answered);
        return answered;
    }

    /** Collects every schema a part of the description refers to, however deep. */
    private static void reached(
            final JsonNode schemas, final JsonNode part, final Set<String> found) {
        if (part.isObject() && part.has("$ref")) {
            final String ref = part.get("$ref").asText();
            if (ref.startsWith("#/components/schemas/")) {
                final String name = ref.replace("#/components/schemas/", "");
                if (found.add(name)) {
                    reached(schemas, schemas.get(name), found);
                }
            }
            return;
        }
        part.forEach(child -> reached(schemas, child, found));
    }

    @Test
    void theHealthPatternsAreTheFormsThePlannerChecks() {
        // A description that lets the window accept a version or commit the planner refuses, or
        // the reverse.
        final JsonNode health = description.at("/components/schemas/Health/properties");
        assertThat(health.at("/version/pattern").asText())
                .as("the description's version pattern against PlannerBuild's")
                .isEqualTo("^" + PlannerBuild.VERSION.pattern() + "$");
        assertThat(health.at("/commit/pattern").asText())
                .as("the description's commit pattern against PlannerBuild's")
                .isEqualTo("^" + PlannerBuild.COMMIT.pattern() + "$");
    }

    @Test
    void theTextFormatPatternIsTheOneTheStoreChecks() {
        // A format the store gives that the description refuses breaks the window's reading of
        // every story and issue.
        assertThat(description.at("/components/schemas/TextFormat/pattern").asText())
                .as("the description's text format pattern against TextFormat's")
                .isEqualTo("^" + org.fuin.porelo.planner.store.TextFormat.NAME.pattern() + "$");
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
    void everyNestedWorkFieldReadByHandMatchesItsWireType() {
        // Flat field checks alone miss an array or nested record becoming the wrong wire shape.
        final JsonNode schemas = description.get("components").get("schemas");
        final Set<Class<?>> workTypes =
                Set.of(
                        WorkRequests.Proposal.class,
                        WorkRequests.Request.class,
                        WorkRun.PassOver.class,
                        WorkRequests.StopRequest.class,
                        WorkRequests.Repository.class,
                        WorkRequests.Main.class,
                        WorkRequests.Assignment.class,
                        WorkRequests.IssueLimit.class,
                        WorkRequests.TaskEventRequest.class,
                        WorkRequests.TaskEnded.class,
                        NextStep.Landing.class,
                        WorkRequests.ReadingRequest.class);
        final Set<Class<?>> described = new HashSet<>(SCHEMAS.values());
        described.addAll(READ_BY_HAND.values());
        assertThat(workTypes)
                .as("every work body and nested record read by hand participates")
                .hasSize(12)
                .isSubsetOf(described);
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
    }

    @Test
    void theHandInOfAnIdeaDescribesItsBodyItsAnswerAndEveryRefusal() {
        // A refusal or a member missing from the contract lets the window take a refused idea as
        // saved, or send an idea the planner reads otherwise.
        final JsonNode operation =
                description.path("paths").path("/projects/{project}/ideas").path("post");
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                .as("the hand-in's body")
                .isEqualTo("#/components/schemas/IdeaRequest");
        assertThat(operation.at("/responses/201/content/application~1json/schema/$ref").asText())
                .as("the hand-in's answer")
                .isEqualTo("#/components/schemas/IdeaAdded");
        final Set<String> statuses = new TreeSet<>();
        operation.path("responses").fieldNames().forEachRemaining(statuses::add);
        assertThat(statuses)
                .as("every answer of the hand-in")
                .isEqualTo(
                        Set.of(
                                "201", "400", "401", "404", "409", "422", "501", "502", "503",
                                "504"));
        assertThat(operation.at("/responses/422/$ref").asText())
                .as("a refused idea's answer")
                .isEqualTo("#/components/responses/IdeaRefused");
        final JsonNode schemas = description.path("components").path("schemas");
        final JsonNode issue = schemas.path("IdeaIssue");
        final Set<String> properties = new TreeSet<>();
        issue.path("properties").fieldNames().forEachRemaining(properties::add);
        assertThat(properties)
                .as("an idea's issue has exactly the members the planner reads")
                .isEqualTo(names(NewIdeas.Issue.class.getRecordComponents()));
        final Set<String> required = new TreeSet<>();
        issue.path("required").forEach(field -> required.add(field.asText()));
        assertThat(required)
                .as("only the size of an idea's issue may be left out")
                .isEqualTo(Set.of("repository", "title", "mustBeTrue", "worker"));
        assertThat(schemas.at("/Idea/properties/issues/items/$ref").asText())
                .as("the idea's issues")
                .isEqualTo("#/components/schemas/IdeaIssue");
        assertThat(schemas.at("/Problem/properties/parameters/properties/faults/type").asText())
                .as("an idea's faults are a list")
                .isEqualTo("array");
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
            final JsonNode list =
                    field.has("$ref")
                            ? schemas.get(
                                    field.get("$ref").asText().replace("#/components/schemas/", ""))
                            : field;
            assertThat(list.path("type").asText()).as("%s is a list", name).isEqualTo("array");
            wireType(
                    schemas,
                    list.get("items"),
                    parameterized.getActualTypeArguments()[0],
                    name + "[]");
            return;
        }
        final Class<?> raw = (Class<?>) type;
        if (raw.isRecord()) {
            assertThat(field.path("$ref").asText())
                    .as("%s refers to its complete nested schema", name)
                    .isEqualTo("#/components/schemas/" + schemaOf(raw));
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
        assertThat(stop.at("/requestBody/required").asBoolean())
                .as("stop takes an empty body still")
                .isFalse();
        assertThat(stop.at("/requestBody/content/application~1json/schema/$ref").asText())
                .as("stop's body names at most the run")
                .isEqualTo("#/components/schemas/StopRequest");
        final JsonNode event = description.path("components").path("schemas").path("TaskEnded");
        assertThat(event.path("required").toString())
                .as("all of Porelo's facts are required")
                .contains("at", "finished", "why", "answer", "unpushed")
                .doesNotContain("outcome");
    }

    @Test
    void theDescribedOutcomesAndReasonsAreExactlyThoseThePlannerTakesAndRaises() {
        // A window built against the description would send an outcome the planner refuses, so a
        // blocked end is never told, or refuse an answer raising a reason the description lacks.
        final JsonNode schemas = description.path("components").path("schemas");
        final Set<String> outcomes = new TreeSet<>();
        schemas.at("/TaskEnded/properties/outcome/enum")
                .forEach(outcome -> outcomes.add(outcome.asText()));
        final Set<String> declared = new TreeSet<>();
        Arrays.stream(WorkEnds.Declared.values()).forEach(d -> declared.add(d.name()));
        assertThat(outcomes).as("the outcomes a task may declare").isEqualTo(declared);
        final Set<String> raised = new TreeSet<>();
        Arrays.stream(WorkEnds.Reason.values()).forEach(reason -> raised.add(reason.name()));
        for (final String reasons :
                List.of(
                        "/PlanWorkIssue/properties/reason/enum",
                        "/Issue/properties/attention/enum")) {
            final Set<String> described = new TreeSet<>();
            schemas.at(reasons).forEach(reason -> described.add(reason.asText()));
            assertThat(described).as("the reasons described at %s", reasons).containsAll(raised);
        }
    }

    @Test
    void theDescribedReasonsAStartPassesOverAreExactlyThoseThePlannerTakes() {
        // A window built against the description would offer a Start the planner refuses, or the
        // planner would start work over a reason the description keeps out, such as a block.
        final Set<String> described = new TreeSet<>();
        description
                .path("components")
                .path("schemas")
                .at("/PassOver/properties/reason/enum")
                .forEach(reason -> described.add(reason.asText()));
        assertThat(described)
                .as("the reasons a Start may pass over")
                .isEqualTo(new TreeSet<>(WorkRun.PASSABLE))
                .contains("FAILED")
                .doesNotContain("BLOCKED");
    }

    @Test
    void theDescribedEventsAndLandingNamesAreExactlyThoseThePlannerTakes() {
        // A window built against the description would send a landing the planner refuses, or
        // the planner would take one the description forbids, and no reader would ever start.
        final JsonNode schemas = description.path("components").path("schemas");
        final Set<String> events = new TreeSet<>();
        schemas.at("/TaskEventRequest/properties/event/enum")
                .forEach(event -> events.add(event.asText()));
        final Set<String> taken = new TreeSet<>();
        Arrays.stream(WorkRun.Event.values()).forEach(event -> taken.add(event.name()));
        assertThat(events).as("the events the window may report").isEqualTo(taken);
        final java.util.regex.Pattern name =
                java.util.regex.Pattern.compile(schemas.at("/RefName/pattern").asText());
        final java.util.regex.Pattern ref =
                java.util.regex.Pattern.compile(
                        schemas.at("/Landing/properties/ref/pattern").asText());
        final java.util.regex.Pattern commit =
                java.util.regex.Pattern.compile(
                        schemas.at("/Landing/properties/commit/pattern").asText());
        final String sha = "0123456789abcdef0123456789abcdef01234567";
        for (final String sample :
                List.of(
                        "main",
                        "feature/a-1",
                        "refs/landed/t/main",
                        "origin/landed/t/main",
                        "x".repeat(255),
                        "x".repeat(256),
                        "fix-ä",
                        "ä".repeat(255),
                        "ä".repeat(256),
                        "a\u2028b",
                        "a\u2029b",
                        "a\u0085b",
                        "a\u2028..b",
                        "a\u2028.lock",
                        "refs/a\u2029..b",
                        "main\n",
                        "a..b",
                        "a b",
                        "a\tb",
                        "a\u007fb",
                        "a~b",
                        "a^b",
                        "a:b",
                        "a?b",
                        "a*b",
                        "a[b",
                        "a\\b",
                        "-a",
                        "/a",
                        "a/",
                        "a.lock",
                        "refs/a..b",
                        "heads/main")) {
            // A described length counts characters (Unicode code points), as JSON Schema does.
            final int length = sample.codePointCount(0, sample.length());
            final boolean described =
                    name.matcher(sample).matches()
                            && length <= schemas.at("/RefName/maxLength").asInt();
            assertThat(described)
                    .as("the description and the planner agree on the name %s", sample)
                    .isEqualTo(WorkRun.isBranch(sample));
            final boolean describedRef =
                    ref.matcher(sample).matches()
                            && length <= schemas.at("/Landing/properties/ref/maxLength").asInt();
            assertThat(describedRef)
                    .as("the description and the planner agree on the ref %s", sample)
                    .isEqualTo(
                            WorkRun.isLanding(
                                    new NextStep.Landing("main", sample, "origin/main", sha)));
        }
        for (final String sample :
                List.of(sha, "0123456789abcdef".repeat(4), sha.toUpperCase(), sha + "0", "")) {
            assertThat(commit.matcher(sample).matches())
                    .as("the description and the planner agree on the commit %s", sample)
                    .isEqualTo(
                            WorkRun.isLanding(
                                    new NextStep.Landing(
                                            "main", "refs/main", "origin/main", sample)));
        }
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
        return Stream.concat(SCHEMAS.entrySet().stream(), READ_BY_HAND.entrySet().stream())
                .filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(type.getSimpleName());
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
