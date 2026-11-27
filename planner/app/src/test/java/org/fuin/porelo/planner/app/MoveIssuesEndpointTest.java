package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Many issues moved to their states in one call, as an agent posts it. */
class MoveIssuesEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<Writes> saved = new ArrayList<>();

    private int saveCalls;

    private int changedSaves;

    private int edits;

    private boolean keeps;

    private Map<String, String> states = initialStates();

    private Work work = work(List.of());

    private Board current = board("r1");

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Map<String, String> initialStates() {
        final Map<String, String> states = new LinkedHashMap<>();
        states.put("AB1", "Backlog");
        states.put("AB2", "Review");
        states.put("AB3", "Review");
        states.put("AB4", "Backlog");
        states.put("AB5", "Idea");
        return states;
    }

    private static Work work(final List<String> faults) {
        return new Work(
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
                                Map.of("claude", "claude-opus-5-5"),
                                Roles.forRule(null, null))),
                faults);
    }

    private Board board(final String revision) {
        final List<Issue> issues = new ArrayList<>();
        states.forEach(
                (id, state) ->
                        issues.add(
                                new Issue(
                                        id,
                                        "porelo-store",
                                        "An issue" + (id.equals("AB1") ? edits : ""),
                                        state,
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "")));
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Idea", "Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(),
                issues,
                work);
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
                        saveCalls++;
                        if (changedSaves > 0) {
                            // Another change reached the issue between the judging and the save.
                            changedSaves--;
                            edits++;
                            current = MoveIssuesEndpointTest.this.board("r1");
                            throw new StoreException.Changed(List.of("AB1"));
                        }
                        saved.add(writes);
                        states.putAll(writes.issues());
                        current = MoveIssuesEndpointTest.this.board("r2");
                        return current;
                    }

                    @Override
                    public boolean keepsReasons() {
                        return keeps;
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(new BoardController(projects, changes))
                        .setControllerAdvice(new Problems(projects))
                        .build();
        mvc.perform(put("/projects/alpha").contentType(MediaType.APPLICATION_JSON).content(SOURCE))
                .andExpect(status().isNoContent());
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private ResultActions moveIssues(final String body) throws Exception {
        return mvc.perform(
                post("/projects/alpha/states")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void fiveMovesOfWhichTwoBreakARuleSaveThreeTogetherAndNameTheTwoWithTheirReasons()
            throws Exception {
        // Harm: one broken move holding back the others, or dropped without its reason.
        keeps = true;

        moveIssues(
                        "{\"moves\": ["
                                + "{\"issue\": \"AB1\", \"to\": \"Review\"},"
                                + "{\"issue\": \"AB2\", \"to\": \"Finished\"},"
                                + "{\"issue\": \"AB3\", \"to\": \"Finished\","
                                + " \"reason\": \"Done.\"},"
                                + "{\"issue\": \"AB4\", \"to\": \"Nowhere\"},"
                                + "{\"issue\": \"AB5\", \"to\": \"Backlog\", \"reason\": \"Why.\"}"
                                + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"))
                .andExpect(jsonPath("$.results.length()").value(5))
                .andExpect(jsonPath("$.results[0].item").value("AB1"))
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[0].state").value("Review"))
                .andExpect(jsonPath("$.results[1].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[2].outcome").value("SAVED"))
                .andExpect(jsonPath("$.results[3].item").value("AB4"))
                .andExpect(jsonPath("$.results[3].outcome").value("REFUSED"))
                .andExpect(
                        jsonPath("$.results[3].refusal.type")
                                .value("urn:porelo-planner:StateNotAllowed"))
                .andExpect(jsonPath("$.results[3].refusal.status").value(422))
                .andExpect(jsonPath("$.results[3].refusal.parameters.item").value("AB4"))
                .andExpect(jsonPath("$.results[4].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[4].refusal.title").value("MoveNotAllowed"))
                .andExpect(
                        jsonPath("$.results[4].refusal.detail")
                                .value("A reason is taken only with a move into a closed state."));

        final Map<String, String> written = new LinkedHashMap<>();
        final Map<String, Writes.Reason> reasons = new LinkedHashMap<>();
        saved.forEach(
                writes -> {
                    written.putAll(writes.issues());
                    reasons.putAll(writes.issueReasons());
                });
        assertThat(written)
                .as("the three moves the rules allow, and no other")
                .containsOnly(
                        Map.entry("AB1", "Review"),
                        Map.entry("AB2", "Finished"),
                        Map.entry("AB3", "Finished"));
        assertThat(reasons)
                .as("the reason of the closing move is kept with it")
                .containsOnlyKeys("AB3");
    }

    @Test
    void anIssueAlreadyInItsStateIsUnchangedAndNothingIsSaved() throws Exception {
        // Harm: a commit for a move that changes nothing.
        moveIssues("{\"moves\": [{\"issue\": \"AB1\", \"to\": \"Backlog\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"))
                .andExpect(jsonPath("$.results[0].outcome").value("UNCHANGED"))
                .andExpect(jsonPath("$.results[0].state").value("Backlog"))
                .andExpect(jsonPath("$.results[0].refusal").doesNotExist());

        assertThat(saveCalls).as("the store is not asked").isZero();
    }

    @Test
    void aBoardChangedBetweenThePreviewAndTheSaveIsPreviewedAgainOnceAndSaved() throws Exception {
        // Harm: a move saved over an issue as it no longer stands, or refused for a change a
        // second look finds harmless.
        changedSaves = 1;

        moveIssues("{\"moves\": [{\"issue\": \"AB1\", \"to\": \"Review\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("SAVED"));

        assertThat(saveCalls).as("one save refused as changed, one that stands").isEqualTo(2);
        assertThat(saved).as("the move saved once").hasSize(1);
    }

    @Test
    void aBoardChangedAgainMovesNothingAndAnswersItemChanged() throws Exception {
        // Harm: a call that keeps trying against a board that keeps moving.
        changedSaves = 2;

        moveIssues("{\"moves\": [{\"issue\": \"AB1\", \"to\": \"Review\"}]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:ItemChanged"))
                .andExpect(jsonPath("$.parameters.changed[0]").value("AB1"));

        assertThat(saveCalls).as("the save tried twice and no more").isEqualTo(2);
        assertThat(saved).as("nothing saved").isEmpty();
    }

    @Test
    void aBatchTheRulesRefuseWholeSavesNothingAndAnswersEveryIssueRefused() throws Exception {
        // Harm: moves saved on a board whose work rules cannot be read.
        work = work(List.of("stories/work.yml: not YAML"));
        current = board("r1");

        moveIssues(
                        "{\"moves\": [{\"issue\": \"AB1\", \"to\": \"Review\"},"
                                + " {\"issue\": \"AB2\", \"to\": \"Finished\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"))
                .andExpect(jsonPath("$.results[1].refusal.title").value("MoveNotAllowed"));

        assertThat(saveCalls).as("the store is not asked").isZero();
    }

    @Test
    void aBodyThatIsNoListOfMovesIsRefusedBeforeAnythingIsJudged() throws Exception {
        // Harm: a member the planner does not take, or too many moves, read as a smaller batch.
        final String move = "{\"issue\": \"AB1\", \"to\": \"Review\"}";
        moveIssues("{\"moves\": [" + move + "], \"other\": 1}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        moveIssues("{\"moves\": [{\"issue\": \"AB1\", \"to\": \"Review\", \"other\": 1}]}")
                .andExpect(status().isUnprocessableContent());
        moveIssues("{\"moves\": []}").andExpect(status().isUnprocessableContent());
        moveIssues("{\"moves\": [" + (move + ",").repeat(200) + move + "]}")
                .andExpect(status().isUnprocessableContent());
        moveIssues("{\"moves\": [{\"issue\": \"AB1\", \"to\": \"/home/x:pw\"}]}")
                .andExpect(status().isUnprocessableContent());
        moveIssues("{\"moves\": [{\"issue\": \"../x\", \"to\": \"Review\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"));

        assertThat(saveCalls).as("the store is not asked").isZero();
    }
}
