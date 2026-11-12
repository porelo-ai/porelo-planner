package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The worker call as the window sends it: checked, saved through the store, refused by name. */
class WorkerEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<Writes> saved = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private Board current = board("r1", Worker.AGENT);

    private MockMvc mvc;

    private ChangesRegistry changes;

    /** AB01 with its sub-issue AB01.1, worked as given, and AB02, worked by a person. */
    private static Board board(final String revision, final Worker worker) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Backlog", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "A story",
                                "Backlog",
                                false,
                                "High",
                                List.of("AB01", "AB02"),
                                "",
                                null)),
                List.of(
                        issue("AB01", null, worker),
                        issue("AB01.1", "AB01", worker),
                        issue("AB02", null, Worker.MANUAL)),
                new Work(Map.of(), List.of()));
    }

    private static Issue issue(
            final String id, final @Nullable String parent, final Worker worker) {
        return new Issue(
                id,
                "porelo-store",
                "An issue",
                "Backlog",
                "S",
                List.of(),
                parent,
                null,
                "",
                worker,
                Risk.ORDINARY);
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
                    public Board save(final Revision revision, final Writes writes) {
                        if (failure != null) {
                            throw failure;
                        }
                        saved.add(writes);
                        current =
                                WorkerEndpointTest.board(
                                        "r2", writes.workers().getOrDefault("AB01", Worker.AGENT));
                        return current;
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

    @Test
    void aWorkerIsSavedAnsweredWithTheNewRevisionAndToldToEveryWindow() throws Exception {
        // A worker the windows are not told of leaves every other window offering an agent's work.
        final Changes.Subscription window = changes.subscribe("alpha");

        worker("AB01", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(saved).as("one save").hasSize(1);
        assertThat(saved.get(0).workers())
                .as("the save sets the worker of the issue named")
                .isEqualTo(Map.of("AB01", Worker.MANUAL));
        assertThat(saved.get(0).issues()).as("the save moves no issue").isEmpty();
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void theWorkerAnIssueHasAlreadySavesNothing() throws Exception {
        // A save of nothing would still commit and push an empty change to the store.
        worker("AB02", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aWorkerAgainstAnotherRevisionIsRefusedAsStaleAndSavesNothing() throws Exception {
        // A save over a board the window no longer shows would undo someone else's change.
        worker("AB01", "{\"worker\": \"MANUAL\", \"revision\": \"r0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"))
                .andExpect(jsonPath("$.parameters.revision").value("r1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void anIssueTheBoardLacksIsNotFound() throws Exception {
        // A worker saved for no issue would write a head the board does not hold.
        worker("AB09", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchIssue"))
                .andExpect(jsonPath("$.parameters.issue").value("AB09"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aSubIssueIsRefusedAsNotAllowedNamingIt() throws Exception {
        // A sub-issue is worked as its parent is; a worker of its own would split one issue's work.
        worker("AB01.1", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("AB01.1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aBodyThatIsNoWorkerChangeIsRefusedWithoutRepeatingIt() throws Exception {
        // A worker read loosely would start work a person keeps; a refusal repeating the body
        // would show whatever a caller put there, a secret too.
        for (final String body :
                List.of(
                        "{\"worker\": \"manual\", \"revision\": \"r1\"}",
                        "{\"worker\": \"token123secret\", \"revision\": \"r1\"}",
                        "{\"worker\": 1, \"revision\": \"r1\"}",
                        "{\"worker\": \"MANUAL\"}",
                        "{\"revision\": \"r1\"}",
                        "{\"worker\": \"MANUAL\", \"revision\": \"r1\", \"token123secret\": 1}",
                        "{\"revision\": token123secret}",
                        "[]")) {
            final String answer =
                    worker("AB01", body)
                            .andExpect(status().isUnprocessableContent())
                            .andExpect(
                                    jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                            .andExpect(jsonPath("$.parameters.item").value("AB01"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(answer)
                    .as("the refusal of %s does not repeat it", body)
                    .doesNotContain("token123secret");
        }
        worker("ab01", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aPersonsIssueInAWorkingStateIsRefusedAnAgentAndSavesNothing() throws Exception {
        // An agent's issue in a working state no task entered would wait there forever.
        final WorkRule implement =
                new WorkRule(
                        List.of("Backlog"),
                        "Finished",
                        null,
                        null,
                        WorkRule.Per.REPOSITORY,
                        List.of("AGENT"),
                        "Implement {issues}",
                        Map.of("codex", "model"));
        current =
                new Board(
                        new Revision("r1"),
                        new Workflow(
                                List.of("Backlog", "Implementing", "Finished"),
                                List.of(),
                                List.of("Finished")),
                        current.stories(),
                        List.of(
                                issue("AB01", null, Worker.MANUAL),
                                new Issue(
                                        "AB01.1",
                                        "porelo-store",
                                        "An issue",
                                        "Implementing",
                                        "S",
                                        List.of(),
                                        "AB01",
                                        null,
                                        "",
                                        Worker.MANUAL,
                                        Risk.ORDINARY),
                                new Issue(
                                        "AB02",
                                        "porelo-store",
                                        "An issue",
                                        "Implementing",
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "",
                                        Worker.MANUAL,
                                        Risk.ORDINARY)),
                        new Work(Map.of("Implementing", implement), List.of()));

        for (final String issue : List.of("AB02", "AB01")) {
            final var response =
                    worker(issue, "{\"worker\": \"AGENT\", \"revision\": \"r1\"}")
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus())
                    .as("the switch of %s: %s", issue, response.getContentAsString())
                    .isEqualTo(422);
            final var refused =
                    tools.jackson.databind.json.JsonMapper.builder()
                            .build()
                            .readTree(response.getContentAsString());
            assertThat(refused.path("type").asString())
                    .as("the switch of %s is refused as a move is", issue)
                    .isEqualTo("urn:porelo-planner:MoveNotAllowed");
            assertThat(refused.at("/parameters/item").asString())
                    .as("the refusal names %s", issue)
                    .isEqualTo(issue);
        }
        assertThat(saved).as("no refused switch is saved").isEmpty();
    }

    @Test
    void anIssueTheStoreCannotSaveIsRefusedAsNotAllowedNamingIt() throws Exception {
        // The window must learn why the worker stayed unsaved, and which issue holds it.
        failure = new StoreException.NotSavable("AB01", "the issue's head cannot be read");
        worker("AB01", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("AB01"))
                .andExpect(
                        jsonPath("$.parameters.reason").value("the issue's head cannot be read"));
    }

    @Test
    void aStoreThatSavesNoWorkerIsAnsweredAsNotOffered() throws Exception {
        // A bare server error would leave the window unable to tell a missing feature from a fault.
        failure = new UnsupportedOperationException("This store does not save");
        worker("AB01", "{\"worker\": \"MANUAL\", \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
    }

    @Test
    void everyIssueOfTheBoardAnswersItsWorker() throws Exception {
        // An issue answered without its worker shows a person's issue as one an agent may start.
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].issues[0].worker").value("AGENT"))
                .andExpect(jsonPath("$.stories[0].issues[1].worker").value("MANUAL"));
        mvc.perform(get("/projects/alpha/stories/ST1/issues"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[1].worker").value("MANUAL"));
        mvc.perform(get("/projects/alpha/issues/AB02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issue.worker").value("MANUAL"));
    }

    private ResultActions worker(final String issue, final String body) throws Exception {
        return mvc.perform(
                put("/projects/alpha/issues/" + issue + "/worker")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
