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
import org.springframework.web.bind.annotation.DeleteMapping;
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
                    Map.entry("Tracker", org.fuin.porelo.planner.app.answers.Tracker.class),
                    Map.entry(
                            "ItemsDirectoryRequest", TrackerController.ItemsDirectoryRequest.class),
                    Map.entry("State", org.fuin.porelo.planner.app.answers.State.class),
                    Map.entry("Phase", org.fuin.porelo.planner.app.answers.Phase.class),
                    Map.entry("WorkRule", org.fuin.porelo.planner.app.answers.WorkRule.class),
                    Map.entry("StoryList", org.fuin.porelo.planner.app.answers.StoryList.class),
                    Map.entry("StoryReply", org.fuin.porelo.planner.app.answers.StoryReply.class),
                    Map.entry("Story", org.fuin.porelo.planner.app.answers.Story.class),
                    Map.entry("OpenWork", org.fuin.porelo.planner.app.answers.OpenWork.class),
                    Map.entry("OpenCount", org.fuin.porelo.planner.app.answers.OpenCount.class),
                    Map.entry("StoryDetail", org.fuin.porelo.planner.app.answers.StoryDetail.class),
                    Map.entry("IssueList", org.fuin.porelo.planner.app.answers.IssueList.class),
                    Map.entry("IssueReply", org.fuin.porelo.planner.app.answers.IssueReply.class),
                    Map.entry("Issue", org.fuin.porelo.planner.app.answers.Issue.class),
                    Map.entry("Wait", org.fuin.porelo.planner.app.answers.Wait.class),
                    Map.entry("IssueDetail", org.fuin.porelo.planner.app.answers.IssueDetail.class),
                    Map.entry("ItemWarning", org.fuin.porelo.planner.app.answers.ItemWarning.class),
                    Map.entry("Correction", org.fuin.porelo.planner.app.answers.Correction.class),
                    Map.entry(
                            "RepositoryWarning",
                            org.fuin.porelo.planner.app.answers.RepositoryWarning.class),
                    Map.entry("LabelCall", org.fuin.porelo.planner.app.answers.LabelCall.class),
                    Map.entry(
                            "CorrectionPreview",
                            org.fuin.porelo.planner.app.answers.CorrectionPreview.class),
                    Map.entry(
                            "CorrectionSaved",
                            org.fuin.porelo.planner.app.answers.CorrectionSaved.class),
                    Map.entry("CorrectionRequest", BoardController.CorrectionRequest.class),
                    Map.entry("CorrectionConfirm", BoardController.CorrectionConfirm.class),
                    Map.entry("HeldIssue", org.fuin.porelo.planner.app.answers.HeldIssue.class),
                    Map.entry("Board", org.fuin.porelo.planner.app.answers.Board.class),
                    Map.entry("HeldSave", org.fuin.porelo.planner.app.answers.HeldSave.class),
                    Map.entry("HeldChange", org.fuin.porelo.planner.app.answers.HeldChange.class),
                    Map.entry("HeldTry", org.fuin.porelo.planner.app.answers.HeldTry.class),
                    Map.entry("HeldAnswer", org.fuin.porelo.planner.app.answers.HeldAnswer.class),
                    Map.entry("HeldCommand", org.fuin.porelo.planner.app.answers.HeldCommand.class),
                    Map.entry(
                            "UnlistedIssue",
                            org.fuin.porelo.planner.app.answers.UnlistedIssue.class),
                    Map.entry("RuleBreach", org.fuin.porelo.planner.app.answers.RuleBreach.class),
                    Map.entry("Outline", org.fuin.porelo.planner.app.answers.Outline.class),
                    Map.entry(
                            "OutlineStory", org.fuin.porelo.planner.app.answers.OutlineStory.class),
                    Map.entry(
                            "OutlineIssue", org.fuin.porelo.planner.app.answers.OutlineIssue.class),
                    Map.entry("IssueParts", org.fuin.porelo.planner.app.answers.IssueParts.class),
                    Map.entry("TextParts", org.fuin.porelo.planner.app.answers.TextParts.class),
                    Map.entry("Breaches", org.fuin.porelo.planner.app.answers.Breaches.class),
                    Map.entry("Breach", org.fuin.porelo.planner.app.answers.Breach.class),
                    Map.entry(
                            "SearchAnswer", org.fuin.porelo.planner.app.answers.SearchAnswer.class),
                    Map.entry("SearchMatch", org.fuin.porelo.planner.app.answers.SearchMatch.class),
                    Map.entry("BatchSaved", org.fuin.porelo.planner.app.answers.BatchSaved.class),
                    Map.entry("ItemResult", org.fuin.porelo.planner.app.answers.ItemResult.class),
                    Map.entry("Problem", org.fuin.porelo.planner.app.answers.Problem.class),
                    Map.entry("ChangedItem", org.fuin.porelo.planner.app.answers.ChangedItem.class),
                    Map.entry("Refreshed", org.fuin.porelo.planner.app.answers.Refreshed.class),
                    Map.entry("Moved", org.fuin.porelo.planner.app.answers.Moved.class),
                    Map.entry("StoppedRun", org.fuin.porelo.planner.app.answers.StoppedRun.class),
                    Map.entry("StoppedTask", org.fuin.porelo.planner.app.answers.StoppedTask.class),
                    Map.entry("MovePreview", org.fuin.porelo.planner.app.answers.MovePreview.class),
                    Map.entry("Target", org.fuin.porelo.planner.app.answers.Target.class),
                    Map.entry("MovedIssue", org.fuin.porelo.planner.app.answers.MovedIssue.class),
                    Map.entry("MovedStory", org.fuin.porelo.planner.app.answers.MovedStory.class),
                    Map.entry("MoveWarning", org.fuin.porelo.planner.app.answers.MoveWarning.class),
                    Map.entry("IssueAdded", org.fuin.porelo.planner.app.answers.IssueAdded.class),
                    Map.entry("StoryAdded", org.fuin.porelo.planner.app.answers.StoryAdded.class),
                    Map.entry("EditSaved", org.fuin.porelo.planner.app.answers.EditSaved.class),
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
                    Map.entry("TaskRole", org.fuin.porelo.planner.app.answers.TaskRole.class),
                    Map.entry("PlanOffer", org.fuin.porelo.planner.app.answers.PlanOffer.class),
                    Map.entry(
                            "PlanAssignment",
                            org.fuin.porelo.planner.app.answers.PlanAssignment.class),
                    Map.entry(
                            "IssueReadingRounds",
                            org.fuin.porelo.planner.app.answers.IssueReadingRounds.class),
                    Map.entry(
                            "PlanningStartTask",
                            org.fuin.porelo.planner.app.answers.PlanningStartTask.class),
                    Map.entry("GivenFile", org.fuin.porelo.planner.app.answers.GivenFile.class),
                    Map.entry("TrackedWork", org.fuin.porelo.planner.app.answers.TrackedWork.class),
                    Map.entry("RunPlace", org.fuin.porelo.planner.app.answers.RunPlace.class),
                    Map.entry("GatePush", org.fuin.porelo.planner.app.answers.GatePush.class),
                    Map.entry("TaskEnded", org.fuin.porelo.planner.app.answers.TaskEnded.class),
                    Map.entry("Landing", org.fuin.porelo.planner.app.answers.Landing.class),
                    Map.entry("TaskRefusal", org.fuin.porelo.planner.app.answers.TaskRefusal.class),
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
                    Map.entry("Idea", NewIdeas.Idea.class),
                    Map.entry("IdeaStory", NewIdeas.Story.class),
                    Map.entry("WorkerRequest", WorkRequests.WorkerRequest.class),
                    Map.entry("PriorityRequest", WorkRequests.PriorityRequest.class),
                    Map.entry("StoryWorkProposalRequest", WorkRequests.Proposal.class),
                    Map.entry("StoryWorkRequest", WorkRequests.Request.class),
                    Map.entry("PassOver", WorkRun.PassOver.class),
                    Map.entry("StopRequest", WorkRequests.StopRequest.class),
                    Map.entry("TaskEventRequest", WorkRequests.TaskEventRequest.class),
                    Map.entry("ReadingRequest", WorkRequests.ReadingRequest.class),
                    Map.entry(
                            "StageResultReply",
                            org.fuin.porelo.planner.app.answers.StageResultReply.class),
                    Map.entry("StageResultRequest", WorkRequests.StageResultRequest.class));

    /** The bodies' own records of schemas an answer carries too, each read by hand. */
    private static final Map<String, Class<?>> READ_BY_HAND =
            Map.ofEntries(
                    Map.entry("PlanRepositoryWork", WorkRequests.Repository.class),
                    Map.entry("PlanWorker", WorkRequests.Main.class),
                    Map.entry("PlanOffer", WorkRequests.Offer.class),
                    Map.entry("PlanAssignment", WorkRequests.Assignment.class),
                    Map.entry("IssueReadingRounds", WorkRequests.IssueLimit.class),
                    Map.entry("TaskEnded", WorkRequests.TaskEnded.class),
                    Map.entry("TaskRefusal", WorkRequests.TaskRefusal.class),
                    Map.entry("Landing", NextStep.Landing.class),
                    Map.entry("Level", LogLevelController.Chosen.class),
                    Map.entry("StoryRequest", EditRequests.StoryRequest.class),
                    Map.entry("StoryFromIssuesRequest", EditRequests.StoryFromIssuesRequest.class),
                    Map.entry("StoryIssuesRequest", EditRequests.StoryIssuesRequest.class),
                    Map.entry("BlockedByRequest", EditRequests.BlockedByRequest.class),
                    Map.entry("StateMoves", EditRequests.StateMovesRequest.class),
                    Map.entry("StateMove", EditRequests.StateMoveRequest.class),
                    Map.entry("CommentRequest", EditRequests.CommentRequest.class),
                    Map.entry("ListIssueRequest", EditRequests.ListRequest.class));

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
                final DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
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
                if (delete != null) {
                    served.put(
                            "DELETE " + path(delete.value(), delete.path()),
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
                        "POST /projects/{project}/stories/{story}/work",
                        "POST /projects/{project}/stories",
                        "GET /projects/{project}/issues",
                        "PUT /projects/{project}/issues/{issue}/fields",
                        "PUT /projects/{project}/stories/{story}/issues",
                        "PUT /projects/{project}/issues/{issue}/blockedBy",
                        "PUT /projects/{project}/issues/{issue}/text",
                        "PUT /projects/{project}/stories/{story}/text",
                        "POST /projects/{project}/issues/{issue}/comments",
                        "POST /projects/{project}/items",
                        "POST /projects/{project}/states")
                .isEqualTo(served);
    }

    @Test
    void aStartOrderNamesNoMemberForAPartThePlannerNeverHandsOnAndTakesNoOther() {
        // Harm: a member the window would hand Start unread, such as credentials, a clearance or
        // an upstream, or a role word Start no longer takes, reaches Porelo with the order.
        final JsonNode order = description.at("/components/schemas/PlanningStartTask");
        final Set<String> members = new TreeSet<>();
        order.path("properties").fieldNames().forEachRemaining(members::add);
        assertThat(members)
                .as("the start order's members")
                .isNotEmpty()
                .doesNotContain("credentials", "clearance", "upstream", "runRole", "reads");
        assertThat(order.path("additionalProperties").isBoolean())
                .as("the start order says whether it takes other members")
                .isTrue();
        assertThat(order.path("additionalProperties").asBoolean())
                .as("the start order takes no other member")
                .isFalse();
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
                if (!leftOut
                        && !AnswerRecordsTest.SENT_THOUGH_OPTIONAL.contains(
                                entry.getKey() + "." + AnswerRecordsTest.wireName(component))) {
                    always.add(AnswerRecordsTest.wireName(component));
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
    void theDescriptionAndTheDocumentNameTheBodyBoundTheFilterKeeps() throws Exception {
        // Harm: the window or a reader told of a bound the planner no longer keeps.
        final List<String> bounds =
                List.of(
                        "larger than " + AccessFilter.MOST_TEXT_BODY + " bytes",
                        AccessFilter.MOST_LARGE_BODY + " bytes for",
                        AccessFilter.MOST_RESULT + " bytes for");
        assertThat(description.at("/info/description").asText().replaceAll("\\s+", " "))
                .as("the description's own words")
                .contains(bounds);
        assertThat(
                        description
                                .at("/components/responses/Unauthorized/description")
                                .asText()
                                .replaceAll("\\s+", " "))
                .as("the refused call's answer")
                .contains(bounds);
        assertThat(
                        java.nio.file.Files.readString(java.nio.file.Path.of("../../doc/index.md"))
                                .replaceAll("\\s+", " "))
                .as("the planner's document")
                .contains(bounds);
    }

    @Test
    void theTextEditsTakeExactlyTheMembersTheirSchemasName() {
        // A member the description names but the planner refuses, or one it takes unnamed, makes
        // the window's edit fail or write a part nobody described.
        final JsonNode schemas = description.get("components").get("schemas");
        final Map<String, Set<String>> taken =
                Map.of(
                        "IssueTextRequest", EditRequests.ISSUE_PARTS.keySet(),
                        "StoryTextRequest", EditRequests.STORY_PARTS.keySet());
        taken.forEach(
                (schema, parts) -> {
                    final Set<String> members = new TreeSet<>(parts);
                    members.add("title");
                    members.add("seen");
                    members.add("labels");
                    final Set<String> described = new TreeSet<>();
                    schemas.get(schema)
                            .get("properties")
                            .properties()
                            .forEach(member -> described.add(member.getKey()));
                    assertThat(described)
                            .as("the members of %s against those the planner reads", schema)
                            .contains("title", "seen", "labels")
                            .isEqualTo(members);
                });
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
    void theLabelNamePatternRefusesEveryCharacterTheStoresLabelRuleRefuses() {
        // Harm: the window takes a label name that hides or turns text, which the planner refuses
        // or a person reads as another label.
        final String ecma = description.at("/components/schemas/LabelName/pattern").asText();
        // Read without the u flag, a tag character stands as its surrogate pair; Java matches by
        // code points, so each pair is read as the range of code points it encodes.
        final java.util.regex.Matcher pairs =
                java.util.regex.Pattern.compile(
                                "\\\\u(D[89AB][0-9A-F]{2})\\[\\\\u(D[C-F][0-9A-F]{2})-\\\\u(D[C-F][0-9A-F]{2})\\]",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(ecma);
        final StringBuilder read = new StringBuilder();
        while (pairs.find()) {
            final char high = (char) Integer.parseInt(pairs.group(1), 16);
            pairs.appendReplacement(
                    read,
                    java.util.regex.Matcher.quoteReplacement(
                            String.format(
                                    "[\\x{%X}-\\x{%X}]",
                                    Character.toCodePoint(
                                            high, (char) Integer.parseInt(pairs.group(2), 16)),
                                    Character.toCodePoint(
                                            high, (char) Integer.parseInt(pairs.group(3), 16)))));
        }
        pairs.appendTail(read);
        final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(read.toString());
        for (final int hidden :
                List.of(
                        0x0000, 0x001F, 0x007F, 0x0085, 0x009F, 0x00AD, 0x034F, 0x061C, 0x180E,
                        0x200B, 0x200F, 0x202A, 0x202E, 0x2060, 0x2064, 0x2066, 0x206F, 0xFEFF,
                        0x2028, 0x2029, 0xE0000, 0xE0001, 0xE0041, 0xE007F)) {
            final String name = "a" + Character.toString(hidden) + "b";
            assertThat(pattern.matcher(name).matches()).as("U+%04X", hidden).isFalse();
            assertThat(org.fuin.porelo.planner.store.TopicLabels.wellFormed(name))
                    .as("U+%04X by the store's rule", hidden)
                    .isFalse();
        }
        for (final String name :
                List.of("needs-design", "Größe 🙂", "a" + Character.toString(0xE0080))) {
            assertThat(pattern.matcher(name).matches()).as(name).isTrue();
            assertThat(org.fuin.porelo.planner.store.TopicLabels.wellFormed(name))
                    .as("%s by the store's rule", name)
                    .isTrue();
        }
    }

    @Test
    void theIdPatternsTakeTheIdsThePlannerTakes() {
        // Harm: the window refusing an item the planner serves, or sending one the planner refuses.
        final java.util.regex.Pattern story =
                java.util.regex.Pattern.compile(
                        description.at("/components/schemas/StoryId/pattern").asText());
        final java.util.regex.Pattern issue =
                java.util.regex.Pattern.compile(
                        description.at("/components/schemas/IssueId/pattern").asText());
        for (final String id :
                List.of(
                        "ST1",
                        "ST12345",
                        "XY03", // not-a-citation: an id's shape
                        "XY03.1", // not-a-citation: an id's shape
                        "porelo-planner#1",
                        "porelo-ai.github.io#123456789",
                        "repo#0",
                        "repo#01",
                        "repo#1234567890",
                        "Repo#1",
                        "a/b#1",
                        "a..b#1",
                        "repo#1#2",
                        "repo #1",
                        "#1",
                        "a".repeat(100) + "#1",
                        "a".repeat(101) + "#1")) {
            assertThat(story.matcher(id).matches())
                    .as("the description's StoryId against Ids.story for '%s'", id)
                    .isEqualTo(takes(() -> org.fuin.porelo.planner.store.Ids.story(id)));
            assertThat(issue.matcher(id).matches())
                    .as("the description's IssueId against Ids.issue for '%s'", id)
                    .isEqualTo(takes(() -> org.fuin.porelo.planner.store.Ids.issue(id)));
        }
    }

    @Test
    void noStoryOrIssueIsDescribedWithAnOldIdBesideItsReference() {
        // Harm: the window shows or sends an old id that names no item, since old ids are matched
        // to nothing.
        final List<String> named = new ArrayList<>();
        description
                .at("/components/schemas")
                .properties()
                .forEach(
                        schema -> {
                            if (schema.getValue().at("/properties").has("alias")) {
                                named.add(schema.getKey());
                            }
                        });
        assertThat(named).as("the schemas describing an alias").isEmpty();
        assertThat(description.at("/components/schemas").has("StoryAlias"))
                .as("a story alias described")
                .isFalse();
    }

    @Test
    void aGivenFilesNameIsTheNameThePlannerGivesAnIssuesText() {
        // Harm: the window refuses a stage's start over a tracker's issue, or takes a name with a
        // '#' or a path for a file Porelo mounts.
        final java.util.regex.Pattern name =
                java.util.regex.Pattern.compile(
                        description
                                .at("/components/schemas/GivenFile/properties/name/pattern")
                                .asText());
        for (final String issue :
                List.of(
                        "XY12", // not-a-citation: an id's shape
                        "XY12.1", // not-a-citation: an id's shape
                        "porelo-planner#12",
                        "a.b_c-d#1")) {
            assertThat(name.matcher(org.fuin.porelo.planner.core.StageOrder.given(issue)).matches())
                    .as("the given file of %s", issue)
                    .isTrue();
        }
        for (final String refused :
                List.of(
                        "porelo-planner#12.md",
                        "porelo-planner-012.md",
                        "a..b-1.md",
                        "../x-1.md",
                        "x/y-1.md",
                        "porelo-planner.md")) {
            assertThat(name.matcher(refused).matches()).as("the name '%s'", refused).isFalse();
        }
    }

    @Test
    void theTrackedWorkTakesTheShapeTheWindowTakes() {
        // Harm: the description lets through a work the window refuses, so a Start is refused
        // where the contract promised it.
        final JsonNode work = description.at("/components/schemas/TrackedWork");
        final java.util.regex.Pattern issue =
                java.util.regex.Pattern.compile(work.at("/properties/issue/pattern").asText());
        final java.util.regex.Pattern phase =
                java.util.regex.Pattern.compile(work.at("/properties/phase/pattern").asText());
        assertThat(work.at("/additionalProperties").asBoolean(true))
                .as("a work with another member is refused")
                .isFalse();
        assertThat(issue.matcher("porelo-ai/porelo-x#3").matches())
                .as("an owner's reference is taken")
                .isTrue();
        for (final String refused :
                List.of(
                        "a..b/porelo-x#3",
                        "a../porelo-x#3",
                        "porelo-ai/a..b#3",
                        "porelo-x#3",
                        "porelo-ai/porelo-x #3",
                        "porelo-ai/porelo-x#3#4")) {
            assertThat(issue.matcher(refused).matches())
                    .as("the work's issue '%s' is refused", refused)
                    .isFalse();
        }
        assertThat(phase.matcher("Analysis").matches()).as("a phase's name is taken").isTrue();
        for (final String refused : List.of(" ", "   ", "Analysis\nx")) {
            assertThat(phase.matcher(refused).matches())
                    .as("the work's phase '%s' is refused", refused)
                    .isFalse();
        }
    }

    private static boolean takes(final Runnable check) {
        try {
            check.run();
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        }
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
                        WorkRequests.Offer.class,
                        WorkRequests.Assignment.class,
                        WorkRequests.IssueLimit.class,
                        WorkRequests.TaskEventRequest.class,
                        WorkRequests.TaskEnded.class,
                        WorkRequests.TaskRefusal.class,
                        NextStep.Landing.class,
                        WorkRequests.ReadingRequest.class);
        final Set<Class<?>> described = new HashSet<>(SCHEMAS.values());
        described.addAll(READ_BY_HAND.values());
        assertThat(workTypes)
                .as("every work body and nested record read by hand participates")
                .hasSize(14)
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
    void theHandInOfAStageResultDescribesItsBodyItsAnswerAndEveryRefusal() {
        // A refusal or a member missing from the contract lets the window take a refused result
        // as saved, or hand in a result the planner reads otherwise.
        final JsonNode operation =
                description
                        .path("paths")
                        .path("/projects/{project}/tasks/{task}/result")
                        .path("post");
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText())
                .as("the hand-in's body")
                .isEqualTo("#/components/schemas/StageResultRequest");
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                .as("the hand-in's answer")
                .isEqualTo("#/components/schemas/StageResultReply");
        final Set<String> statuses = new TreeSet<>();
        operation.path("responses").fieldNames().forEachRemaining(statuses::add);
        assertThat(statuses)
                .as("every answer of the hand-in")
                .isEqualTo(Set.of("200", "400", "401", "404", "409", "422", "501", "502", "503"));
        assertThat(operation.at("/responses/422/$ref").asText())
                .as("a refused result's answer")
                .isEqualTo("#/components/responses/ResultRefused");
        assertThat(operation.path("description").asText())
                .as("the body's bound the planner reads")
                .contains("at most " + AccessFilter.MOST_RESULT + " bytes");
        final JsonNode schemas = description.path("components").path("schemas");
        for (final Map.Entry<String, Class<?>> format :
                Map.<String, Class<?>>of(
                                "StageResultFile",
                                org.fuin.porelo.planner.core.HandedResults.File.class,
                                "StageResultIssue",
                                org.fuin.porelo.planner.core.HandedResults.Entry.class)
                        .entrySet()) {
            final Set<String> properties = new TreeSet<>();
            schemas.path(format.getKey())
                    .path("properties")
                    .fieldNames()
                    .forEachRemaining(properties::add);
            assertThat(properties)
                    .as("%s has exactly the members the planner reads", format.getKey())
                    .isEqualTo(names(format.getValue().getRecordComponents()));
        }
        final Set<String> required = new TreeSet<>();
        schemas.at("/StageResultIssue/required").forEach(field -> required.add(field.asText()));
        assertThat(required).as("the members every kind of result needs").isEqualTo(Set.of("id"));
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
        if (type instanceof ParameterizedType parameterized
                && parameterized.getRawType() == Map.class) {
            assertThat(field.path("type").asText()).as("%s is a map", name).isEqualTo("object");
            assertThat(field.at("/additionalProperties/type").asText())
                    .as("%s maps to strings", name)
                    .isEqualTo("string");
            return;
        }
        final Class<?> raw = (Class<?>) type;
        if (raw == org.fuin.porelo.planner.store.Basis.class) {
            // The versions seen travel as a map by id, which no record's members describe.
            assertThat(field.path("$ref").asText())
                    .as("%s refers to the versions seen", name)
                    .isEqualTo("#/components/schemas/Seen");
            return;
        }
        if (raw == org.fuin.porelo.planner.store.Role.class) {
            // A role sent back is read by its name alone and taken from the work rules.
            assertThat(field.at("/allOf/0/$ref").asText())
                    .as("%s refers to the role's schema", name)
                    .isEqualTo("#/components/schemas/TaskRole");
            return;
        }
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
        Arrays.stream(components)
                .forEach(component -> names.add(AnswerRecordsTest.wireName(component)));
        return names;
    }
}
