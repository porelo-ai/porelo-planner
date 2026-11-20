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
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The edits by hand as the window sends them: each answer and refusal mapped to HTTP. */
class EditsEndpointTest {

    private final List<Object> written = new ArrayList<>();

    private boolean offered = true;

    private @Nullable RuntimeException failure;

    private Board current =
            new Board(
                    new Revision("r1"),
                    new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                    List.of(
                            new Story(
                                    "ST1",
                                    "One",
                                    "Idea",
                                    false,
                                    "High",
                                    null,
                                    List.of("AB1"),
                                    "",
                                    null)),
                    List.of(
                            new Issue(
                                    "AB1",
                                    "porelo-store",
                                    "One",
                                    "Idea",
                                    null,
                                    List.of(),
                                    null,
                                    null,
                                    ""),
                            new Issue(
                                    "AB2",
                                    "porelo-store",
                                    "Two",
                                    "Idea",
                                    null,
                                    List.of(),
                                    null,
                                    null,
                                    "")),
                    new Work(Map.of(), List.of()),
                    List.of("porelo-store"));

    private MockMvc mvc;

    private ChangesRegistry changes;

    @BeforeEach
    void serve() {
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
                    public Board setFields(
                            final Revision revision, final String issue, final Fields fields) {
                        return write(fields);
                    }

                    @Override
                    public Board setBlockedBy(
                            final Revision revision,
                            final String issue,
                            final List<String> blockedBy) {
                        return write(blockedBy);
                    }

                    @Override
                    public void comment(final String issue, final String text) {
                        // The board shows no comment, so its revision stays.
                        written.add(text);
                    }

                    @Override
                    public NewIdea.Added createIdea(final Revision revision, final NewIdea idea) {
                        return new NewIdea.Added("ST2", List.of("AB3"), write(idea.title()));
                    }
                };
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        projects.put("alpha", "git@example.org:a/porelo-store.git", null);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new EditsController(new BoardEdits(projects, changes)))
                        .setControllerAdvice(new Problems())
                        .build();
    }

    @AfterEach
    void close() {
        changes.close();
    }

    private Board write(final Object what) {
        if (!offered) {
            throw new UnsupportedOperationException(PlanningStore.NOT_OFFERED);
        }
        if (failure != null) {
            throw failure;
        }
        written.add(what);
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

    private ResultActions send(final String method, final String path, final String body)
            throws Exception {
        return mvc.perform(
                ("POST".equals(method)
                                ? post("/projects/alpha" + path)
                                : put("/projects/alpha" + path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    void fieldsAreReadIntoTheirRecordAndAnsweredWithTheNewRevision() throws Exception {
        // A member read into the wrong field would set a value the person never chose.
        send(
                        "PUT",
                        "/issues/AB1/fields",
                        "{\"estimateMinutes\": 90, \"rank\": null, \"revision\": \"r1\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("r2"));

        assertThat(written)
                .as("the fields written")
                .containsExactly(
                        new Fields(
                                java.util.Set.of(Fields.Field.ESTIMATE, Fields.Field.RANK),
                                null,
                                90,
                                null,
                                null));
    }

    @Test
    void aMalformedBodyOrAnInvalidIdIsRefusedBeforeTheStore() throws Exception {
        // A body read loosely, or a path id taken as it came, would write what nobody meant.
        send("PUT", "/issues/AB1/fields", "{\"size\": 3, \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        send(
                        "PUT",
                        "/issues/AB1/blockedBy",
                        "{\"blockedBy\": [\"AB2\"], \"extra\": 1, \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("EditNotAllowed"));
        send("PUT", "/issues/ab-1/blockedBy", "{\"blockedBy\": [], \"revision\": \"r1\"}")
                .andExpect(status().isBadRequest());
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aStaleRevisionIsAConflictAndAnEditTheStoreDoesNotOfferIsNotOffered() throws Exception {
        // A missing feature told as a refused write, or a lost edit as a success, misleads the
        // window about what to do next.
        send("PUT", "/issues/AB1/blockedBy", "{\"blockedBy\": [\"AB2\"], \"revision\": \"r0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("StaleRevision"));

        offered = false;
        send("PUT", "/issues/AB1/blockedBy", "{\"blockedBy\": [\"AB2\"], \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        send("PUT", "/stories/ST1/issues", "{\"issues\": [\"AB1\", \"AB2\"], \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.title").value("NotOffered"));
        assertThat(written).as("nothing written").isEmpty();
    }

    @Test
    void aCommentIsAnsweredWithNoContentAndAStoryAsCreated() throws Exception {
        // A comment answered with a revision, or a story without its ids, leaves the window
        // waiting for a board change that never comes or unable to show what it made.
        send("POST", "/issues/AB1/comments", "{\"text\": \"A note.\"}")
                .andExpect(status().isNoContent());
        send(
                        "POST",
                        "/stories",
                        "{\"idea\": {\"story\": {\"title\": \"A story\", \"gain\": \"A gain.\","
                                + " \"test\": \"A test.\", \"priority\": \"High\"}, \"issues\":"
                                + " [{\"repository\": \"porelo-store\", \"title\": \"An issue\","
                                + " \"mustBeTrue\": \"It works.\", \"worker\": \"Agent\"}]},"
                                + " \"revision\": \"r1\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ST2"))
                .andExpect(jsonPath("$.issues[0]").value("AB3"))
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(written).as("the writes").containsExactly("A note.", "A story");
    }

    @Test
    void aStoryTheStoreCannotWriteIsAnIdeaRefused() throws Exception {
        // A refused story answered as a server fault gives the window no reason to show.
        failure = new StoreException.NotSavable("ST2", "the repository is archived");
        send(
                        "POST",
                        "/stories",
                        "{\"idea\": {\"story\": {\"title\": \"A story\", \"gain\": \"A gain.\","
                                + " \"test\": \"A test.\", \"priority\": \"High\"}, \"issues\":"
                                + " [{\"repository\": \"porelo-store\", \"title\": \"An issue\","
                                + " \"mustBeTrue\": \"It works.\", \"worker\": \"Agent\"}]},"
                                + " \"revision\": \"r1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("IdeaRefused"));
    }
}
