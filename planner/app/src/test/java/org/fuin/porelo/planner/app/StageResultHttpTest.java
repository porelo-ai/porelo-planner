package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StageResultHttpTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String READINESS = "Preparing for Backlog";

    private MockMvc mvc;

    private ChangesRegistry changes;

    private Store store;

    @BeforeEach
    void serveARealRunOverAFakeStore() {
        store = new Store();
        final Projects projects = new Projects((p, r, b) -> store);
        projects.put("project", "https://example.org/store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems(projects))
                        .build();
    }

    @AfterEach
    void closeTheFollower() {
        changes.close();
    }

    @Test
    void aStageResultAtOneMiBIsSavedAndOneByteMoreIsRefused() throws Exception {
        // Harm: a task's stage result is refused although a forge accepts its size.
        final String offer = started();
        final String body = handIn(offer, "M");
        // A result file of 1 MiB with its envelope.
        final String bounded = body + " ".repeat(1048576 + 4096 - body.length());
        final JsonNode refused = call("/tasks/analyze/result", bounded + " ", 422);
        assertThat(refused.path("type").asString())
                .as("a size refusal")
                .isEqualTo("urn:porelo-planner:ResultRefused");
        assertThat(store.results).as("the oversized request saves nothing").isEmpty();
        assertThat(call("/tasks/analyze/result", bounded, 200).path("outcome").asString())
                .as("a result at the bound is saved")
                .isEqualTo("SAVED");
        assertThat(store.results).as("one saved result").hasSize(1);
        assertThat(store.results.getFirst().issues().getFirst().plan())
                .as("the result reaches the store unchanged")
                .isEqualTo("Read it.\nChange it.");
    }

    @Test
    void aHandedInResultIsSavedOnceAndAnsweredWithTheIssueInItsDoneState() throws Exception {
        // Prevents an issue counted analyzed whose store holds no plan, and a result saved twice
        // when the window hands it in again.
        final String offer = started();

        final JsonNode saved = call("/tasks/analyze/result", handIn(offer, "M"), 200);
        final JsonNode again = call("/tasks/analyze/result", handIn(offer, "XL"), 200);

        assertThat(saved.path("outcome").asString()).as("the outcome").isEqualTo("SAVED");
        assertThat(saved.at("/issues/0/state").asString())
                .as("the issue in the rule's done state")
                .isEqualTo("Analyzed");
        assertThat(saved.path("revision").asString())
                .as("the board's revision after the save")
                .isEqualTo(store.board().revision().value());
        assertThat(again).as("the replay answers the same").isEqualTo(saved);
        assertThat(store.results).as("one save").hasSize(1);
        assertThat(store.results.getFirst().issues().getFirst().size())
                .as("the first hand-in's size")
                .isEqualTo("M");
    }

    @Test
    void aMalformedResultIsRefusedNamingItsFieldsAndSavesNothing() throws Exception {
        // Prevents a result the store cannot hold being saved, or a refusal the window cannot read.
        final String offer = started();
        final ObjectNode body = (ObjectNode) JSON.readTree(handIn(offer, "XXL"));
        ((ObjectNode) body.at("/result/issues/0")).put("extra", "x");

        final JsonNode refused = call("/tasks/analyze/result", body.toString(), 422);

        assertThat(refused.path("type").asString())
                .as("the refusal")
                .isEqualTo("urn:porelo-planner:ResultRefused");
        final List<String> faults = new ArrayList<>();
        refused.at("/parameters/faults").forEach(f -> faults.add(f.asString()));
        assertThat(faults)
                .as("each fault names its field")
                .anySatisfy(
                        f -> assertThat(f).as("the size's fault").startsWith("issues[0].size: "))
                .anySatisfy(f -> assertThat(f).as("the member's fault").startsWith("issues[0]: "));
        assertThat(store.results).as("nothing is saved").isEmpty();
        assertThat(call("/tasks/analyze/result", handIn(offer, "M"), 422).at("/parameters/faults"))
                .as("the replay answers the same refusal")
                .isEqualTo(refused.at("/parameters/faults"));
    }

    // Harm: a ready that is no boolean read as an answer, or refused with its fault said twice.
    @Test
    void aReadyThatIsNoBooleanIsRefusedOnceAndAReadinessMemberOutsideACheckIsNamed()
            throws Exception {
        final String offer = started();
        final ObjectNode body = (ObjectNode) JSON.readTree(handIn(offer, "M"));
        ((ObjectNode) body.at("/result/issues/0")).put("ready", "yes");

        final JsonNode refused = call("/tasks/analyze/result", body.toString(), 422);

        final List<String> faults = new ArrayList<>();
        refused.at("/parameters/faults").forEach(f -> faults.add(f.asString()));
        assertThat(faults)
                .as("the ready fault once, and the member named outside a readiness check")
                .contains(
                        "issues[0].ready: It must be true or false.",
                        "issues[0]: Only a readiness check names ready, lacks and why.")
                .filteredOn(f -> f.startsWith("issues[0].ready"))
                .hasSize(1);
        assertThat(store.results).as("nothing is saved").isEmpty();
    }

    // Harm: a readiness check's result over HTTP moving every issue to the backlog, or losing why
    // the one not ready went back.
    @Test
    void aReadinessResultMovesTheReadyIssueAndReturnsTheOtherWithItsReason() throws Exception {
        readinessStore();
        final String offer = started(READINESS, "check");

        final JsonNode saved = call("/tasks/check/result", readinessHandIn(offer, "false"), 200);

        assertThat(saved.path("outcome").asString()).as("the outcome").isEqualTo("SAVED");
        assertThat(store.results)
                .as("one save of both verdicts")
                .singleElement()
                .satisfies(
                        result ->
                                assertThat(result.verdicts())
                                        .containsExactly(
                                                new StageResult.Verdict(
                                                        "AA1", READINESS, "Backlog", null),
                                                new StageResult.Verdict(
                                                        "AA2",
                                                        READINESS,
                                                        "Estimated",
                                                        "Not ready: it lacks a plan. No plan"
                                                                + " section.")));
        assertThat(List.of(store.state("AA1"), store.state("AA2")))
                .as("the ready one in the backlog, the other back")
                .containsExactly("Backlog", "Estimated");
    }

    // Harm: a ready that is no boolean on a readiness check read as an answer, or its fault said
    // twice, once by the reader and once by the check.
    @Test
    void aReadyThatIsNoBooleanOnAReadinessCheckIsRefusedWithOneFault() throws Exception {
        readinessStore();
        final String offer = started(READINESS, "check");

        final JsonNode refused = call("/tasks/check/result", readinessHandIn(offer, "\"no\""), 422);

        final List<String> faults = new ArrayList<>();
        refused.at("/parameters/faults").forEach(f -> faults.add(f.asString()));
        assertThat(faults)
                .as("the ready fault, once")
                .contains("issues[1].ready: It must be true or false.")
                .filteredOn(f -> f.startsWith("issues[1].ready"))
                .hasSize(1);
        assertThat(store.results).as("nothing is saved").isEmpty();
        assertThat(List.of(store.state("AA1"), store.state("AA2")))
                .as("both stay at the check")
                .containsExactly(READINESS, READINESS);
    }

    private void readinessStore() {
        changes.close();
        store = Store.readiness();
        final Projects projects = new Projects((p, r, b) -> store);
        projects.put("project", "https://example.org/store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems(projects))
                        .build();
    }

    // Prevents a malformed envelope from being recorded as the offer's outcome, so the window's
    // proper hand-in afterwards would be refused for good.
    @Test
    void anEnvelopeWithNeitherBothOrAWrongResultIsRefusedAloneAndRecordsNothing() throws Exception {
        final String offer = started();
        final ObjectNode both = (ObjectNode) JSON.readTree(handIn(offer, "M"));
        both.put("missing", "no file");
        for (final String body :
                List.of(
                        "{\"offer\":\"" + offer + "\"}",
                        both.toString(),
                        "{\"offer\":\"" + offer + "\",\"result\":1}")) {
            final JsonNode refused = call("/tasks/analyze/result", body, 422);
            assertThat(refused.at("/parameters/faults"))
                    .as("one fault for the envelope %s", body)
                    .hasSize(1);
        }
        assertThat(
                        call("/tasks/analyze/result", handIn(offer, "M"), 200)
                                .path("outcome")
                                .asString())
                .as("the proper hand-in afterwards is saved")
                .isEqualTo("SAVED");
    }

    @Test
    void aBodyThatIsNoHandInAndAnUnknownOfferAreRefused() throws Exception {
        // Prevents a hand-in for work this task was never given moving an issue.
        started();

        assertThat(call("/tasks/analyze/result", "[1]", 422).path("type").asString())
                .as("a body that is no object")
                .isEqualTo("urn:porelo-planner:ResultRefused");
        assertThat(
                        call(
                                        "/tasks/analyze/result",
                                        handIn("0b1d6f0e-6c43-4e8e-9a51-6f5cbb5b3a11", "M"),
                                        404)
                                .path("type")
                                .asString())
                .as("an unknown offer")
                .isEqualTo("urn:porelo-planner:TaskNotAnswered");
        assertThat(store.results).as("nothing is saved").isEmpty();
    }

    private String started() throws Exception {
        return started("In analysis", "analyze");
    }

    private String started(final String to, final String task) throws Exception {
        final JsonNode order =
                call(
                        "/stories/ST1/work",
                        "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\""
                                + to
                                + "\","
                                + "\"mode\":\"AGENT\",\"seen\":"
                                + Seens.of(store.board())
                                + ",\"readingRounds\":1}",
                        200);
        final String offer = order.at("/tasks/0/offer").asString();
        call(
                "/tasks/" + task + "/events",
                "{\"offer\":\""
                        + offer
                        + "\",\"event\":\"STARTED\",\"container\":\"box\",\"machine\":\"workstation\"}",
                200);
        return offer;
    }

    private static String readinessHandIn(final String offer, final String secondReady) {
        return "{\"offer\":\""
                + offer
                + "\",\"result\":{\"format\":\"porelo-stage-result-1\",\"kind\":\"READINESS\","
                + "\"issues\":[{\"id\":\"AA1\",\"ready\":true},{\"id\":\"AA2\",\"ready\":"
                + secondReady
                + ",\"lacks\":[\"PLAN\"],\"why\":\"No plan section.\"}]}}";
    }

    private static String handIn(final String offer, final String size) {
        return "{\"offer\":\""
                + offer
                + "\",\"result\":{\"format\":\"porelo-stage-result-1\",\"kind\":\"ANALYSIS\","
                + "\"issues\":[{\"id\":\"AA1\",\"plan\":\"Read it.\\nChange it.\",\"size\":\""
                + size
                + "\",\"sizeWhy\":\"One module.\",\"toBeChecked\":[\"The bound.\"]}]}}";
    }

    private JsonNode call(final String path, final String body, final int status) throws Exception {
        final var response =
                mvc.perform(
                                post("/projects/project" + path)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus())
                .as("the response to %s: %s", path, response.getContentAsString())
                .isEqualTo(status);
        return JSON.readTree(response.getContentAsString());
    }

    /**
     * A store with one stage, an analysis or a readiness check, whose saved results can be seen.
     */
    private static final class Store implements PlanningStore {
        private Board current;
        private final Map<String, String> states = new java.util.LinkedHashMap<>();
        private final List<StageResult> results = new ArrayList<>();
        private int saves;

        /** One issue AA1 in Backlog and an analysis from it. */
        Store() {
            this(
                    "In analysis",
                    new WorkRule(
                            List.of("Backlog"),
                            "Analyzed",
                            null,
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Analyze {story}: {issues}; {tasks}",
                            Map.of("codex", "model"),
                            Map.of(),
                            WorkRule.Result.ANALYSIS,
                            Roles.forRule(null, WorkRule.Result.ANALYSIS)),
                    List.of("Backlog", "In analysis", "Analyzed", "Finished"),
                    "Backlog",
                    List.of("AA1"));
        }

        Store(
                final String working,
                final WorkRule rule,
                final List<String> workflow,
                final String from,
                final List<String> ids) {
            ids.forEach(id -> states.put(id, from));
            current =
                    new Board(
                            new Revision("r1"),
                            new Workflow(workflow, List.of(), List.of("Finished")),
                            List.of(new Story("ST1", "Story", from, "High", null, ids, "", null)),
                            issues(),
                            new Work(Map.of(working, rule), List.of()),
                            org.fuin.porelo.planner.store.ProjectRepository.named(
                                    List.of("alpha")));
        }

        /** Two issues AA1 and AA2 in Estimated and a readiness check to Backlog. */
        static Store readiness() {
            return new Store(
                    READINESS,
                    new WorkRule(
                            List.of("Estimated"),
                            "Backlog",
                            null,
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Check {story}: {issues}; {tasks}",
                            Map.of("codex", "model"),
                            Map.of(),
                            WorkRule.Result.READINESS,
                            Roles.forRule(null, WorkRule.Result.READINESS)),
                    List.of("Estimated", READINESS, "Backlog", "Finished"),
                    "Estimated",
                    List.of("AA1", "AA2"));
        }

        private List<Issue> issues() {
            return states.entrySet().stream()
                    .map(
                            entry ->
                                    new Issue(
                                            entry.getKey(),
                                            "alpha",
                                            "Issue",
                                            entry.getValue(),
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            "**What must be true.** It works.",
                                            Worker.AGENT,
                                            Risk.ORDINARY))
                    .toList();
        }

        private void moved(final Map<String, String> to) {
            saves++;
            states.putAll(to);
            current =
                    new Board(
                            new Revision("r" + (saves + 1)),
                            current.workflow(),
                            current.stories(),
                            issues(),
                            current.work(),
                            current.repositories());
        }

        String state(final String id) {
            return states.get(id);
        }

        @Override
        public Board board() {
            return current;
        }

        @Override
        public Board refresh() {
            return current;
        }

        @Override
        public Board save(final Revision revision, final Writes writes) {
            final Map<String, String> to = new java.util.LinkedHashMap<>();
            writes.issues().forEach((id, state) -> to.put(id, state));
            moved(to);
            return current;
        }

        @Override
        public Board saveResult(final StageResult result) {
            results.add(result);
            final Map<String, String> to = new java.util.LinkedHashMap<>();
            result.issues().forEach(issue -> to.put(issue.id(), issue.to()));
            result.verdicts().forEach(verdict -> to.put(verdict.id(), verdict.to()));
            moved(to);
            return current;
        }

        @Override
        public String storyState(final List<String> states) {
            return states.getFirst();
        }
    }
}
