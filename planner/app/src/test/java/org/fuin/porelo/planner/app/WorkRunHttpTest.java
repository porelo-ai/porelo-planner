package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.fuin.porelo.planner.store.Risk;
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
    void aWorkerChangeOfAnIssueARunningTaskOwnsIsRefusedAndSavesNothing() throws Exception {
        // A worker changed under a running task would hand a person an issue an agent still works.
        final String offer = offerWork("ST1").at("/tasks/0/offer").asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final String revision = store.board().revision().value();
        final var response =
                mvc.perform(
                                put("/projects/project/issues/AA1/worker")
                                        .contentType("application/json")
                                        .content(
                                                "{\"worker\":\"MANUAL\",\"revision\":\""
                                                        + revision
                                                        + "\"}"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus())
                .as("the answer to the change: %s", response.getContentAsString())
                .isEqualTo(409);
        final JsonNode refused = JSON.readTree(response.getContentAsString());
        assertThat(refused.path("type").asString())
                .as("active ownership protects the issue's worker")
                .endsWith(":WorkRunning");
        assertThat(refused.at("/parameters/story").asString())
                .as("the refusal names the running story")
                .isEqualTo("ST1");
        assertThat(store.board().revision().value())
                .as("the refused change saves nothing")
                .isEqualTo(revision);
    }

    @Test
    void aPersonsIssueInAWorkingStateIsNeitherWorkedElsewhereNorWaitingOnAReadingChoice()
            throws Exception {
        // A person's own issue raised as needing them would bury the issues that really do.
        final Issue manual =
                new Issue(
                        "AA1",
                        "alpha",
                        "A person's",
                        "Building",
                        null,
                        List.of(),
                        null,
                        null,
                        "",
                        Worker.MANUAL,
                        Risk.ORDINARY);
        final Issue agents =
                new Issue(
                        "AA2", "alpha", "An agent's", "Building", null, List.of(), null, null, "");
        final Board board =
                new Board(
                        new Revision("r1"),
                        new Workflow(
                                List.of("Backlog", "Building", "Review"), List.of(), List.of()),
                        List.of(
                                new Story(
                                        "ST1",
                                        "Story",
                                        "Building",
                                        false,
                                        "High",
                                        null,
                                        List.of("AA1", "AA2"),
                                        "",
                                        null)),
                        List.of(manual, agents),
                        store.board().work(),
                        List.of("alpha"));
        final WorkRun.Snapshot none = new WorkRun.Snapshot(0, Map.of());

        assertThat(WorkAnswers.issue(board, agents, none).noRunHere())
                .as("an agent's issue in a working state without a run is worked elsewhere")
                .isTrue();
        assertThat(WorkAnswers.issue(board, manual, none).needsYou())
                .as("a person's issue there is their own work")
                .isFalse();
        offerWork("ST1");
        final WorkRun.Run run =
                java.util.Objects.requireNonNull(projects.work("project").work("ST1"));
        final WorkRun.Snapshot stopped =
                new WorkRun.Snapshot(
                        1,
                        Map.of(
                                "ST1",
                                new WorkRun.Run(
                                        run.id(),
                                        run.story(),
                                        run.to(),
                                        run.mode(),
                                        run.comment(),
                                        run.coordinator(),
                                        run.readingRounds(),
                                        run.issueReadingRounds(),
                                        run.repositories(),
                                        run.since(),
                                        run.offers(),
                                        Map.of(
                                                "AA1",
                                                new NextStep.Progress(
                                                        new ReadingCycle.Rounds(2, 2, true),
                                                        List.of(),
                                                        true,
                                                        "ROUND_LIMIT",
                                                        "")),
                                        false,
                                        false,
                                        false)));
        assertThat(stopped.runs().get("ST1").issues().get("AA1").rounds().stopped())
                .as("the fixture's reading of AA1 stopped")
                .isTrue();
        final Answers.Issue answered = WorkAnswers.issue(board, manual, stopped);
        assertThat(answered.needsYou() || answered.readingStopped())
                .as("a stop left from before the switch asks the person for no choice")
                .isFalse();
    }

    @Test
    void theServedRunReportsAStoppedIssueAPersonNowWorksAsTheirsAndNotWaitingOnAChoice()
            throws Exception {
        // A run that still asks for an agent's choice on a person's issue blocks the story's start.
        final String offer =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final String reader =
                call("/tasks/build/events", event(offer, "ENDED", "Built"), 200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/read/events", event(reader, "STARTED", ""), 200);
        final JsonNode read =
                call(
                        "/tasks/read/events",
                        event(reader, "ENDED", "Findings:\n1. blocking | - | - | Preserve data"),
                        200);
        assertThat(read.at("/work/issues/0/readingStopped").asBoolean())
                .as("the fixture's reading of AA1 stopped at its limit")
                .isTrue();
        final var switched =
                mvc.perform(
                                put("/projects/project/issues/AA1/worker")
                                        .contentType("application/json")
                                        .content(
                                                "{\"worker\":\"MANUAL\",\"revision\":\""
                                                        + store.board().revision().value()
                                                        + "\"}"))
                        .andReturn()
                        .getResponse();
        assertThat(switched.getStatus())
                .as(
                        "no task owns AA1, so the switch to a person: %s",
                        switched.getContentAsString())
                .isEqualTo(200);

        final JsonNode served =
                JSON.readTree(
                        mvc.perform(get("/projects/project/stories/ST1/work"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString());

        final JsonNode issue = served.at("/work/issues/0");
        assertThat(issue.path("issue").asString()).as("the run's entry is AA1").isEqualTo("AA1");
        assertThat(issue.path("reason").asString())
                .as("the run says a person works AA1")
                .isEqualTo("MANUAL");
        assertThat(issue.path("needsYou").asBoolean())
                .as("a person's issue needs no agent's choice")
                .isFalse();
        assertThat(issue.path("readingStopped").asBoolean())
                .as("a stop left from before the switch is not offered as a choice")
                .isFalse();
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
    void aStartedTaskAnswersTheEffortItsOrderSaidItUses() throws Exception {
        // A run that answers another effort than the one its task was started with.
        final String story = store.board().stories().getFirst().id();
        final JsonNode proposal =
                call("/stories/" + story + "/proposal", "{\"to\":\"Building\"}", 200);
        final var repositories =
                (tools.jackson.databind.node.ArrayNode) proposal.get("repositories");
        for (final JsonNode repository : repositories) {
            ((tools.jackson.databind.node.ObjectNode) repository.get("main")).put("effort", "low");
        }
        final var decision = JSON.createObjectNode();
        decision.put("project", "project");
        decision.put("story", story);
        decision.put("to", "Building");
        decision.put("mode", "AGENT");
        decision.put("revision", store.board().revision().value());
        decision.set("repositories", repositories);
        final JsonNode order = call("/stories/" + story + "/work", decision.toString(), 200);
        final String offer = order.at("/tasks/0/offer").asString();
        final JsonNode started = call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        assertThat(started.at("/work/tasks/0/effort").asString())
                .as("the started task answers the effort its order named")
                .isEqualTo("low");
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
    void aBuildEndedBeforeItsWorkLandedIsReadOnlyOnceTheWindowReportsWhereItLanded()
            throws Exception {
        // A reader offered before the work left the gate starts on nothing; one offered on every
        // resend of the landing reads the same work twice.
        final String offer =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final JsonNode ended =
                call("/tasks/build/events", event(offer, "ENDED", "Built", List.of()), 200);
        assertThat(ended.path("tasks").size())
                .as("no reader while the work waits at the gate")
                .isZero();
        assertThat(ended.at("/work/tasks/0/ended").has("landed"))
                .as("an end that names no landing answers none")
                .isFalse();
        final JsonNode landed =
                call("/tasks/build/events", event(offer, "LANDED", "", List.of(LANDING)), 200);
        assertThat(landed.path("tasks").size()).as("the landing offers one reader").isEqualTo(1);
        assertThat(landed.at("/tasks/0/role").asString())
                .as("the offer is the build's reading")
                .isEqualTo("READER");
        assertThat(landed.at("/tasks/0/reads"))
                .as("the window starts the reader naming the build's task")
                .hasSize(1)
                .first()
                .satisfies(
                        task ->
                                assertThat(task.asString())
                                        .as("the task the reader reads")
                                        .isEqualTo("build"));
        assertThat(
                        landed.path("work")
                                .path("tasks")
                                .valueStream()
                                .filter(
                                        t ->
                                                t.path("role").asString().equals("BUILDER")
                                                        || t.path("role").asString().equals("MAIN"))
                                .findFirst()
                                .orElseThrow()
                                .at("/ended/landed/0/commit")
                                .asString())
                .as("the build's end keeps where its work landed")
                .isEqualTo(LANDING.get("commit"));
        final JsonNode again =
                call("/tasks/build/events", event(offer, "LANDED", "", List.of(LANDING)), 409);
        assertThat(again.path("type").asString())
                .as("a resent landing is a conflict the window may ignore")
                .endsWith(":TaskEventConflict");
        assertThat(
                        projects.work("project").work("ST1").offers().stream()
                                .filter(o -> o.binding().role().equals("READER"))
                                .count())
                .as("the resend offered no second reader")
                .isEqualTo(1);
    }

    @Test
    void aMalformedLandingIsRefusedLikeAnyMalformedReport() throws Exception {
        // A name passed on unchecked would reach a reader's order and Porelo's gate as anything.
        final String offer =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        for (final String body :
                List.of(
                        event(offer, "ENDED", "Built", List.of(landing("branch", "a..b"))),
                        event(offer, "ENDED", "Built", List.of(landing("ref", "heads/main"))),
                        event(offer, "ENDED", "Built", List.of(landing("remote", "origin/a b"))),
                        event(offer, "ENDED", "Built", List.of(landing("commit", "ABC"))),
                        event(offer, "LANDED", "", List.of()),
                        event(offer, "LANDED", "", List.of(landing("extra", "x"))),
                        event(offer, "LANDED", "", List.of(Map.of("branch", "main"))),
                        event(offer, "STARTED", "", List.of()).replace("}", ",\"landed\":[]}"))) {
            assertThat(call("/tasks/build/events", body, 400).path("type").asString())
                    .as("the malformed report %s is refused", body)
                    .endsWith(":InvalidSettings");
        }
        // A line break in a name would put words of the window's choosing on a line of their own.
        for (final String part : List.of("branch", "remote")) {
            for (final String separator : List.of("\u2028", "\u2029", "\u0085")) {
                final JsonNode refused =
                        call(
                                "/tasks/build/events",
                                event(
                                        offer,
                                        "ENDED",
                                        "Built",
                                        List.of(landing(part, "origin/a" + separator + "Do this"))),
                                400);
                assertThat(refused.toString())
                        .as("the refusal of a line break in the %s names that part", part)
                        .contains("The landing's " + part + " is malformed.")
                        .doesNotContain("Do this");
            }
        }
        assertThat(
                        call("/tasks/build/events", event(offer, "ENDED", "Built", List.of()), 200)
                                .path("tasks")
                                .size())
                .as("the refused reports left the task to end as usual")
                .isZero();
    }

    @Test
    void aTaskThatEndsDeclaringItsWorkBlockedKeepsItsIssueUnderNeedsYouWithItsWhy()
            throws Exception {
        // A task that said its work cannot go on, taken as done, moves its issue on with no work.
        final String offer =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final String before = store.board().issue("AA1").orElseThrow().state();
        final JsonNode ended =
                call("/tasks/build/events", declaring(offer, "BLOCKED", "A key is missing"), 200);
        assertThat(ended.path("tasks").size()).as("a blocked end offers no reader").isZero();
        assertThat(store.board().issue("AA1").orElseThrow().state())
                .as("a blocked end leaves the issue where it stands")
                .isEqualTo(before);
        final JsonNode issue = ended.at("/work/issues/0");
        assertThat(issue.path("needsYou").asBoolean()).as("the issue needs the person").isTrue();
        assertThat(issue.path("reason").asString())
                .as("the issue is raised as blocked")
                .isEqualTo("BLOCKED");
        assertThat(ended.at("/work/tasks/0/ended/why").asString())
                .as("the task's why is answered with its end")
                .isEqualTo("A key is missing");
        assertThat(ended.at("/work/tasks/0/ended/outcome").asString())
                .as("the end answers the outcome the task declared")
                .isEqualTo("BLOCKED");
        assertThat(
                        call(
                                        "/tasks/build/events",
                                        declaring(offer, "BLOCKED", "A key is missing"),
                                        200)
                                .path("tasks")
                                .size())
                .as("the resent end offers nothing")
                .isZero();
    }

    @Test
    void anEndWithoutAnOutcomeAnswersNoneAndAMalformedOutcomeIsRefused() throws Exception {
        // An outcome taken in another spelling or form would be judged as none, and a blocked
        // task would move on as done.
        final String offer =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":1}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(offer, "STARTED", ""), 200);
        final String done = declaring(offer, "DONE", "");
        for (final String body :
                List.of(
                        done.replace("\"DONE\"", "\"blocked\""),
                        done.replace("\"DONE\"", "\"UNPUSHED\""),
                        done.replace("\"DONE\"", "\" BLOCKED\""),
                        done.replace("\"DONE\"", "\"\""),
                        done.replace("\"DONE\"", "null"),
                        done.replace("\"DONE\"", "1"),
                        done.replace("\"DONE\"", "[\"BLOCKED\"]"))) {
            assertThat(call("/tasks/build/events", body, 400).path("type").asString())
                    .as("the malformed outcome in %s is refused", body)
                    .endsWith(":InvalidSettings");
        }
        final JsonNode ended = call("/tasks/build/events", event(offer, "ENDED", "Built"), 200);
        assertThat(ended.at("/work/tasks/0/ended").has("outcome"))
                .as("an end that declared nothing answers no outcome")
                .isFalse();
        assertThat(ended.path("tasks").size())
                .as("the refused ends left the task to end as usual")
                .isEqualTo(1);
    }

    @Test
    void workForChosenIssuesIsProposedAndOfferedForThemAloneAndAMalformedListIsRefused()
            throws Exception {
        // A list taken loosely starts work for issues the person did not move, or two agents on
        // one issue.
        final JsonNode proposal =
                call("/stories/ST1/proposal", "{\"to\":\"Building\",\"issues\":[\"AA1\"]}", 200);
        assertThat(proposal.at("/repositories/0/issues/0").asString())
                .as("the proposal names the chosen issue")
                .isEqualTo("AA1");
        final JsonNode order =
                call(
                        "/stories/ST1/work",
                        "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"issues\":[\"AA1\"]}",
                        200);
        assertThat(order.at("/tasks/0/issues/0").asString())
                .as("the order offers work for the chosen issue")
                .isEqualTo("AA1");
        for (final String issues :
                List.of("[]", "[\"AA1\",\"AA1\"]", "[\"aa1\"]", "\"AA1\"", "[1]", "null")) {
            assertThat(
                            call(
                                            "/stories/ST1/proposal",
                                            "{\"to\":\"Building\",\"issues\":" + issues + "}",
                                            400)
                                    .path("type")
                                    .asString())
                    .as("the proposal naming %s is refused", issues)
                    .endsWith(":InvalidSettings");
            assertThat(
                            call(
                                            "/stories/ST1/work",
                                            "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"issues\":"
                                                    + issues
                                                    + "}",
                                            400)
                                    .path("type")
                                    .asString())
                    .as("the order naming %s is refused", issues)
                    .endsWith(":InvalidSettings");
        }
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
    void aReworkChoiceWithSettingsTheReworkingRuleDoesNotOfferOrOnlyPartOfThemIsRefused()
            throws Exception {
        // A rework in settings the work rules do not allow would run an agent nobody checked.
        final String build =
                call(
                                "/stories/ST1/work",
                                "{\"project\":\"project\",\"story\":\"ST1\",\"to\":\"Building\",\"mode\":\"AGENT\",\"revision\":\"r1\",\"readingRounds\":2}",
                                200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/build/events", event(build, "STARTED", ""), 200);
        final String reader =
                call("/tasks/build/events", event(build, "ENDED", "Built"), 200)
                        .at("/tasks/0/offer")
                        .asString();
        call("/tasks/read/events", event(reader, "STARTED", ""), 200);
        final JsonNode stopped =
                call(
                        "/tasks/read/events",
                        event(reader, "ENDED", "Findings:\n1. blocking | - | - | Preserve data"),
                        200);
        assertThat(stopped.at("/work/issues/0/reason").asString())
                .as("a reading left still stops on blocking findings")
                .isEqualTo("BLOCKING_FINDINGS");
        assertThat(stopped.path("tasks").size()).as("no rework starts by itself").isZero();
        final String revision = store.board().revision().value();
        final String choice =
                "{\"story\":\"ST1\",\"round\":1,\"choice\":\"REWORK\",\"comment\":\"\",\"revision\":\""
                        + revision
                        + "\"";
        for (final String settings :
                List.of(
                        ",\"mode\":\"SHELL\",\"agent\":\"codex\",\"model\":\"model\"",
                        ",\"agent\":\"codex\",\"model\":\"model\"")) {
            assertThat(
                            call("/issues/AA1/reading", choice + settings + "}", 400)
                                    .path("type")
                                    .asString())
                    .as("%s is refused", settings)
                    .endsWith(":InvalidSettings");
        }
        assertThat(store.board().revision().value())
                .as("a refused choice saves nothing")
                .isEqualTo(revision);
        final JsonNode rework = call("/issues/AA1/reading", choice + "}", 200);
        assertThat(
                        List.of(
                                rework.at("/tasks/0/mode").asString(),
                                rework.at("/tasks/0/agent").asString(),
                                rework.at("/tasks/0/model").asString()))
                .as("a choice without settings reworks in the rule's first mode and model")
                .containsExactly("AGENT", "codex", "model");
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
                                        old.text(),
                                        old.worker(),
                                        old.risk())),
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
        return event(offer, event, answer, List.of(LANDING));
    }

    /** A report as the window sends it; an end names where its work landed, if it has. */
    private static String event(
            final String offer,
            final String event,
            final String answer,
            final List<Map<String, String>> landed) {
        final Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("offer", offer);
        body.put("event", event);
        body.put("container", "box-" + offer);
        body.put("machine", "workstation");
        if (event.equals("ENDED")) {
            final Map<String, Object> ended = new java.util.LinkedHashMap<>();
            ended.put("at", "2026-01-01T00:00:00Z");
            ended.put("finished", true);
            ended.put("why", "");
            ended.put("answer", answer);
            ended.put("unpushed", 0);
            if (!landed.isEmpty()) {
                ended.put("landed", landed);
            }
            body.put("ended", ended);
        }
        if (event.equals("LANDED")) {
            body.put("landed", landed);
        }
        return JSON.writeValueAsString(body);
    }

    /** An end that finished and pushed everything, declaring {@code outcome} with {@code why}. */
    private static String declaring(final String offer, final String outcome, final String why)
            throws Exception {
        final var body =
                (tools.jackson.databind.node.ObjectNode)
                        JSON.readTree(event(offer, "ENDED", "Built"));
        final var ended = (tools.jackson.databind.node.ObjectNode) body.path("ended");
        ended.put("why", why);
        ended.put("outcome", outcome);
        return JSON.writeValueAsString(body);
    }

    /** Where Porelo reports a build's work landed, as its task row names it. */
    private static final Map<String, String> LANDING =
            Map.of(
                    "branch", "porelo/build",
                    "ref", "refs/landed/build/porelo/build",
                    "remote", "origin/landed/build/porelo/build",
                    "commit", "0123456789abcdef0123456789abcdef01234567");

    private static Map<String, String> landing(final String name, final String value) {
        final Map<String, String> changed = new java.util.LinkedHashMap<>(LANDING);
        changed.put(name, value);
        return changed;
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
                                            null,
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
                                            null,
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
                                            "",
                                            writes.workers().getOrDefault(old.id(), old.worker()),
                                            Risk.ORDINARY)),
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
