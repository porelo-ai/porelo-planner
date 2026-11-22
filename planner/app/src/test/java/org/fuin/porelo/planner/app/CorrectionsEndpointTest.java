package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
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

/** A warning's correction as the window reads, previews and confirms it. */
class CorrectionsEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final Correction STATED =
            new Correction(Correction.STATE_STATED, List.of("Backlog", "Review"));

    private final List<Writes> saved = new ArrayList<>();

    private Board current = board("r1", "Parked", Warning.stateOutsideWorkflow().offering(STATED));

    private MockMvc mvc;

    private ChangesRegistry changes;

    /** ST1 in the state given with its warnings, holding AB01 in Backlog; ST2 names one outside. */
    private static Board board(final String revision, final String state, final Warning... w) {
        return new Board(
                new Revision(revision),
                new Workflow(
                        List.of("Backlog", "Building", "Review", "Finished"),
                        List.of(),
                        List.of("Finished")),
                List.of(
                        new Story(
                                        "ST1",
                                        "A story",
                                        state,
                                        true,
                                        "High",
                                        null,
                                        List.of("AB01"),
                                        "",
                                        null)
                                .withWarnings(List.of(w)),
                        new Story(
                                        "ST2", "Another", "Backlog", true, "High", null, List.of(),
                                        "", null)
                                .withWarnings(List.of(Warning.issueOutsideProject()))),
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
                        saved.add(writes);
                        current =
                                CorrectionsEndpointTest.board(
                                        "r2", writes.stories().getOrDefault("ST1", "Parked"));
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

    private ResultActions post(final String path, final String body) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/projects/alpha/" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String asked(final String revision, final String state) {
        return "{\"revision\": \""
                + revision
                + "\", \"story\": \"ST1\", \"warning\": \"StateOutsideWorkflow\", \"state\": \""
                + state
                + "\"";
    }

    private String digest(final String state) throws Exception {
        final String body =
                post("corrections/preview", asked("r1", state) + "}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$.digest");
    }

    private ResultActions confirm(final String revision, final String state, final String digest)
            throws Exception {
        return post("corrections", asked(revision, state) + ", \"digest\": \"" + digest + "\"}");
    }

    @Test
    void theBoardServesEachWarningWithTheCorrectionItsStoreOffersOrNone() throws Exception {
        // Harm: a window offers a correction the store cannot carry out, or none where it can.
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.stories[0].warnings[0].correction.kind").value("StateStated"))
                .andExpect(
                        jsonPath("$.stories[0].warnings[0].correction.states[0]").value("Backlog"))
                .andExpect(
                        jsonPath("$.stories[0].warnings[0].correction.states[1]").value("Review"))
                .andExpect(
                        jsonPath(
                                "$.stories[1].warnings[0]",
                                org.hamcrest.Matchers.hasKey("correction")))
                .andExpect(jsonPath("$.stories[1].warnings[0].correction").isEmpty());
    }

    @Test
    void aStorysCorrectionIsPreviewedThenSavedAloneAndAnsweredWithTheNewRevision()
            throws Exception {
        // Harm: a correction saved without the person's confirm of what it writes, or with its
        // issues moved along.
        post("corrections/preview", asked("r1", "Review") + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r1"))
                .andExpect(jsonPath("$.story").value("ST1"))
                .andExpect(jsonPath("$.issue").isEmpty())
                .andExpect(jsonPath("$.warning").value("StateOutsideWorkflow"))
                .andExpect(jsonPath("$.from").value("Parked"))
                .andExpect(jsonPath("$.to").value("Review"));
        assertThat(saved).as("a preview writes nothing").isEmpty();

        confirm("r1", "Review", digest("Review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(saved)
                .as("one save of the story's stated state alone")
                .containsExactly(new Writes(Map.of(), Map.of("ST1", "Review"), List.of()));
    }

    @Test
    void aStaleChangedOrRepeatedConfirmIsAConflictAndWritesNothing() throws Exception {
        // Harm: a confirm sent again after a lost answer writes over a board the person no
        // longer saw.
        final String digest = digest("Review");

        confirm("r1", "Review", "0".repeat(64))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:PreviewChanged"));
        confirm("r0", "Review", digest)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"));
        assertThat(saved).as("nothing written").isEmpty();

        confirm("r1", "Review", digest).andExpect(status().isOk());
        confirm("r1", "Review", digest)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:StaleRevision"));
        assertThat(saved).as("the repeat wrote nothing").hasSize(1);
    }

    @Test
    void aCorrectionTheBoardNoLongerLetsStandIsRefusedByNameAndWritesNothing() throws Exception {
        // Harm: a correction written over a warning gone, past another warning's hold, or into
        // a state it never offered.
        post("corrections/preview", asked("r1", "Building") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(jsonPath("$.parameters.item").value("ST1"));

        current =
                board(
                        "r1",
                        "Parked",
                        Warning.stateOutsideWorkflow().offering(STATED),
                        Warning.wrongParent());
        post("corrections/preview", asked("r1", "Review") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(jsonPath("$.detail").value(Warning.wrongParent().text()));

        current = board("r1", "Review");
        confirm("r1", "Review", "0".repeat(64))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(jsonPath("$.detail").value("The warning no longer stands on it."));
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void aWarningTheStoreOffersNoCorrectionForIsNotOfferedAndABodyNamingNoItemIsRefused()
            throws Exception {
        // Harm: a correction a store cannot carry out half happens.
        post(
                        "corrections/preview",
                        "{\"revision\": \"r1\", \"story\": \"ST2\", \"warning\":"
                                + " \"IssueOutsideProject\", \"state\": \"Backlog\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        post(
                        "corrections/preview",
                        "{\"revision\": \"r1\", \"warning\": \"NoState\", \"state\": \"Backlog\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        post("corrections", asked("r1", "Review") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value("The body names no preview's digest."));
        post(
                        "corrections/preview",
                        "{\"revision\": \"r1\", \"story\": \"ST1\", \"warning\":"
                                + " \"StateOutsideWorkflow\", \"state\": \"/home/x:pw\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.parameters.to").value(""));
        assertThat(saved).as("nothing written").isEmpty();
    }
}
