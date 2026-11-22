package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.NextStep;
import org.fuin.porelo.planner.core.ReadingCycle;
import org.fuin.porelo.planner.core.WorkEnds;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Ids;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.OutsideIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The board reads as the window calls them, over a store that answers what a test gives it. */
class BoardReadsTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<String> opened = new ArrayList<>();

    private final WorkRecord records = new WorkRecord(Map.of(), true);

    private @Nullable RuntimeException refusal;

    private MockMvc mvc;

    private Board supplied = board();

    private int reads;

    private static Board board() {
        final Map<String, WorkRule> rules = new LinkedHashMap<>();
        rules.put(
                "Implementing",
                new WorkRule(
                        List.of("Backlog"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("UNATTENDED"),
                        "Build {issues}.",
                        Map.of("claude", "claude-opus-5-5")));
        rules.put(
                "Finished",
                new WorkRule(
                        List.of("Review"),
                        "Review",
                        null,
                        null,
                        WorkRule.Per.STORY,
                        List.of("AGENT"),
                        "Close it.",
                        Map.of("claude", "claude-opus-5-5")));
        return new Board(
                new Revision("porelo-store@c1"),
                new Workflow(
                        List.of("Backlog", "Implementing", "Review", "Finished"),
                        List.of(new Workflow.Phase("Open", List.of("Backlog", "Implementing"))),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "Given",
                                "Review",
                                true,
                                "High",
                                "M",
                                List.of("AB01"),
                                "The story's text.",
                                URI.create("https://example.org/ST1")),
                        new Story(
                                "ST2",
                                "By issues",
                                "Backlog",
                                false,
                                "Low",
                                null,
                                List.of("AB01.1", "AB01"),
                                "Another text.",
                                null)),
                List.of(
                        new Issue(
                                "AB01",
                                "porelo-store",
                                "Parent",
                                "Backlog",
                                "S",
                                List.of(),
                                null,
                                null,
                                "# AB01 — Parent\n\n**What must be true.** The parent holds,\nin two lines.\n\n## Plan\n"),
                        new Issue(
                                "AB01.1",
                                "porelo-store",
                                "Child",
                                "Review",
                                null,
                                List.of("AB01"),
                                "AB01",
                                null,
                                "No such paragraph.")),
                new Work(rules, List.of("stories/work.yml: Testing: no state of the workflow")));
    }

    @BeforeEach
    void serve() {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        reads++;
                        if (refusal != null) {
                            throw refusal;
                        }
                        return supplied;
                    }

                    @Override
                    public Board refresh() {
                        return board();
                    }

                    @Override
                    public @Nullable String storyState(final List<String> issueStates) {
                        return BoardReadsTest.storyState(issueStates);
                    }

                    @Override
                    public TextFormat textFormat() {
                        return TextFormat.MARKDOWN;
                    }
                };
        final Projects projects =
                new Projects(
                        (project, repository, branch) -> {
                            if (repository.startsWith("/")) {
                                throw new Refusal(
                                        "NotACloneUrl",
                                        HttpStatus.BAD_REQUEST,
                                        "Not a clone URL the planner takes",
                                        Map.of("project", project));
                            }
                            opened.add(project + " " + repository + " " + branch);
                            return store;
                        },
                        8,
                        records);
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(
                                        projects,
                                        new ChangesRegistry(
                                                projects,
                                                Duration.ofHours(1),
                                                Executors.newSingleThreadScheduledExecutor())))
                        .setControllerAdvice(new Problems())
                        .build();
    }

    /**
     * The stories index's rule over this board's workflow: the earliest open state, else Finished.
     */
    private static @Nullable String storyState(final List<String> issueStates) {
        final List<String> order = board().workflow().states();
        return issueStates.stream()
                .filter(state -> !"Finished".equals(state))
                .min(java.util.Comparator.comparingInt(order::indexOf))
                .orElse(issueStates.isEmpty() ? null : "Finished");
    }

    private void given() throws Exception {
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @Test
    void aProjectNotGivenIsNoSuchProjectAndOneGivenAnswers() throws Exception {
        // The window sends its PUT and retries once on exactly this answer.
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchProject"))
                .andExpect(jsonPath("$.parameters.project").value("alpha"));
        given();
        given();
        assertThat(opened).as("the same source opened once").hasSize(1);
        mvc.perform(get("/projects/alpha/workflow")).andExpect(status().isOk());
    }

    @Test
    void theWorkflowGivesEachStateItsKindAndOnlyTheSoundRules() throws Exception {
        // A closed state shown as working would let the window start work on a finished story.
        given();
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("porelo-store@c1"))
                .andExpect(jsonPath("$.states[0].name").value("Backlog"))
                .andExpect(jsonPath("$.states[0].kind").value("WAITING"))
                .andExpect(jsonPath("$.states[1].kind").value("WORKING"))
                .andExpect(jsonPath("$.states[2].kind").value("WAITING"))
                .andExpect(jsonPath("$.states[3].kind").value("CLOSED"))
                .andExpect(jsonPath("$.phases[0].states[1]").value("Implementing"))
                .andExpect(jsonPath("$.work.length()").value(1))
                .andExpect(jsonPath("$.work[0].state").value("Implementing"))
                .andExpect(jsonPath("$.work[0].per").value("repository"))
                .andExpect(jsonPath("$.work[0].then").isEmpty())
                .andExpect(jsonPath("$.work[0].models.claude").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.faults.length()").value(2))
                .andExpect(
                        jsonPath("$.faults[1]")
                                .value("stories/work.yml: Finished: a closed state"));
    }

    @Test
    void theStoriesAndTheBoardSayWhatIsStillOpenAndWhatWaitsForReview() throws Exception {
        // Harm: a wrong Needs you count, or a story shown done with work left.
        given();
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[1].open.left").value(1))
                .andExpect(jsonPath("$.stories[1].open.review").value(1))
                .andExpect(jsonPath("$.stories[1].open.blocked").value(0))
                .andExpect(jsonPath("$.stories[1].open.states[0].name").value("Backlog"))
                .andExpect(jsonPath("$.stories[1].open.states[0].issues").value(1))
                .andExpect(jsonPath("$.stories[1].open.states[2].name").value("Review"))
                .andExpect(jsonPath("$.stories[1].open.states[2].issues").value(1))
                .andExpect(jsonPath("$.stories[1].open.sizes[0].name").value("S"))
                .andExpect(jsonPath("$.stories[1].open.minutes").isEmpty())
                .andExpect(jsonPath("$.stories[1].open.unestimated").value(1))
                .andExpect(jsonPath("$.stories[1].issues[0].minutesLeft").isEmpty())
                .andExpect(
                        jsonPath(
                                "$.stories[1].issues[0]",
                                org.hamcrest.Matchers.hasKey("minutesLeft")))
                // ST1 names AB01 alone; its sub-issue AB01.1 waits for review and counts in it.
                .andExpect(jsonPath("$.stories[0].open.left").value(1))
                .andExpect(jsonPath("$.stories[0].open.review").value(1))
                // The board's figures count every issue once, whatever stories name it.
                .andExpect(jsonPath("$.open.left").value(1))
                .andExpect(jsonPath("$.open.review").value(1));
        mvc.perform(get("/projects/alpha/stories").param("state", "Review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories.length()").value(1))
                .andExpect(jsonPath("$.open.review").value(1));
    }

    @Test
    void theStoriesCarryTheirIssuesAndWhetherTheirStateWasGivenButNoDescription() throws Exception {
        // A story whose state was given by hand must not be moved by its issues' states; a story's
        // size must reach the window as its head gives it, and as null without one.
        given();
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories.length()").value(2))
                .andExpect(jsonPath("$.stories[0].stateGiven").value(true))
                .andExpect(jsonPath("$.stories[0].link").value("https://example.org/ST1"))
                .andExpect(jsonPath("$.stories[0].description").doesNotExist())
                .andExpect(jsonPath("$.stories[0].work").isEmpty())
                .andExpect(jsonPath("$.stories[0]", org.hamcrest.Matchers.hasKey("work")))
                .andExpect(jsonPath("$.stories[0].size").value("M"))
                .andExpect(jsonPath("$.stories[1].size").isEmpty())
                .andExpect(jsonPath("$.stories[1]", org.hamcrest.Matchers.hasKey("size")))
                .andExpect(jsonPath("$.stories[1].stateGiven").value(false))
                // Harm: a story given Review over an issue in Backlog shown as fitting, taken as
                // done.
                .andExpect(
                        jsonPath("$.stories[0].misfits")
                                .value(org.hamcrest.Matchers.contains("AB01")))
                .andExpect(jsonPath("$.stories[1].misfits").isEmpty())
                .andExpect(jsonPath("$.stories[1]", org.hamcrest.Matchers.hasKey("misfits")))
                // Harm: a given story offered back to its issues without the state they give.
                .andExpect(jsonPath("$.stories[0].derivedState").value("Backlog"))
                .andExpect(jsonPath("$.stories[1].derivedState").isEmpty())
                .andExpect(jsonPath("$.stories[1]", org.hamcrest.Matchers.hasKey("derivedState")))
                .andExpect(jsonPath("$.stories[1].issues[0].id").value("AB01.1"))
                .andExpect(jsonPath("$.stories[1].issues[1].id").value("AB01"));
        mvc.perform(get("/projects/alpha/stories").param("state", "Backlog"))
                .andExpect(jsonPath("$.stories.length()").value(1))
                .andExpect(jsonPath("$.stories[0].id").value("ST2"));
    }

    @Test
    void theStreamedBoardNamesTheMisfitsOfAGivenState() {
        // Harm: a window following the stream losing the mark the board's read showed.
        final org.fuin.porelo.planner.app.answers.Board streamed =
                BoardController.boardOf(
                        board(), new WorkRun.Snapshot(0, Map.of()), BoardReadsTest::storyState);
        assertThat(streamed.stories())
                .as("ST1 given Review over AB01 in Backlog; ST2 by its issues")
                .extracting(org.fuin.porelo.planner.app.answers.Story::misfits)
                .containsExactly(List.of("AB01"), List.of());
        // Harm: a stream without the state a given story's issues give hands the story nowhere.
        assertThat(streamed.stories())
                .as("ST1's issue gives Backlog; ST2's state is not given")
                .extracting(org.fuin.porelo.planner.app.answers.Story::derivedState)
                .containsExactly("Backlog", null);
    }

    @Test
    void oneStoryCarriesItsDescriptionAndItsIssuesInItsOrder() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/stories/ST1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("porelo-store@c1"))
                .andExpect(jsonPath("$.story.description").value("The story's text."))
                // Harm: the window shows the store's Markdown as raw signs, or a text as Markdown
                // that is none.
                .andExpect(jsonPath("$.story.descriptionFormat").value("MARKDOWN"))
                .andExpect(jsonPath("$.story.size").value("M"))
                // Harm: the story page showing a given state as fitting while the board marks it.
                .andExpect(
                        jsonPath("$.story.misfits").value(org.hamcrest.Matchers.contains("AB01")))
                .andExpect(jsonPath("$.story.derivedState").value("Backlog"))
                .andExpect(jsonPath("$.story.issues[0].id").value("AB01"));
        mvc.perform(get("/projects/alpha/stories/ST2/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].id").value("AB01.1"))
                .andExpect(jsonPath("$.issues[0].parent").value("AB01"))
                .andExpect(jsonPath("$.issues[0].size").isEmpty())
                .andExpect(jsonPath("$.issues[0].blockedBy[0]").value("AB01"));
    }

    @Test
    void theIssuesInNoStoryExcludeStoryIssuesAndTheirDescendantsAtTheBoardsRevision()
            throws Exception {
        // Harm: an issue hidden from the board, or a story's work counted twice.
        supplied =
                issueBoard(
                        List.of("porelo-store#1"),
                        List.of(
                                unassignedIssue("porelo-store#1", "Backlog", null),
                                unassignedIssue("porelo-store#2", "Review", null),
                                unassignedIssue("porelo-store#3", "Backlog", "porelo-store#1"),
                                unassignedIssue("porelo-store#4", "Backlog", "porelo-store#3")));
        given();

        final JsonNode answer = issuesInNoStory();

        assertThat(answer.path("revision").asString())
                .as("the revision of the board that supplied the issues")
                .isEqualTo("unassigned@c2");
        assertThat(answer.path("issues").valueStream().map(i -> i.path("id").asString()).toList())
                .as("only the issue neither named by a story nor descended from one it names")
                .containsExactly("porelo-store#2");
    }

    @Test
    void aSubIssueOfAnIssueInNoStoryIsStillListed() throws Exception {
        // Harm: filtering every sub-issue hides work whose whole family is in no story.
        supplied =
                issueBoard(
                        List.of(),
                        List.of(
                                unassignedIssue("porelo-store#1", "Review", null),
                                unassignedIssue("porelo-other#3", "Backlog", null),
                                unassignedIssue("porelo-store#2", "Backlog", "porelo-store#1")));
        given();

        final JsonNode answer = issuesInNoStory();

        assertThat(answer.path("issues").valueStream().map(i -> i.path("id").asString()).toList())
                .as("every unassigned issue, by workflow state with the board's order preserved")
                .containsExactly("porelo-other#3", "porelo-store#2", "porelo-store#1");
    }

    @Test
    void anAgentsIssueInNoStoryInAWorkingStateHasNoRunHere() throws Exception {
        // Harm: work that stands in a working state with no run anywhere here looks looked after.
        supplied =
                issueBoard(
                        List.of(),
                        List.of(unassignedIssue("porelo-store#2", "Implementing", null)));
        given();

        final JsonNode answer = issuesInNoStory();

        assertThat(answer.at("/issues/0/noRunHere").asBoolean())
                .as("an agent's issue in a working state, which no story's run can hold")
                .isTrue();
    }

    @Test
    void anIssueInNoStoryHasTheSameFieldsAsTheSameIssueInAStory() throws Exception {
        // Harm: a loose issue loses its reading progress or breaks the screen on a missing field.
        final List<Issue> issues =
                List.of(unassignedIssue("porelo-store#2", "Backlog", "porelo-store#1"));
        supplied = issueBoard(List.of(), issues);
        given();
        givenReadingProgress();
        final JsonNode unassigned = issuesInNoStory();

        supplied = issueBoard(List.of("porelo-store#2"), issues);
        final var response = mvc.perform(get("/projects/alpha/stories/ST1/issues")).andReturn();
        assertThat(response.getResponse().getStatus())
                .as("the story issue list used as the screen's reference")
                .isEqualTo(200);
        final JsonNode assigned =
                JsonMapper.builder().build().readTree(response.getResponse().getContentAsString());

        assertThat(unassigned.path("issues").size()).as("the loose issue is present").isEqualTo(1);
        assertThat(unassigned.path("issues"))
                .as("the complete issue representation, including nulls and work flags")
                .isEqualTo(assigned.path("issues"));
        assertThat(unassigned.at("/issues/0/rounds").asInt())
                .as("the recorded reading rounds survive detachment from the story")
                .isEqualTo(2);
        assertThat(unassigned.at("/issues/0/needsYou").asBoolean())
                .as("the recorded need for the person remains visible")
                .isTrue();
        assertThat(unassigned.at("/issues/0/readingStopped").asBoolean())
                .as("the stopped reading remains visible")
                .isTrue();
        assertThat(unassigned.at("/issues/0/attention").asString())
                .as("the recorded reason for attention is retained")
                .isEqualTo("STOPPED");
        assertThat(unassigned.at("/issues/0/findings/0/text").asString())
                .as("the finding remains available to the person")
                .isEqualTo("The issue needs a repair.");
    }

    @Test
    void anIssueSaysWhatItWaitsOnOutsideTheBoardDoneOpenOrNotReadable() throws Exception {
        // Harm: the window shows an issue free whose blocker in another repository is open or
        // could not be read, and the person starts work that cannot be built yet.
        final Issue waiting =
                new Issue(
                        "porelo-store#2",
                        "porelo-store",
                        "Waiting",
                        "Backlog",
                        "S",
                        List.of(),
                        null,
                        null,
                        "",
                        Worker.AGENT,
                        org.fuin.porelo.planner.store.Risk.ORDINARY,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(
                                new org.fuin.porelo.planner.store.Wait(
                                        "other-org/done#1",
                                        true,
                                        URI.create("https://github.com/other-org/done/issues/1")),
                                new org.fuin.porelo.planner.store.Wait(
                                        "other-org/open#2", false, null),
                                org.fuin.porelo.planner.store.Wait.unreadable()));
        supplied = issueBoard(List.of("porelo-store#2"), List.of(waiting));
        given();

        for (final String path :
                List.of(
                        "/projects/alpha/stories/ST1/issues",
                        "/projects/alpha/issues/porelo-store%232")) {
            final String at = path.endsWith("issues") ? "$.issues[0]" : "$.issue";
            mvc.perform(get(URI.create(path)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(at + ".blockedBy").isEmpty())
                    .andExpect(jsonPath(at + ".waitsOn[0].reference").value("other-org/done#1"))
                    .andExpect(jsonPath(at + ".waitsOn[0].done").value(true))
                    .andExpect(
                            jsonPath(at + ".waitsOn[0].link")
                                    .value("https://github.com/other-org/done/issues/1"))
                    .andExpect(jsonPath(at + ".waitsOn[1].done").value(false))
                    .andExpect(jsonPath(at + ".waitsOn[1].link").isEmpty())
                    .andExpect(jsonPath(at + ".waitsOn[2].reference").isEmpty())
                    .andExpect(jsonPath(at + ".waitsOn[2]", org.hamcrest.Matchers.hasKey("done")))
                    .andExpect(jsonPath(at + ".waitsOn[2].done").isEmpty());
        }
    }

    private void givenReadingProgress() {
        final NextStep.Progress progress =
                new NextStep.Progress(
                        new ReadingCycle.Rounds(2, 3, true),
                        List.of(
                                new WorkEnds.Finding(
                                        1,
                                        WorkEnds.Severity.BLOCKING,
                                        null,
                                        null,
                                        "The issue needs a repair.")),
                        true,
                        "STOPPED",
                        "");
        final WorkRun.Run run =
                new WorkRun.Run(
                        "run",
                        "ST1",
                        "Implementing",
                        "AGENT",
                        "",
                        "",
                        3,
                        List.of(),
                        List.of(),
                        Instant.EPOCH.toString(),
                        List.of(),
                        Map.of("porelo-store#2", progress),
                        true,
                        false,
                        false);
        records.open("alpha", "git@example.org:a/porelo-store.git", null)
                .locked(
                        session -> {
                            session.write(new WorkRun.Snapshot(1, Map.of("ST1", run)));
                            return true;
                        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ST1", "all", "NONE"})
    void aMissingOrUnsupportedStoryFilterIsRefusedBeforeReading(final @Nullable String story)
            throws Exception {
        // Harm: an unintended list served under a contract nobody agreed, with needless reads.
        given();
        reads = 0;
        final var request = get("/projects/alpha/issues");
        if (story != null) {
            request.param("story", story);
        }

        final var response = mvc.perform(request).andReturn().getResponse();

        assertThat(response.getStatus()).as("the invalid filter's status").isEqualTo(400);
        assertThat(response.getContentType())
                .as("a problem the window can read")
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        final JsonNode problem =
                JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(problem.path("type").asString())
                .as("the API's bad parameter refusal")
                .isEqualTo("urn:porelo-planner:InvalidName");
        assertThat(problem.path("parameters").propertyNames())
                .as("the refusal names the project alone, never the value sent")
                .containsExactly("project");
        assertThat(reads).as("no board read for a refused query").isZero();
    }

    /** A board whose story stands outside the workflow and names an id on two issues. */
    private static Board warnedBoard() {
        final Board base = board();
        final List<Issue> issues = new ArrayList<>(base.issues());
        for (final String repository : List.of("porelo-store", "porelo-other")) {
            issues.add(
                    new Issue(
                                    "AB05",
                                    repository,
                                    "Twice",
                                    "Backlog",
                                    "S",
                                    List.of(),
                                    null,
                                    null,
                                    "")
                            .withWarnings(List.of(Warning.idTwice())));
        }
        issues.add(
                new Issue(
                                "AB06",
                                "porelo-store",
                                "Parked",
                                "Parked",
                                "S",
                                List.of(),
                                null,
                                null,
                                "")
                        .withWarnings(List.of(Warning.stateOutsideWorkflow())));
        issues.add(
                new Issue(
                        "AB07",
                        "porelo-store",
                        "Waiting",
                        "Backlog",
                        "S",
                        List.of(),
                        null,
                        null,
                        ""));
        final List<Story> stories = new ArrayList<>(base.stories());
        stories.add(
                new Story(
                                "ST3",
                                "Parked by hand",
                                "Parked",
                                true,
                                "High",
                                null,
                                List.of("AB05"),
                                "",
                                null)
                        .withWarnings(List.of(Warning.stateOutsideWorkflow())));
        return new Board(
                base.revision(),
                base.workflow(),
                stories,
                issues,
                base.work(),
                List.of("porelo-store", "porelo-other"));
    }

    @Test
    void everyItemIsServedWithItsWarningsAndAnIdOnTwoIssuesShowsBoth() throws Exception {
        // Harm: an item hidden from the board, or shown without what is wrong with it.
        supplied = warnedBoard();
        given();
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].warnings").isEmpty())
                .andExpect(jsonPath("$.stories[0]", org.hamcrest.Matchers.hasKey("warnings")))
                .andExpect(jsonPath("$.stories[2].state").value("Parked"))
                .andExpect(jsonPath("$.stories[2].warnings[0].kind").value("StateOutsideWorkflow"))
                .andExpect(
                        jsonPath("$.stories[2].warnings[0].text")
                                .value(Warning.stateOutsideWorkflow().text()))
                .andExpect(jsonPath("$.stories[2].issues.length()").value(2))
                .andExpect(jsonPath("$.stories[2].issues[0].repository").value("porelo-store"))
                .andExpect(jsonPath("$.stories[2].issues[1].repository").value("porelo-other"))
                .andExpect(jsonPath("$.stories[2].issues[1].warnings[0].kind").value("IdTwice"))
                .andExpect(jsonPath("$.stories[2].misfits").isEmpty())
                .andExpect(jsonPath("$.stories[2].open").isEmpty());
        final JsonNode none = issuesInNoStory();
        assertThat(none.path("issues").valueStream().map(i -> i.path("id").asString()).toList())
                .as("an issue outside the workflow follows those in its states")
                .containsExactly("AB07", "AB06");
        assertThat(none.path("issues").get(1).path("warnings").get(0).path("text").asString())
                .as("with its warning's text")
                .isEqualTo(Warning.stateOutsideWorkflow().text());
        mvc.perform(get("/projects/alpha/issues/AB05"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"));
    }

    private JsonNode issuesInNoStory() throws Exception {
        final var response =
                mvc.perform(get("/projects/alpha/issues").param("story", "none"))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus())
                .as("the list of issues in no story: %s", response.getContentAsString())
                .isEqualTo(200);
        return JsonMapper.builder().build().readTree(response.getContentAsString());
    }

    private static Board issueBoard(final List<String> named, final List<Issue> issues) {
        final Board base = board();
        return new Board(
                new Revision("unassigned@c2"),
                base.workflow(),
                List.of(new Story("ST1", "Story", "Backlog", true, "High", "M", named, "", null)),
                issues,
                base.work(),
                List.of("porelo-store", "porelo-other"),
                List.of(),
                null,
                List.of(
                        new OutsideIssue(
                                "porelo-store#5",
                                "porelo-store",
                                "Outside",
                                "Paused",
                                null,
                                null,
                                null,
                                Worker.AGENT)));
    }

    private static Issue unassignedIssue(
            final String id, final String state, final @Nullable String parent) {
        return new Issue(
                id,
                Ids.repositoryOf(id),
                "Work",
                state,
                "S",
                List.of(Ids.repositoryOf(id) + "#9"),
                parent,
                URI.create("https://example.org/work"),
                "The issue's text.");
    }

    @Test
    void oneIssueCarriesItsTextItsSubIssuesAndWhatMustBeTrueWhenItHasIt() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/issues/AB01"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.issue.whatMustBeTrue")
                                .value("The parent holds,\nin two lines."))
                .andExpect(jsonPath("$.issue.subIssues[0]").value("AB01.1"))
                .andExpect(jsonPath("$.issue.parent").isEmpty())
                .andExpect(jsonPath("$.issue", org.hamcrest.Matchers.hasKey("parent")));
        mvc.perform(get("/projects/alpha/issues/AB01.1"))
                .andExpect(jsonPath("$.issue.whatMustBeTrue").doesNotExist())
                .andExpect(jsonPath("$.issue.text").value("No such paragraph."))
                .andExpect(jsonPath("$.issue.textFormat").value("MARKDOWN"));
    }

    @Test
    void anUnknownStoryOrIssueAndABadIdAreRefusedByName() throws Exception {
        given();
        mvc.perform(get("/projects/alpha/stories/ST9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"))
                .andExpect(jsonPath("$.parameters.story").value("ST9"));
        mvc.perform(get("/projects/alpha/issues/ZZ9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"))
                .andExpect(jsonPath("$.parameters.issue").value("ZZ9"));
        mvc.perform(get("/projects/alpha/stories/bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        mvc.perform(get("/projects/Bad..Name/workflow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
    }

    @Test
    void aRefusedSourceIsNotACloneUrlAndLeavesNoProject() throws Exception {
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"repository\": \"/home/person/git/p\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotACloneUrl"));
        mvc.perform(get("/projects/alpha/workflow")).andExpect(status().isNotFound());
    }

    @Test
    void eachStoreRefusalIsItsProblemNamingTheRepository() throws Exception {
        // The window offers Try again on these and shows the planner's words.
        given();
        refusal = new StoreException.CredentialRefused("porelo-store");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CredentialRefused"))
                .andExpect(jsonPath("$.parameters.repository").value("porelo-store"));
        refusal = new StoreException.Unreachable("porelo-store", "timed out");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Unreachable"));
        refusal = new StoreException.Unreadable("porelo-store", "issues/README.md is no index");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Unreadable"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(org.hamcrest.Matchers.containsString("no index")));
        refusal = new StoreException.WorkflowMissing("porelo-store");
        mvc.perform(get("/projects/alpha/workflow"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:WorkflowMissing"));
    }

    @Test
    void aMissingForgeAccessIsAProblemNamingTheAddressAndTheRight() throws Exception {
        // Harm: the window cannot tell which forge and which right to offer the person a token for.
        given();
        refusal = new StoreException.NoForgeAccess("https://github.com", "Projects");
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoForgeAccess"))
                .andExpect(jsonPath("$.parameters.address").value("https://github.com"))
                .andExpect(jsonPath("$.parameters.right").value("Projects"));
        refusal = new StoreException.NoForgeAccess("https://github.com", null);
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.parameters.address").value("https://github.com"))
                .andExpect(jsonPath("$.parameters.right").doesNotExist());
    }

    @Test
    void aBodyThatIsNoSourceIsAProblemTheWindowCanRead() throws Exception {
        // The window shows the planner's words; an answer of another shape would show none.
        mvc.perform(
                        put("/projects/alpha")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"repository\": [1]"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotACloneUrl"))
                .andExpect(jsonPath("$.parameters.project").value("alpha"));
        mvc.perform(
                        put("/projects/Bad..Name")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"))
                .andExpect(jsonPath("$.parameters.project").doesNotExist());
    }
}
