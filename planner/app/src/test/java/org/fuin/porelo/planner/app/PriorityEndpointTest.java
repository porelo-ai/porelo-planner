package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
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

/** The priority call as the window sends it: checked, saved through the store, refused by name. */
class PriorityEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<Writes> saved = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private Board current = board("r1", "High");

    private MockMvc mvc;

    private ChangesRegistry changes;

    /** ST1 at the given priority, with one issue. */
    private static Board board(final String revision, final String priority) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Backlog", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new Story(
                                "ST1",
                                "A story",
                                "Backlog",
                                false,
                                priority,
                                null,
                                List.of("AB01"),
                                "",
                                null)),
                List.of(
                        new Issue(
                                "AB01",
                                "porelo-store",
                                "An issue",
                                "Backlog",
                                "S",
                                List.of(),
                                null,
                                null,
                                "",
                                Worker.AGENT,
                                Risk.ORDINARY)),
                new Work(Map.of(), List.of()));
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
                                PriorityEndpointTest.board(
                                        "r2", writes.priorities().getOrDefault("ST1", "High"));
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
    void aPriorityIsSavedAnsweredWithTheNewRevisionAndToldToEveryWindow() throws Exception {
        // A priority the windows are not told of leaves every other window sorting the old way.
        final Changes.Subscription window = changes.subscribe("alpha");

        priority("ST1", "{\"priority\": \"Lowest\", \"revision\": \"r1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(saved).as("one save").hasSize(1);
        assertThat(saved.get(0).priorities())
                .as("the save sets the priority of the story named")
                .isEqualTo(Map.of("ST1", "Lowest"));
        assertThat(saved.get(0).stories()).as("the save moves no story").isEmpty();
        assertThat(saved.get(0).issues()).as("the save moves no issue").isEmpty();
        assertThat(window.next(Duration.ofSeconds(2)).map(board -> board.revision().value()))
                .as("the window is told the saved board")
                .contains("r2");
    }

    @Test
    void thePriorityAStoryHasAlreadySavesNothingAndAnswersTheCurrentRevision() throws Exception {
        // A save of nothing would still commit and push an empty change to the store.
        priority("ST1", "{\"priority\": \"High\", \"revision\": \"r1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aPriorityAgainstAnotherRevisionIsRefusedAsStaleAndSavesNothing() throws Exception {
        // A save over a board the window no longer shows would undo someone else's change.
        priority("ST1", "{\"priority\": \"Low\", \"revision\": \"r0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"))
                .andExpect(jsonPath("$.parameters.revision").value("r1"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aStoryTheBoardLacksIsNotFound() throws Exception {
        // A priority saved for no story would write a head the board does not hold.
        priority("ST9", "{\"priority\": \"Low\", \"revision\": \"r1\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"))
                .andExpect(jsonPath("$.parameters.story").value("ST9"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aBodyThatIsNoPriorityChangeIsRefusedAsABadRequestWithoutRepeatingIt() throws Exception {
        // A sixth value would write a head the stories index refuses, a server error would hide
        // why, and a refusal repeating the body would show whatever a caller put there.
        for (final String body :
                List.of(
                        "{\"priority\": \"Urgent\", \"revision\": \"r1\"}",
                        "{\"priority\": \"high\", \"revision\": \"r1\"}",
                        "{\"priority\": \"token123secret\", \"revision\": \"r1\"}",
                        "{\"priority\": 1, \"revision\": \"r1\"}",
                        "{\"priority\": \"Low\"}",
                        "{\"revision\": \"r1\"}",
                        "{\"priority\": \"Low\", \"revision\": 1}",
                        "{\"priority\": \"Low\", \"revision\": \"r1\", \"token123secret\": 1}",
                        "{\"revision\": token123secret}",
                        "[]")) {
            final String answer =
                    priority("ST1", body)
                            .andExpect(status().isBadRequest())
                            .andExpect(
                                    jsonPath("$.type")
                                            .value("urn:porelo-planner:PriorityNotAllowed"))
                            .andExpect(jsonPath("$.parameters.story").value("ST1"))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(answer)
                    .as("the refusal of %s does not repeat it", body)
                    .doesNotContain("token123secret");
        }
        priority("st1", "{\"priority\": \"Low\", \"revision\": \"r1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aStoryTheStoreCannotSaveIsRefusedAsNotAllowedNamingIt() throws Exception {
        // The window must learn why the priority stayed unsaved, and which story holds it.
        failure = new StoreException.NotSavable("ST1", "its head holds 2 priority members");
        priority("ST1", "{\"priority\": \"Low\", \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("ST1"));
    }

    @Test
    void aStoreThatSavesNoPriorityIsAnsweredAsNotOffered() throws Exception {
        // A bare server error would leave the window unable to tell a missing feature from a fault.
        failure = new UnsupportedOperationException("This store does not save");
        priority("ST1", "{\"priority\": \"Low\", \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
    }

    private ResultActions priority(final String story, final String body) throws Exception {
        return mvc.perform(
                put("/projects/alpha/stories/" + story + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }
}
