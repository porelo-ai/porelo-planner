package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.fuin.porelo.forge.github.Batch;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.github.FieldNames;
import org.fuin.porelo.planner.store.github.FieldsBoardWriter;
import org.fuin.porelo.planner.store.github.ForgeAccessRefusals;
import org.fuin.porelo.planner.store.github.GitHubStore;
import org.fuin.porelo.planner.store.github.IssueMapping;
import org.fuin.porelo.planner.store.github.ProjectConfig;
import org.fuin.porelo.planner.store.github.ProjectRef;
import org.fuin.porelo.planner.store.github.ProjectWrites;
import org.fuin.porelo.planner.store.github.Snapshot;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** A project GitHub Issues keeps, given a new issue and an idea as the window posts them. */
class GitHubAddsHttpTest {

    private static final String SOURCE = "{\"repository\": \"git@example.org:a/porelo-store.git\"}";

    private static final String IDEA =
            "{\"story\": {\"title\": \"Tag stories\", \"gain\": \"People find stories\","
                    + " \"test\": \"Tag one\", \"priority\": \"High\"}, \"issues\":"
                    + " [{\"repository\": \"porelo-other\", \"title\": \"Tags\", \"mustBeTrue\":"
                    + " \"A story can be tagged.\", \"size\": \"S\", \"worker\": \"Manual\"}]}";

    private static final URI GITHUB = URI.create("https://github.com");

    private static final String TOKEN = "github_pat_http_test_0123456789";

    private static final Workflow WORKFLOW =
            new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished"));

    /** The issues GitHub made, each as its repository and number. */
    private final List<String> made = new ArrayList<>();

    private int reads;

    private MockMvc mvc;

    private ChangesRegistry changes;

    private static ProjectConfig config() {
        final Map<String, String> fields = new LinkedHashMap<>();
        FieldNames.ALL.keySet().forEach(field -> fields.put(field, "F_" + field));
        final Map<String, Map<String, String>> options = new LinkedHashMap<>();
        options.put(FieldNames.STATUS, ids(WORKFLOW.states()));
        options.put(FieldNames.WORKER, ids(List.of("Agent", "Manual")));
        options.put(FieldNames.SIZE, ids(IssueMapping.SIZES));
        options.put(FieldNames.PRIORITY, ids(Story.PRIORITIES));
        return new ProjectConfig(
                GITHUB,
                new ProjectRef("example-org", 7),
                "c0ffee0",
                WORKFLOW,
                new Work(Map.of(), List.of()),
                List.of("porelo-store", "porelo-other"),
                fields,
                options,
                "PVT_7");
    }

    private static Map<String, String> ids(final List<String> names) {
        final Map<String, String> ids = new LinkedHashMap<>();
        names.forEach(name -> ids.put(name, "O_" + name));
        return ids;
    }

    /** GitHub's side of a write: it makes each issue asked for and takes every other call. */
    private final class GitHub implements ProjectWrites {

        @Override
        public Created createIssue(
                final String token,
                final String owner,
                final String repository,
                final String title,
                final String body,
                final List<String> labels) {
            final int number = made.size() + 1;
            made.add(repository + "#" + number);
            return new Created("I_" + number, 9000L + number, number, labels);
        }

        @Override
        public Batch<String> addProjectItems(
                final String token, final String projectId, final List<String> issueIds) {
            return new Batch<>(
                    issueIds.stream().map(id -> Batch.Outcome.of("PVTI_" + id)).toList(), null);
        }

        @Override
        public void addSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            // GitHub takes it.
        }

        @Override
        public Batch<String> setFieldValues(
                final String token,
                final String projectId,
                final List<GitHubGraphQl.FieldValue> values) {
            return new Batch<>(
                    values.stream().map(v -> Batch.Outcome.of(v.itemId())).toList(), null);
        }

        @Override
        public FoundPage issuesCreatedSince(
                final String token,
                final String owner,
                final String repository,
                final Instant since,
                final int page) {
            throw new AssertionError("every call was answered, so none is looked for");
        }

        @Override
        public GitHubGraphQl.IssueBoard issueBoard(
                final String token, final List<String> issueIds, final String projectId) {
            throw new AssertionError("a new item reads no item before it is made");
        }

