package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WorkRunHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private MockMvc mvc;
    private ChangesRegistry changes;
    private Projects projects;
    private Store store;

    @BeforeEach
    void serveARealRunOverAFakeStore() {
        store = new Store();
        projects = new Projects((p, r, b) -> store, 8);
        projects.put("project", "https://example.org/store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems())
                        .build();
    }

    @AfterEach
    void closeTheFollower() {
        changes.close();
    }

    @Test
    void theWindowCanRunAStoryWithoutInventingActivityAndRepeatedEndsDoNotStartTwice()
            throws Exception {
        // Prevents wrong starts and hidden reading results caused by incompatible HTTP envelopes.
        assertThat(
                        JSON.readTree(
                                        mvc.perform(get("/projects/project/stories/ST1/work"))
                                                .andReturn()
                                                .getResponse()
                                                .getContentAsString())
                                .path("work")
                                .isNull())
                .as("no run is represented explicitly")
                .isTrue();
        final JsonNode order =
                call(
                        "/stories/ST1/work",
                        "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                        200);
        final String offer = order.at("/tasks/0/offer").asString();
        assertThat(projects.work("project").active("ST1"))
                .as("preparing an offer starts nothing")
                .isEmpty();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final JsonNode refusedMove =
                call(
                        "/moves",
                        "{\"revision\":\"r2\",\"moves\":[{\"issue\":\"AA1\",\"to\":\"Review\"}]}",
                        409);
        assertThat(refusedMove.path("type").asString())
                .as("active ownership protects window moves")
                .endsWith(":WorkRunning");
        final JsonNode ended = call("/tasks/build/events", event(offer, "ENDED", "Built"), 200);
        assertThat(ended.propertyNames())
                .as("every event carries work, revision and next tasks")
                .containsExactlyInAnyOrder("revision", "work", "tasks");
        assertThat(call("/tasks/build/events", event(offer, "ENDED", "Built"), 200).path("tasks"))
                .as("repeated reports keep opaque identities")
                .isEqualTo(ended.path("tasks"));
        final String reader = ended.at("/tasks/0/offer").asString();
        call("/tasks/read/events", event(reader, "STARTED", ""), 200);
        final JsonNode read =
                call(
                        "/tasks/read/events",
                        event(reader, "ENDED", "Findings:\n1. blocking | - | - | Preserve data"),
                        200);
        assertThat(read.at("/work/issues/0/readingStopped").asBoolean())
                .as("the limit is visible")
                .isTrue();
        final JsonNode choice =
                call(
                        "/issues/AA1/reading",
                        "{\"story\":\"ST1\",\"round\":1,\"choice\":\"REVIEW_ANYWAY\",\"comment\":\"\",\"revision\":\""
                                + store.board().revision().value()
                                + "\"}",
                        200);
        assertThat(choice.propertyNames())
                .as("choices keep the issue envelope and next tasks")
                .containsExactlyInAnyOrder("issue", "work", "revision", "tasks");
        assertThat(choice.at("/issue/state").asString())
                .as("the operator can accept the findings")
                .isEqualTo("Review");
        assertThat(choice.at("/issue/findings").size())
                .as("review retains the reading")
                .isEqualTo(1);
    }

    @Test
    void sharedIssueProgressPrefersActiveWorkThenCountedReadingsThenTheSmallerStory()
            throws Exception {
        // Prevents shared issues from displaying stale counts or findings from an arbitrary run.
        final var order =
                call(
                        "/stories/ST1/work",
                        "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\"}",
                        200);
        call(
                "/tasks/build/events",
                event(order.at("/tasks/0/offer").asString(), "STARTED", ""),
                200);
        final WorkRun.Run template =
                java.util.Objects.requireNonNull(projects.work("project").work("ST1"));
        for (final List<WorkRun.Run> runs :
                List.of(
                        List.of(
                                progressRun(template, "ST1", 1, true),
                                progressRun(template, "ST2", 2, false)),
                        List.of(
                                progressRun(template, "ST2", 1, true),
                                progressRun(template, "ST1", 2, false)),
                        List.of(
                                progressRun(template, "ST1", 2, false),
                                progressRun(template, "ST2", 1, false)),
                        List.of(
                                progressRun(template, "ST2", 2, false),
                                progressRun(template, "ST1", 1, false)),
                        List.of(
                                progressRun(template, "ST1", 2, false),
                                progressRun(template, "ST2", 2, false)),
                        List.of(
                                progressRun(template, "ST2", 2, true),
                                progressRun(template, "ST1", 1, true)),
                        List.of(
                                progressRun(template, "ST1", 2, true),
                                progressRun(template, "ST2", 2, true)))) {
            final WorkRun.Run preferred = runs.getFirst();
            final WorkRun.Run other = runs.getLast();
            final var snapshot =
                    new WorkRun.Snapshot(
                            1, Map.of(preferred.story(), preferred, other.story(), other));
            assertThat(WorkAnswers.progress(snapshot, "AA1"))
                    .as("shared progress belongs to the preferred run %s", preferred.story())
                    .isSameAs(preferred.issues().get("AA1"));
            assertThat(WorkAnswers.progress(snapshot, "AA2"))
                    .as("an issue absent from every run has no recorded progress")
                    .isNull();
        }
    }

    @Test
    void invalidMachineNamesChangeNeitherTheBoardNorTheWorkRecord() throws Exception {
        // Prevents malformed machine names from starting work or consuming an offer.
        final String story = store.board().stories().getFirst().id();
        final JsonNode order = offerWork(story);
        final String offer = order.at("/tasks/0/offer").asString();
        final var work = projects.work("project");
        final var snapshot = work.snapshot();
        final Board board = store.board();
        final var valid =
                (tools.jackson.databind.node.ObjectNode) JSON.readTree(event(offer, "STARTED", ""));
        final var missing = valid.deepCopy();
        missing.remove("machine");
        final List<JsonNode> invalid = new java.util.ArrayList<>();
        invalid.add(missing);
        invalid.add(valid.deepCopy().putNull("machine"));
        invalid.add(valid.deepCopy().put("machine", 1));
        for (final String machine :
                List.of(
                        "",
                        "x".repeat(129),
                        "😀".repeat(129),
                        "line\nbreak",
                        "line\rbreak",
                        "tab\tname",
                        "nul\u0000name",
                        "delete\u007fname",
                        "control\u0085name",
                        "line\u2028break",
                        "paragraph\u2029break")) {
            invalid.add(valid.deepCopy().put("machine", machine));
        }
        for (final JsonNode body : invalid) {
            final JsonNode refused = call("/tasks/build/events", body.toString(), 400);
            assertThat(refused.path("type").asString())
                    .as("an invalid machine has the same refusal as other invalid report fields")
                    .endsWith(":InvalidSettings");
            assertThat(work.snapshot())
                    .as("a refused machine changes no generation, binding or offers")
                    .isEqualTo(snapshot);
            assertThat(store.board())
                    .as("a refused machine changes neither planning state nor revision")
                    .isEqualTo(board);
        }
    }

    @Test
    void theStoryAnswersTheReportedMachineAndCountsItsNameInCodePoints() throws Exception {
        // Prevents lost machine routing and rejection of valid supplementary Unicode names.
        final String story = store.board().stories().getFirst().id();
        final String offer = offerWork(story).at("/tasks/0/offer").asString();
        final String machine = "😀".repeat(128);
        final var body =
                (tools.jackson.databind.node.ObjectNode) JSON.readTree(event(offer, "STARTED", ""));
        body.put("machine", machine);
        final JsonNode started = call("/tasks/build/events", body.toString(), 200);
        assertThat(started.at("/work/tasks/0/machine").asString())
                .as("the report answer preserves the full name at the code point limit")
                .isEqualTo(machine);
        final var response =
                mvc.perform(get("/projects/project/stories/" + story + "/work"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).as("the story's work can be read").isEqualTo(200);
        assertThat(
                        JSON.readTree(response.getContentAsString())
                                .at("/work/tasks/0/machine")
                                .asString())
                .as("the story's work answers the machine verbatim")
                .isEqualTo(machine);
    }

    @Test
    void invalidReportsAreBoundedAndNeverEchoUncheckedSubjects() throws Exception {
        // Prevents malformed reports or unchecked subjects from reaching tasks or refusals.
        for (final String body :
                List.of(
                        "{\"offer\":\"bad-secret\",\"event\":\"ENDED\",\"container\":\"box\",\"machine\":\"workstation\"}",
                        "{\"offer\":\"00000000-0000-0000-0000-000000000000\",\"event\":\"STARTED\",\"container\":\"box\",\"machine\":\"workstation\",\"event\":\"ENDED\"}",
                        "{\"unknown\":\"" + "x".repeat(131073) + "\"}",
                        event("00000000-0000-0000-0000-000000000000", "ENDED", "Built")
                                .replace("00:00:00Z", "00:00Z"),
                        event("00000000-0000-0000-0000-000000000000", "ENDED", "Built")
                                .replace("00:00:00Z", "00:00:00+01:00:30"))) {
            final JsonNode refused = call("/tasks/build/events", body, 400);
            assertThat(refused.toString())
                    .as("unchecked input is absent from the refusal")
                    .doesNotContain("bad-secret");
        }
        final JsonNode unknown =
                call(
                        "/tasks/build/events",
                        event("00000000-0000-0000-0000-000000000000", "STARTED", ""),
                        404);
        assertThat(unknown.path("type").asString())
                .as("an unknown checked offer has its named refusal")
                .endsWith(":TaskNotAnswered");
        call("/stories/ST1/work/stop", "{}", 400);
        call("/stories/ST1/work/stop", "", 200);
    }

    @Test
    void anOversizedReadingAnswerCannotSaveBeforeItCanBeReturned() throws Exception {
        // Prevents an oversized issue envelope from changing planning before it can be answered.
        final var order =
                call(
                        "/stories/ST1/work",
                        "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                        200);
        final String build = order.at("/tasks/0/offer").asString();
        call("/tasks/build/events", event(build, "STARTED", ""), 200);
        final String reader =
                call("/tasks/build/events", event(build, "ENDED", "Built"), 200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/read/events", event(reader, "STARTED", ""), 200);
        call(
                "/tasks/read/events",
                event(reader, "ENDED", "Findings:\n1. blocking | - | - | Preserve data"),
                200);
        final Board board = store.current;
        final Issue old = board.issues().getFirst();
        store.current =
                new Board(
                        board.revision(),
                        board.workflow(),
                        board.stories(),
                        List.of(
                                new Issue(
                                        old.id(),
                                        old.repository(),
                                        "x".repeat(WorkRecord.MOST_RECORD),
                                        old.state(),
                                        old.size(),
                                        old.blockedBy(),
                                        old.parent(),
                                        old.link(),
                                        old.text())),
                        board.work(),
                        board.repositories());
        final var refused =
                call(
                        "/issues/AA1/reading",
                        "{\"story\":\"ST1\",\"round\":1,\"choice\":\"REVIEW_ANYWAY\",\"comment\":\"\",\"revision\":\""
                                + board.revision().value()
                                + "\"}",
                        503);
        assertThat(refused.path("type").asString())
                .as("capacity has its named refusal")
                .endsWith(":WorkRecordUnavailable");
        assertThat(store.board().issue("AA1").orElseThrow().state())
                .as("the refused answer performs no state write")
                .isEqualTo("Reading");
    }

    @Test
    void workGenerationsReachFollowersAtAnUnchangedStoreRevision() throws Exception {
        // Prevents Needs you and cancellation from staying hidden when no planning state changes.
        try (var subscription = changes.subscribe("project")) {
            call(
                    "/stories/ST1/work",
                    "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\"}",
                    200);
            final var next = subscription.nextFrame(Duration.ZERO);
            assertThat(next).as("a record-only change is an event").isPresent();
            assertThat(next.orElseThrow().board().revision().value())
                    .as("the store revision is unchanged")
                    .isEqualTo("r1");
            assertThat(next.orElseThrow().work().runs())
                    .as("the event carries its exact work generation")
                    .containsKey("ST1");
        }
    }

    private static WorkRun.Run progressRun(
            final WorkRun.Run template,
            final String story,
            final int rounds,
            final boolean active) {
        final var progress =
                new NextStep.Progress(
                        new ReadingCycle.Rounds(rounds, 2, false), List.of(), false, "NONE", story);
        final var offers =
                active
                        ? template.offers().stream()
                                .map(
                                        offer ->
                                                new WorkRun.Offer(
                                                        offer.id(),
                                                        offer.arguments(),
                                                        new NextStep.Binding(
                                                                story,
                                                                offer.binding().repository(),
                                                                offer.binding().issues(),
                                                                offer.binding().role(),
                                                                offer.binding().state(),
                                                                offer.binding().mode(),
                                                                offer.binding().rule()),
                                                        offer.status(),
                                                        offer.task(),
                                                        offer.containerId(),
                                                        offer.machine(),
                                                        offer.since(),
                                                        offer.ended(),
                                                        offer.fingerprint(),
                                                        offer.following(),
                                                        offer.deferred(),
                                                        offer.refusal()))
                                .toList()
                        : List.<WorkRun.Offer>of();
        return new WorkRun.Run(
                template.id(),
                story,
                template.to(),
                template.mode(),
                template.comment(),
                template.coordinator(),
                template.readingRounds(),
                template.issueReadingRounds(),
                template.repositories(),
                template.since(),
                offers,
                Map.of("AA1", progress),
                false,
                false,
                false);
    }

    private JsonNode offerWork(final String story) throws Exception {
        return call(
                "/stories/" + story + "/work",
                JSON.writeValueAsString(
                        Map.of(
                                "project", "project",
                                "story", story,
                                "to", "Building",
                                "mode", "AGENT",
                                "revision", store.board().revision().value())),
                200);
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

    private static String event(final String offer, final String event, final String answer) {
        final Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("offer", offer);
        body.put("event", event);
        body.put("container", "box-" + offer);
        body.put("machine", "workstation");
        if (event.equals("ENDED")) {
            body.put(
                    "ended",
                    Map.of(
                            "at",
                            "2026-01-01T00:00:00Z",
                            "finished",
                            true,
                            "why",
                            "",
                            "answer",
                            answer,
                            "unpushed",
                            0));
        }
        return JSON.writeValueAsString(body);
    }

    private static final class Store implements PlanningStore {
        private Board current;
        private int saves = 1;

        private Store() {
            final WorkRule build =
                    new WorkRule(
                            List.of("Backlog"),
                            "Review",
                            "Reading",
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Build {story} {issues}",
                            Map.of("codex", "model"));
            final WorkRule read =
                    new WorkRule(
                            List.of("Building", "Reworking"),
                            "Review",
                            null,
                            "Reworking",
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Read {issues}",
                            Map.of("codex", "model"));
            final WorkRule rework =
                    new WorkRule(
                            List.of("Reading"),
                            "Review",
                            "Reading",
                            null,
                            WorkRule.Per.REPOSITORY,
                            List.of("AGENT"),
                            "Rework {issues}",
                            Map.of("codex", "model"));
            current =
                    new Board(
                            new Revision("r1"),
                            new Workflow(
                                    List.of(
                                            "Backlog",
                                            "Building",
                                            "Reading",
                                            "Reworking",
                                            "Review"),
                                    List.of(),
                                    List.of()),
                            List.of(
                                    new Story(
                                            "ST1",
                                            "Story",
                                            "Backlog",
                                            false,
                                            "High",
                                            List.of("AA1"),
                                            "",
                                            null)),
                            List.of(
                                    new Issue(
                                            "AA1", "alpha", "Issue", "Backlog", null, List.of(),
                                            null, null, "")),
                            new Work(
                                    Map.of("Building", build, "Reading", read, "Reworking", rework),
                                    List.of()),
                            List.of("alpha"));
        }

        /** Supplies the same snapshot to every read. */
        @Override
        public Board board() {
            return current;
        }

        /** Keeps refresh local to the fake. */
        @Override
        public Board refresh() {
            return current;
        }

        /** Makes the actual planning effect observable through HTTP. */
        @Override
        public Board save(final Revision revision, final Writes writes) {
            final Issue old = current.issues().getFirst();
            final String state = writes.issues().getOrDefault(old.id(), old.state());
            current =
                    new Board(
                            new Revision("r" + ++saves),
                            current.workflow(),
                            List.of(
                                    new Story(
                                            "ST1",
                                            "Story",
                                            state,
                                            false,
                                            "High",
                                            List.of("AA1"),
                                            "",
                                            null)),
                            List.of(
                                    new Issue(
                                            old.id(),
                                            old.repository(),
                                            old.title(),
                                            state,
                                            null,
                                            List.of(),
                                            null,
                                            null,
                                            "")),
                            current.work(),
                            current.repositories());
            return current;
        }

        /** The fixture has one participating issue. */
        @Override
        public String storyState(final List<String> states) {
            return states.getFirst();
        }
    }
}
