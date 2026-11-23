package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The new issue call as the window posts it: checked, saved through the store, refused by name. */
class IssuesEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<NewIssue> added = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private Board current = board("r1");

    private boolean createsInState;

    private final List<Writes> moves = new ArrayList<>();

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Idea", "Working", "Backlog", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(),
                List.of(),
                new Work(
                        Map.of(
                                "Working",
                                new WorkRule(
                                        List.of("Idea"),
                                        "Backlog",
                                        null,
                                        null,
                                        WorkRule.Per.REPOSITORY,
                                        List.of("build"),
                                        "Build",
                                        Map.of("agent", "model"))),
                        List.of()),
                List.of("porelo-store", "porelo-other"));
    }

    @BeforeEach
    void serve() throws Exception {
        final PlanningStore store =
                new PlanningStore() {
                    @Override
                    public Board board() {
                        return current;
                    }

                    @Override
                    public Board refresh() {
                        return current;
                    }

                    @Override
                    public boolean createsInState() {
                        return createsInState;
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        moves.add(writes);
                        current = withIssue("r3", writes.issues().get("CD03"), Worker.AGENT);
                        return current;
                    }

                    @Override
                    public NewIssue.Added createIssue(
                            final Revision revision, final NewIssue issue) {
                        if (failure != null) {
                            throw failure;
                        }
                        added.add(issue);
                        current =
                                withIssue(
                                        "r2",
                                        createsInState && issue.state() != null
                                                ? issue.state()
                                                : "Idea",
                                        issue.worker());
                        return new NewIssue.Added("CD03", current);
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems())
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private static Board withIssue(final String revision, final String state, final Worker worker) {
        final Board empty = board(revision);
        return new Board(
                empty.revision(),
                empty.workflow(),
                List.of(),
                List.of(
                        new Issue(
                                "CD03",
                                "porelo-other",
                                "T",
                                state,
                                null,
                                List.of(),
                                null,
                                null,
                                "W",
                                worker,
                                Risk.ORDINARY)),
                empty.work(),
                empty.repositories());
    }

    @Test
    void aNewIssueBodyAtOneMiBIsSavedAndOneByteMoreIsRefused() throws Exception {
        // Harm: a task's new issue is refused although a forge accepts its size.
        final String body =
                "{\"repository\":\"porelo-other\",\"title\":\""
                        + "T".repeat(256)
                        + "\",\"mustBeTrue\":\""
                        + "é".repeat(65536)
                        + "\",\"revision\":\"r1\"}";
        final String bounded =
                body + " ".repeat(1048576 - body.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(
                        post("/projects/alpha/issues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bounded + " "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IssueNotAllowed"));
        assertThat(added).as("the oversized request saves nothing").isEmpty();
        mvc.perform(
                        post("/projects/alpha/issues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bounded))
                .andExpect(status().isCreated());
        assertThat(added).as("one new issue").hasSize(1);
        assertThat(added.getFirst().title()).as("the full title").isEqualTo("T".repeat(256));
        assertThat(added.getFirst().mustBeTrue())
                .as("the full multibyte text")
                .isEqualTo("é".repeat(65536));
    }

    @Test
    void aStoreThatCreatesInAStatePlacesTheIssueInOneSave() throws Exception {
        // Harm: two revisions and an issue left half placed when a second save fails.
        createsInState = true;

        issueInState("Backlog")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(added)
                .as("the state handed to the single create")
                .extracting(NewIssue::state)
                .containsExactly("Backlog");
        assertThat(moves).as("no second save after creation").isEmpty();
        assertThat(current.issue("CD03").orElseThrow().state())
                .as("the saved placement")
                .isEqualTo("Backlog");
    }

    @Test
    void aStoreWithoutCreationInAStateStillCreatesThenMoves() throws Exception {
        // Harm: a store without the capability silently leaves the issue in its first state.
        issueInState("Backlog")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value("r3"));

        assertThat(added)
                .as("one create in the default state")
                .extracting(NewIssue::state)
                .containsExactly((String) null);
        assertThat(moves).as("the placement saved after creation").hasSize(1);
        assertThat(current.issue("CD03").orElseThrow().state())
                .as("the moved issue")
                .isEqualTo("Backlog");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Finished", "token123secret"})
    void aForbiddenInitialStateIsRefusedBeforeAnythingIsCreated(final String state)
            throws Exception {
        // Harm: a refused placement leaves a newly made issue in the wrong state or leaks input.
        createsInState = true;
        final String answer =
                issueInState(state)
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value("urn:porelo-planner:StateNotAllowed"))
                        .andExpect(jsonPath("$.parameters.item").value(""))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(answer)
                .as("no synthetic id or unconfigured caller text in the refusal")
                .doesNotContain("porelo-other#1", "token123secret");
        assertThat(added).as("no issue created for any refused placement").isEmpty();
        assertThat(moves).as("no placement saved for any refused issue").isEmpty();
    }

    @Test
    void aStateTheWorkflowLacksIsRefusedBeforeCreationOnAStoreWithoutCreationInAState()
            throws Exception {
        // Harm: an issue made and left half placed, and the caller's text repeated in the answer.
        createsInState = false;
        final String answer =
                issueInState("token123secret")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value("urn:porelo-planner:StateNotAllowed"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(answer).as("the caller's state in the refusal").doesNotContain("token123secret");
        assertThat(added).as("no issue created for a state the workflow lacks").isEmpty();
    }

    @Test
    void anExplicitClosedFirstStateIsRefusedBeforeCreation() throws Exception {
        // Harm: a closed first state bypasses the move refusal and produces the wrong contract
        // answer.
        createsInState = true;
        final Board empty = board("r1");
        current =
                new Board(
                        empty.revision(),
                        new Workflow(List.of("Finished", "Idea"), List.of(), List.of("Finished")),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        empty.repositories());

        issueInState("Finished")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StateNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value(""));

        assertThat(added).as("no closed issue created").isEmpty();
        assertThat(moves).as("no move saved").isEmpty();
    }

    @Test
    void aPersonMayCreateAnIssueInAWorkingStateInOneSave() throws Exception {
        // Harm: initial placement takes away a person's right to work an issue by hand.
        createsInState = true;

        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                                + " \"revision\": \"r1\", \"state\": \"Working\", \"worker\": \"MANUAL\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(current.issue("CD03").orElseThrow().state())
                .as("the person's saved placement")
                .isEqualTo("Working");
        assertThat(moves).as("no move after creation").isEmpty();
    }

    @Test
    void anAgentsIssueNoRunWorksMayBeCreatedInAWorkingStateInOneSave() throws Exception {
        // Harm: work begun outside this planner cannot be recorded where it stands.
        createsInState = true;

        issueInState("Working")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(current.issue("CD03").orElseThrow().state())
                .as("the agent's issue saved in the working state")
                .isEqualTo("Working");
        assertThat(moves).as("no move after creation").isEmpty();
    }

    @Test
    void anIssueOutsideTheProjectNeverStandsInForTheIssueJustAdded() throws Exception {
        // Harm: an add into a later state fails with a server error once the repository holds a
        // hand-made issue, as the check names the issue it judges by that issue's reference.
        createsInState = true;
        final Board empty = board("r1");
        current =
                new Board(
                        empty.revision(),
                        empty.workflow(),
                        List.of(),
                        List.of(),
                        empty.work(),
                        empty.repositories(),
                        List.of(),
                        null,
                        List.of(
                                new org.fuin.porelo.planner.store.UnlistedIssue(
                                        "porelo-other#1",
                                        "porelo-other",
                                        "By hand",
                                        org.fuin.porelo.planner.store.UnlistedIssue.Kind.ISSUE,
                                        java.net.URI.create(
                                                "https://github.com/a/porelo-other/issues/1"))));

        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                                + " \"revision\": \"r1\", \"state\": \"Working\", \"worker\": \"MANUAL\"}")
                .andExpect(status().isCreated());

        assertThat(added).as("the issue added").hasSize(1);
    }

    @Test
    void aDefaultInitialStateNeedsNoMoveCheck() throws Exception {
        // Harm: a new check blocks ordinary creation because unrelated work rules have faults.
        createsInState = true;
        final Board empty = board("r1");
        current =
                new Board(
                        empty.revision(),
                        empty.workflow(),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of("An unrelated work rule is unreadable")),
                        empty.repositories());

        issueInState("Idea").andExpect(status().isCreated());
        current =
                new Board(
                        empty.revision(),
                        empty.workflow(),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of("An unrelated work rule is unreadable")),
                        empty.repositories());
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                                + " \"revision\": \"r1\"}")
                .andExpect(status().isCreated());

        assertThat(added).as("both explicit and omitted first-state creates succeed").hasSize(2);
        assertThat(moves).as("neither create saves a move").isEmpty();
    }

    @Test
    void aConfirmMemberOfTheWrongTypeIsRefusedBeforeCreation() throws Exception {
        // Harm: malformed confirmation is accepted as consent or silently changes the request.
        for (final String confirm : List.of("null", "1", "\"true\"", "{}", "[]")) {
            refused(
                    "{\"repository\": \"porelo-other\", \"title\": \"T\","
                            + " \"mustBeTrue\": \"W\", \"revision\": \"r1\", \"confirm\": "
                            + confirm
                            + "}",
                    "confirm");
        }
        assertThat(added).as("no malformed confirmation creates an issue").isEmpty();
    }

    private ResultActions issueInState(final String state) throws Exception {
        return issue(
                "{\"repository\": \"porelo-other\", \"title\": \"T\","
                        + " \"mustBeTrue\": \"W\", \"revision\": \"r1\", \"state\": \""
                        + state
                        + "\"}");
    }

    @Test
    void anIdeaIsAddedAnsweredAsCreatedAndToldToEveryWindow() throws Exception {
        // An idea the windows are not told of leaves every other window without it.
        final Changes.Subscription window = changes.subscribe("alpha");

        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \" An idea \","
                                + " \"mustBeTrue\": \"It holds.\", \"size\": \"S\", \"revision\":"
                                + " \"r1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("CD03"))
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(added)
                .as("one issue handed to the store, its title trimmed")
                .containsExactly(new NewIssue("porelo-other", "An idea", "It holds.", "S"));
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void anIdeaIsWorkedByAnAgentUnlessItNamesAPerson() throws Exception {
        // A new issue given to the wrong worker starts work a person did not ask for, or none.
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r1\"}")
                .andExpect(status().isCreated());
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"worker\": \"MANUAL\", \"revision\": \"r2\"}")
                .andExpect(status().isCreated());
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"worker\": \"AGENT\", \"revision\": \"r2\"}")
                .andExpect(status().isCreated());

        assertThat(added)
                .as("the worker each new issue is handed to the store with")
                .extracting(NewIssue::worker)
                .containsExactly(Worker.AGENT, Worker.MANUAL, Worker.AGENT);
    }

    @Test
    void anIdeaATaskProposedIsHandedToTheStoreNamingThatTask() throws Exception {
        // A task's name lost on the way leaves the person without the task to ask.
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"task\": \"t-7.2\", \"revision\": \"r1\"}")
                .andExpect(status().isCreated());

        assertThat(added)
                .as("the task handed to the store as it was sent")
                .extracting(NewIssue::task)
                .containsExactly("t-7.2");
    }

    @Test
    void anIdeaAgainstAnotherRevisionIsRefusedAsStaleAndAddsNothing() throws Exception {
        // An idea numbered on a board the window no longer shows could take a number taken since.
        issue(
                        "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"))
                .andExpect(jsonPath("$.parameters.revision").value("r1"));
        assertThat(added).as("nothing added").isEmpty();
    }

    @Test
    void aBodyOutOfBoundsIsRefusedNamingItsFieldWithoutRepeatingIt() throws Exception {
        // A refusal repeating the body would show whatever a caller put there, a secret too.
        refused(
                "{\"repository\": \"token123secret\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"revision\": \"r1\"}",
                "repository");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \""
                        + "T".repeat(257)
                        + "\", \"mustBeTrue\": \"W\", \"revision\": \"r1\"}",
                "title");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"size\": 3, \"revision\": \"r1\"}",
                "size");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\"}",
                "revision");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"revision\": \"r1\", \"token123secret\": 1}",
                "");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"worker\": \"manual\", \"revision\": \"r1\"}",
                "worker");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"worker\": \"token123secret\", \"revision\": \"r1\"}",
                "worker");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                        + " \"worker\": 1, \"revision\": \"r1\"}",
                "worker");
        for (final String task :
                List.of(
                        "\"\"",
                        "\" token123secret\"",
                        "\"token123secret\\n## Forged\"",
                        "\"token123secret`\"",
                        "null",
                        "7",
                        "[\"token123secret\"]")) {
            refused(
                    "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \"W\","
                            + " \"task\": "
                            + task
                            + ", \"revision\": \"r1\"}",
                    "task");
        }
        refused("{\"revision\": token123secret}", "");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \""
                        + "W".repeat(1048576)
                        + "\", \"revision\": \"r1\"}",
                "");
        assertThat(added).as("nothing added").isEmpty();
    }

    @Test
    void anIssueTheStoreCannotWriteIsRefusedWithTheStoresWords() throws Exception {
        // The window must learn why the idea stayed unsaved.
        failure =
                new StoreException.NotSavable(
                        "", "the project's own repository uses no issue prefix");
        issue(
                        "{\"repository\": \"porelo-store\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:IssueNotAllowed"))
                .andExpect(jsonPath("$.parameters.field").value(""))
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value("the project's own repository uses no issue prefix"));
    }

    @Test
    void aTextTheStoreCannotKeepIsRefusedNamingItsField() throws Exception {
        // Harm: a text too long for the forge taken by the window as a passing fault and handed in
        // again at every start.
        failure =
                new StoreException.NotSavable(
                        "", "what must be true is longer than GitHub takes", "mustBeTrue");
        issue(
                        "{\"repository\": \"porelo-store\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:IssueNotAllowed"))
                .andExpect(jsonPath("$.parameters.field").value("mustBeTrue"));
    }

    @Test
    void aStoreThatAddsNoIssuesIsAnsweredAsNotOffered() throws Exception {
        // A bare server error would leave the window unable to tell a missing feature from a fault.
        failure = new UnsupportedOperationException("This store does not add issues");
        issue(
                        "{\"repository\": \"porelo-store\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
    }

    @Test
    void anIssueUnderAStoryHeldBackOrNamedTwiceIsRefusedWithItsWords() throws Exception {
        // An issue added under a story the store could not fully read would be worked from it.
        final org.fuin.porelo.planner.store.Story story =
                new org.fuin.porelo.planner.store.Story(
                        "ST1", "S", "Idea", "Medium", null, List.of(), "", null);
        final String body =
                "{\"repository\": \"porelo-store\", \"title\": \"T\", \"mustBeTrue\":"
                        + " \"W\", \"story\": \"ST1\", \"revision\": \"r1\"}";
        current =
                withStories(
                        List.of(
                                story.withWarnings(
                                        List.of(
                                                org.fuin.porelo.planner.store.Warning
                                                        .wrongParent()))));
        issue(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:IssueNotAllowed"))
                .andExpect(jsonPath("$.parameters.field").value("story"))
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value(
                                        "ST1: "
                                                + org.fuin.porelo.planner.store.Warning
                                                        .wrongParent()
                                                        .text()));
        final org.fuin.porelo.planner.store.Story copy =
                story.withWarnings(List.of(org.fuin.porelo.planner.store.Warning.idTwice()));
        current = withStories(List.of(copy, copy));
        issue(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.parameters.field").value("story"))
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value(
                                        "ST1 names two stories; it can be neither worked nor moved"
                                                + " until one id is changed."));
        assertThat(added).as("nothing created").isEmpty();
    }

    private Board withStories(final List<org.fuin.porelo.planner.store.Story> stories) {
        return new Board(
                current.revision(),
                current.workflow(),
                stories,
                current.issues(),
                current.work(),
                current.repositories());
    }

    private void refused(final String body, final String field) throws Exception {
        final String answer =
                issue(body)
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value("urn:porelo-planner:IssueNotAllowed"))
                        .andExpect(jsonPath("$.parameters.field").value(field))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(answer).as("the body is not repeated").doesNotContain("token123secret");
    }

    private ResultActions issue(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/issues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