        @Override
        public GitHubGraphQl.FieldOptions setFieldOptions(
                final String token,
                final String fieldId,
                final List<GitHubGraphQl.NewOption> options) {
            throw new AssertionError("a new item sets no field's options");
        }

        @Override
        public void removeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId) {
            throw new AssertionError("an add changes no other link");
        }

        @Override
        public void reprioritizeSubIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long subIssueId,
                final @Nullable Long afterId,
                final @Nullable Long beforeId) {
            throw new AssertionError("an add changes no other link");
        }

        @Override
        public void addBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            throw new AssertionError("an add changes no other link");
        }

        @Override
        public void removeBlockedBy(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final long blockerId) {
            throw new AssertionError("an add changes no other link");
        }

        @Override
        public void updateIssue(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final @Nullable String body,
                final @Nullable Opening opening) {
            throw new AssertionError("a new item changes no issue");
        }

        @Override
        public CommentsPage issueComments(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final int page) {
            throw new AssertionError("a new item reads no comment");
        }

        @Override
        public void createIssueComment(
                final String token,
                final String owner,
                final String repository,
                final int number,
                final String body) {
            throw new AssertionError("a new item writes no comment");
        }
    }

    @BeforeEach
    void serve() throws Exception {
        final ProjectConfig config = config();
        final GitHubStore store =
                new GitHubStore(
                        config,
                        () -> config,
                        GitHubStore.Parts.reading(
                                        read -> {
                                            reads++;
                                            return new Snapshot(
                                                    new Board(
                                                            new Revision("r" + reads),
                                                            WORKFLOW,
                                                            List.of(),
                                                            List.of(),
                                                            read.work(),
                                                            read.repositories()),
                                                    Map.of(),
                                                    read);
                                        },
                                        states -> null)
                                .writer(
                                        new FieldsBoardWriter(
                                                new GitHub(),
                                                origin -> TOKEN,
                                                new ForgeAccessRefusals(GITHUB))),
                        origin -> TOKEN,
                        Duration.ofSeconds(5));
        final Projects projects = new Projects((project, repository, branch) -> store, 8);
        changes =
                new ChangesRegistry(
                        projects,
                        Duration.ofHours(1),
                        Executors.newSingleThreadScheduledExecutor());
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new BoardController(projects, changes),
                                new EditsController(new BoardEdits(projects, changes)))
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
    void aNewIssueIsAnsweredByItsTrackerReference() throws Exception {
        // Harm: a project GitHub keeps answering the window that it adds no issues.
        mvc.perform(
                        post("/projects/alpha/issues")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"repository\": \"porelo-other\", \"title\": \"An"
                                                + " idea\", \"mustBeTrue\": \"It holds.\","
                                                + " \"revision\": \"r1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("porelo-other#1"))
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(made).as("the issues GitHub made").containsExactly("porelo-other#1");
    }

    @Test
    void aNewIdeaIsAnsweredByItsStorysTrackerReference() throws Exception {
        // Harm: a project GitHub keeps answering the window that it adds no ideas.
        mvc.perform(
                        post("/projects/alpha/ideas")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"offer\": \"4f2c1d7e-8a3b-4c5d-9e6f-0a1b2c3d4e5f\", \"idea\": "
                                                + IDEA
                                                + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("porelo-store#1"));
        assertThat(made)
                .as("the issues GitHub made, the story first")
                .containsExactly("porelo-store#1", "porelo-other#2");
    }

    @Test
    void aNewStoryIsAnsweredByItsAndItsIssuesTrackerReferences() throws Exception {
        // Harm: a project GitHub keeps answering the window that it makes no stories.
        mvc.perform(
                        post("/projects/alpha/stories")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"idea\": " + IDEA + ", \"revision\": \"r1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("porelo-store#1"))
                .andExpect(jsonPath("$.issues[0]").value("porelo-other#2"))
                .andExpect(jsonPath("$.revision").value("r2"));
        assertThat(made)
                .as("the issues GitHub made, the story first")
                .containsExactly("porelo-store#1", "porelo-other#2");
    }
}
