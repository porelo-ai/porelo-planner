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
import org.fuin.porelo.planner.store.RepositoryWarning;
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

    /** Whether the store keeps its repositories' labels. */
    private boolean keepsLabels;

    /** The label calls the store made, one list per correction. */
    private final List<List<RepositoryWarning.Call>> labelCalls = new ArrayList<>();

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
                    public boolean keepsRepositoryLabels() {
                        return keepsLabels;
                    }

                    @Override
                    public Board rereadLabels(final String repository) {
                        return current;
                    }

                    @Override
                    public Board correctLabels(
                            final String repository, final List<RepositoryWarning.Call> calls) {
                        final var refused = refusal;
                        if (refused != null) {
                            throw refused;
                        }
                        labelCalls.add(calls);
                        current = current.withRepositories(List.of(), List.of());
                        current =
                                new Board(
                                        new Revision("r2"),
                                        current.workflow(),
                                        current.stories(),
                                        current.issues(),
                                        current.work(),
                                        current.repositories());
                        return current;
                    }

                    @Override
                    public Board save(final Revision revision, final Writes writes) {
                        final var refused = refusal;
                        if (refused != null) {
                            throw refused;
                        }
                        saved.add(writes);
                        // The story keeps its state unless the save writes one.
                        final String state =
                                current.story("ST1").map(Story::state).orElse("Parked");
                        current =
                                CorrectionsEndpointTest.board(
                                        "r2", writes.stories().getOrDefault("ST1", state));
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
        return "{\"story\": \"ST1\", \"warning\": \"StateOutsideWorkflow\", \"choice\": \""
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
                                + " \"IssueOutsideProject\", \"choice\": \"Backlog\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        post("corrections/preview", "{\"warning\": \"NoState\", \"choice\": \"Backlog\"}")
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
                                + " \"StateOutsideWorkflow\", \"choice\": \"/home/x:pw\"}")
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
                                + " \"StateOutsideWorkflow\", \"choice\": \"Review\"}")
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
                                + " \"StateOutsideWorkflow\", \"choice\": \"Review\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NoSuchStory"));
        assertThat(saved).as("nothing written").isEmpty();
    }

    @Test
    void aStorysMissingPriorityIsPreviewedAndSavedAsTheChoiceAndAChoiceNotOfferedIsRefused()
            throws Exception {
        // Harm: a priority written that the person never picked or the store does not know, or
        // one written over a story changed since its preview.
        current =
                board(
                        "r1",
                        "Backlog",
                        Warning.noPriority()
                                .offering(new Correction("Priority", Story.PRIORITIES)));
        mvc.perform(get("/projects/alpha/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stories[0].warnings[0].correction.kind").value("Priority"))
                .andExpect(
                        jsonPath("$.stories[0].warnings[0].correction.choices")
                                .value(org.hamcrest.Matchers.contains(Story.PRIORITIES.toArray())));
        final String ask = "{\"story\": \"ST1\", \"warning\": \"NoPriority\", \"choice\": ";
        post("corrections/preview", ask + "\"Urgent\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"));

        final String preview =
                post("corrections/preview", ask + "\"Low\"}")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.story").value("ST1"))
                        .andExpect(jsonPath("$.from").value(""))
                        .andExpect(jsonPath("$.to").value("Low"))
                        .andExpect(jsonPath("$.targets[0].id").value("ST1"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(saved).as("a preview writes nothing").isEmpty();
        post(
                        "corrections",
                        ask
                                + "\"Low\", \"seen\": "
                                + Seens.ofAnswer(preview)
                                + ", \"digest\": \""
                                + JsonPath.read(preview, "$.digest")
                                + "\"}")
                .andExpect(status().isOk());
        assertThat(saved)
                .as("the story's priority alone, written once")
                .containsExactly(new Writes(Map.of(), Map.of(), Map.of(), Map.of("ST1", "Low")));
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

    @Test
    void aRepositorysLabelsAreNotOfferedByAStoreWithoutThemAndABodyNamingTwoIsRefused()
            throws Exception {
        // Harm: a repository's labels written by a store that holds none, or a correction
        // naming a repository and an item at once carried out on either of them.
        final String labels = "{\"repository\": \"porelo-store\", \"warning\": \"TopicLabels\"";
        post("corrections/preview", labels + "}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        post("corrections", labels + ", \"seen\": {}, \"digest\": \"" + "0".repeat(64) + "\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
        post("corrections/preview", labels + ", \"story\": \"ST1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "A correction must name exactly one story, one issue or"
                                                + " one repository."));
        post(
                        "corrections/preview",
                        "{\"repository\": \"/home/x:pw\", \"warning\": \"TopicLabels\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:InvalidName"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString("pw"))));
        assertThat(saved).as("nothing written").isEmpty();
    }

    /** The store keeps the labels, and porelo-store lacks security, holds bug and tests differs. */
    private void labelsWrong() {
        keepsLabels = true;
        current =
                current.withRepositories(
                        List.of(
                                new RepositoryWarning(
                                                "porelo-store",
                                                RepositoryWarning.TOPIC_LABELS,
                                                List.of("security"),
                                                List.of("bug"),
                                                List.of("tests"),
                                                null)
                                        .offered()),
                        List.of());
    }

    private static final String LABELS =
            "{\"repository\": \"porelo-store\", \"warning\": \"TopicLabels\"";

    private String labelsDigest() throws Exception {
        return JsonPath.read(
                post("corrections/preview", LABELS + "}")
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.digest");
    }

    private ResultActions labelsConfirm(final String digest) throws Exception {
        return post("corrections", LABELS + ", \"seen\": {}, \"digest\": \"" + digest + "\"}");
    }

    @Test
    void aRepositorysLabelsAreWarnedOfPreviewedAsTheirCallsAndSetRightOnce() throws Exception {
        // Harm: the calls made were not the ones the preview listed, or a confirm sent twice
        // made them twice.
        labelsWrong();
        post("corrections/preview", LABELS + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repository").value("porelo-store"))
                .andExpect(jsonPath("$.targets").isEmpty())
                .andExpect(jsonPath("$.calls[0].action").value("Delete"))
                .andExpect(jsonPath("$.calls[0].label").value("bug"))
                .andExpect(jsonPath("$.calls[1].action").value("Create"))
                .andExpect(jsonPath("$.calls[1].color").value("b60205"))
                .andExpect(jsonPath("$.calls[2].action").value("Update"))
                .andExpect(jsonPath("$.calls[2].label").value("tests"))
                .andExpect(jsonPath("$.calls.length()").value(3));
        assertThat(labelCalls).as("a preview calls nothing").isEmpty();

        final String digest = labelsDigest();
        labelsConfirm(digest)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(labelCalls).as("the calls, once").hasSize(1);
        assertThat(labelCalls.getFirst()).hasSize(3);

        labelsConfirm(digest)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:CorrectionRefused"));
        assertThat(labelCalls).as("no call by the second confirm").hasSize(1);
        assertThat(saved).as("no item written").isEmpty();
    }

    @Test
    void aLabelsConfirmSendingVersionsSeenIsRefusedInFixedWordsWithoutACall() throws Exception {
        // Harm: a body the contract does not allow is taken, so a window binding a repository's
        // correction to versions it believes checked has them ignored in silence.
        labelsWrong();
        final String digest = labelsDigest();

        post(
                        "corrections",
                        LABELS
                                + ", \"seen\": "
                                + Seens.of(current)
                                + ", \"digest\": \""
                                + digest
                                + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(jsonPath("$.parameters.item").value("porelo-store"))
                .andExpect(
                        jsonPath("$.detail")
                                .value("A repository's correction sends its versions seen as {}."));
        assertThat(labelCalls).as("no call").isEmpty();
    }

    @Test
    void aLabelsConfirmOfAnotherPreviewOrRefusedByGitHubAnswersWithoutGitHubsWords()
            throws Exception {
        // Harm: calls the person never saw were made, or GitHub's own words reached the window.
        labelsWrong();
        labelsConfirm("0".repeat(64))
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:PreviewChanged"));
        assertThat(labelCalls).as("no call for another digest").isEmpty();

        refusal =
                new org.fuin.porelo.planner.store.StoreException.NotSavable(
                        "porelo-store", "GitHub refused the call with status 422");
        labelsConfirm(labelsDigest())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        org.hamcrest.Matchers.containsString(
                                                "GitHub refused the call with status 422")));
        post("corrections", LABELS + ", \"seen\": {}, \"digest\": \"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:MoveNotAllowed"));
    }
}
