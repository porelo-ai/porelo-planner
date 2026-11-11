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
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
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

/** The new issue call as the window posts it: checked, saved through the store, refused by name. */
class IssuesEndpointTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private final List<NewIssue> added = new ArrayList<>();

    private @Nullable RuntimeException failure;

    private Board current = board("r1");

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static Board board(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
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
                    public NewIssue.Added createIssue(
                            final Revision revision, final NewIssue issue) {
                        if (failure != null) {
                            throw failure;
                        }
                        added.add(issue);
                        current = IssuesEndpointTest.board("r2");
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
                        + "T".repeat(121)
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
        refused("{\"revision\": token123secret}", "");
        refused(
                "{\"repository\": \"porelo-other\", \"title\": \"T\", \"mustBeTrue\": \""
                        + "W".repeat(65536)
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
    void aStoreThatAddsNoIssuesIsAnsweredAsNotOffered() throws Exception {
        // A bare server error would leave the window unable to tell a missing feature from a fault.
        failure = new UnsupportedOperationException("This store does not add issues");
        issue(
                        "{\"repository\": \"porelo-store\", \"title\": \"T\", \"mustBeTrue\":"
                                + " \"W\", \"revision\": \"r1\"}")
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("urn:porelo-planner:NotOffered"));
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
