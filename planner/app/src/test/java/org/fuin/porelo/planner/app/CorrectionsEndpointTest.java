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

    private final List<Writes> saved = new ArrayList<>();

    /** What the store's next save refuses with, or null. */
    private org.fuin.porelo.planner.store.@org.jspecify.annotations.Nullable StoreException refusal;

    private Board current = board("r1", "Parked", Warning.stateOutsideWorkflow());

    private MockMvc mvc;

    private ChangesRegistry changes;

    /**
     * ST1 in the state given with its warnings, holding one issue in Backlog; ST2 names one
     * outside.
     */
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
                                        "High",
                                        null,
                                        List.of("AB01"), // not-a-citation
                                        "",
                                        null)
                                .withWarnings(List.of(w)),
                        new Story("ST2", "Another", "Backlog", "High", null, List.of(), "", null)
                                .withWarnings(List.of(Warning.issueOutsideProject()))),
                List.of(
                        new Issue(
                                "AB01", // not-a-citation
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
                    public String storyState(final List<String> states) {
                        return states.isEmpty() ? "Backlog" : states.getFirst();
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        final var refused = refusal;
                        if (refused != null) {
                            throw refused;
                        }
                        saved.add(writes);
                        current =
                                CorrectionsEndpointTest.board(
                                        "r2", writes.stories().getOrDefault("ST1", "Parked"));
                        return current;
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

    private ResultActions post(final String path, final String body) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/projects/alpha/" + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static String asked(final String state) {
        return "{\"story\": \"ST1\", \"warning\": \"StateOutsideWorkflow\", \"state\": \""
                + state
                + "\"";
    }

    private String digest(final String state) throws Exception {
        final String body =
                post("corrections/preview", asked(state) + "}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return JsonPath.read(body, "$.digest");
    }

    private ResultActions confirm(final String seen, final String state, final String digest)
            throws Exception {
        return post(
                "corrections",
                asked(state) + ", \"seen\": " + seen + ", \"digest\": \"" + digest + "\"}");
    }

    @Test
    void aStorysStateWarningIsOfferedNoCorrectionAndItsPreviewIsNotOffered() throws Exception {
        // Harm: a story's state set by hand while open, which its issues give, or one a window
        // offers to correct that no store carries out.
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.stories[0].warnings[0]",
                                org.hamcrest.Matchers.hasKey("correction")))
                .andExpect(jsonPath("$.stories[0].warnings[0].correction").isEmpty());
        post("corrections/preview", asked("Review") + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void aCorrectionTheBoardNoLongerLetsStandIsRefusedByNameAndWritesNothing() throws Exception {
        // Harm: a correction written over a warning gone, past another warning's hold, or into
        // a state it never offered.
        current = board("r1", "Parked", Warning.stateOutsideWorkflow(), Warning.wrongParent());
        post("corrections/preview", asked("Review") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(jsonPath("$.detail").value(Warning.wrongParent().text()));

        current = board("r1", "Review");
        confirm(Seens.of(current), "Review", "0".repeat(64))
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
                        "{\"story\": \"ST2\", \"warning\":"
                                + " \"IssueOutsideProject\", \"state\": \"Backlog\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        post("corrections/preview", "{\"warning\": \"NoState\", \"state\": \"Backlog\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
        post("corrections", asked("Review") + ", \"seen\": " + Seens.of(current) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.parameters.reason")
                                .value("The body names no preview's digest."));
        post(
                        "corrections/preview",
                        "{\"story\": \"ST1\", \"warning\":"
                                + " \"StateOutsideWorkflow\", \"state\": \"/home/x:pw\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.parameters.to").value(""));
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void anUnreadableBodyIsRefusedAsAMovesIsWithFixedWords() throws Exception {
        // Harm: the window reads an answer the contract does not describe, or the parser's
        // words repeat what the body held.
        for (final String path : List.of("corrections/preview", "corrections")) {
            post(path, "{\"revision\": 1")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                    .andExpect(jsonPath("$.detail").value("The body is no correction."));
        }
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void anIssuesMoveAndAnUnknownStoryAreRefusedAndWriteNothing() throws Exception {
        // Harm: an issue's state set right past the move's rules, or a correction written for an
        // item the board does not hold.
        final Board plain = board("r1", "Parked", Warning.stateOutsideWorkflow());
        final Issue issue = plain.issues().getFirst();
        current =
                new Board(
                        plain.revision(),
                        plain.workflow(),
                        plain.stories(),
                        List.of(
                                issue.withWarnings(
                                        List.of(
                                                Warning.stateOutsideWorkflow()
                                                        .offering(
                                                                new Correction(
                                                                        Correction.MOVE,
                                                                        List.of(
                                                                                "Backlog",
                                                                                "Review")))))),
                        plain.work(),
                        plain.repositories());
        post(
                        "corrections/preview",
                        "{\"issue\": \"AB01\", \"warning\":" // not-a-citation
                                + " \"StateOutsideWorkflow\", \"state\": \"Review\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "It is set right by a move of it, previewed and saved as"
                                                + " any move."));
        current = plain;
        post(
                        "corrections/preview",
                        "{\"story\": \"ST9\", \"warning\":"
                                + " \"StateOutsideWorkflow\", \"state\": \"Review\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"));
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void whileTheWorkRulesHaveFaultsAPreviewIsRefusedWithThemAndWritesNothing() throws Exception {
        // Harm: an item put into a working state whose rule could not be read.
        final Board read = current;
        current =
                new Board(
                        read.revision(),
                        read.workflow(),
                        read.stories(),
                        read.issues(),
                        new Work(read.work().rules(), List.of("work.yml: Review is no rule")),
                        read.repositories());

        post("corrections/preview", asked("Review") + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("The work rules have faults: work.yml: Review is no rule"));
        assertThat(saved).as("nothing written").isEmpty();
    }
}
