package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
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

/** The moves call as the window posts it: checked, saved through the store, refused by name. */
class MovesEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<Writes> saved = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private boolean derives = true;

    private Board current = board("r1", "Backlog");

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision, final String state) {
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(
                        new Story(
                                "ST1", "A story", state, false, "High", List.of("AB01"), "", null)),
                List.of(
                        new Issue(
                                "AB01",
                                "porelo-store",
                                "An issue",
                                state,
                                "S",
                                List.of(),
                                null,
                                null,
                                "")),
                new Work(
                        Map.of(
                                "Building",
                                new WorkRule(
                                        List.of("Backlog"),
                                        "Review",
                                        null,
                                        null,
                                        WorkRule.Per.STORY,
                                        List.of("AGENT"),
                                        "Build it.",
                                        Map.of("claude", "claude-opus-5-5"))),
                        List.of()));
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
                        if (failure instanceof StoreException.PartlySaved) {
                            // The repositories pushed first now hold the save.
                            current = MovesEndpointTest.board("r2", "Backlog");
                        }
                        if (failure != null) {
                            throw failure;
                        }
                        saved.add(writes);
                        current =
                                MovesEndpointTest.board(
                                        "r2", writes.issues().getOrDefault("AB01", "Backlog"));
                        return current;
                    }

                    @Override
                    public @Nullable String storyState(final List<String> states) {
                        if (!derives) {
                            throw new UnsupportedOperationException();
                        }
                        return states.isEmpty() ? null : states.get(0);
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
    void aBatchIsSavedAndEveryFollowingWindowIsToldTheNewBoard() throws Exception {
        // A save the windows are not told of leaves every other window showing the old board.
        final Changes.Subscription window = changes.subscribe("alpha");

        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"))
                .andExpect(jsonPath("$.moved[0]").value("AB01"));

        assertThat(saved).as("one save with the issue's new state").hasSize(1);
        assertThat(saved.get(0).issues()).containsExactly(Map.entry("AB01", "Review"));
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void aBatchAgainstAnotherRevisionIsRefusedAsStaleAndSavesNothing() throws Exception {
        // A save over a board the window no longer shows would undo someone else's move.
        moves("{\"revision\": \"r0\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"))
                .andExpect(jsonPath("$.parameters.revision").value("r1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aMoveIntoAWorkingStateIsRefusedByNameWithItsParameters() throws Exception {
        // The window offers the work only when it reads this refusal by its name.
        moves("{\"revision\": \"r1\", \"moves\": [{\"story\": \"ST1\", \"to\": \"Building\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:EnteredOnlyByWork"))
                .andExpect(jsonPath("$.parameters.item").value("ST1"))
                .andExpect(jsonPath("$.parameters.to").value("Building"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aMoveNamingBothOrNeitherItemIsRefusedBeforeTheStoreIsAsked() throws Exception {
        // A move naming both would save whichever the planner happened to read.
        moves(
                        "{\"revision\": \"r1\", \"moves\": [{\"story\": \"ST1\", \"issue\": \"AB01\","
                                + " \"to\": \"Review\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        moves("{\"revision\": \"r1\", \"moves\": [{\"to\": \"Review\"}]}")
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        moves("{\"revision\": \"r1\"}")
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aBodyOrStateThatIsNoNameIsRefusedWithoutRepeatingIt() throws Exception {
        // A refusal repeating the body would show whatever a caller put there, a secret too.
        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"/home/x:pw\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.to").value(""));
        final String body =
                moves("{\"revision\": token123secret}")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(body).as("the parser's words are not repeated").doesNotContain("token123secret");
        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"../x\", \"to\": \"Review\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
    }

    @Test
    void anIssueTheStoreCannotSaveIsRefusedAsNotAllowedNamingIt() throws Exception {
        // The window must learn why a move stayed unsaved, and which issue holds it.
        failure =
                new StoreException.NotSavable(
                        "AB01",
                        "stats/AB01.jsonl cannot take the move: invalid JSON in a stage record");
        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("AB01"))
                .andExpect(jsonPath("$.parameters.from").value("Backlog"))
                .andExpect(jsonPath("$.parameters.to").value("Review"))
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value(
                                        "stats/AB01.jsonl cannot take the move: invalid JSON in"
                                                + " a stage record"));
    }

    @Test
    void aStoryMoveWhereTheStoreDerivesNoStateIsAnsweredAsNotOffered() throws Exception {
        // A bare server error would leave the window unable to tell a missing feature from a fault.
        derives = false;
        moves("{\"revision\": \"r1\", \"moves\": [{\"story\": \"ST1\", \"to\": \"Review\"}]}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aPartlySavedBatchIsToldToEveryWindow() throws Exception {
        // The windows not told would show a board some repositories no longer hold.
        final Changes.Subscription window = changes.subscribe("alpha");
        failure =
                new StoreException.PartlySaved("porelo-store", List.of("porelo-other"), "refused");
        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isBadGateway());
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the board the pushed repositories hold")
                .contains("r2");
    }

    @Test
    void aPartlySavedBatchNamesTheRepositoriesPushedAndTheOneThatFailed() throws Exception {
        // A half-made move told as a plain failure would hide what the remotes now hold.
        failure =
                new StoreException.PartlySaved(
                        "porelo-store", List.of("porelo-other", "porelo-third"), "refused");
        moves("{\"revision\": \"r1\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:PartlySaved"))
                .andExpect(jsonPath("$.parameters.pushed").value("porelo-other,porelo-third"))
                .andExpect(jsonPath("$.parameters.failed").value("porelo-store"));
        failure = new StoreException.Busy("porelo-store");
        moves("{\"revision\": \"r2\", \"moves\": [{\"issue\": \"AB01\", \"to\": \"Review\"}]}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:Busy"));
    }

    private ResultActions moves(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/moves")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
