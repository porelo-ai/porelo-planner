package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoreReaderTest {

    private static final Repository OTHER =
            new Repository(
                    "porelo-other", new CloneUrl("https://example.org/porelo-other.git"), null);

    /** One commit's files of one repository. */
    private static final class Tree implements Remote {

        private final Map<String, String> files = new HashMap<>();

        @Override
        public String head(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return "c1";
        }

        @Override
        public String fetch(
                final String repository, final CloneUrl url, final @Nullable String branch) {
            return "c1";
        }

        @Override
        public Optional<String> fetched(final String repository) {
            return Optional.of("c1");
        }

        @Override
        public Optional<String> read(
                final String repository, final String commit, final String path) {
            return Optional.ofNullable(files.get(path));
        }

        @Override
        public List<String> list(
                final String repository, final String commit, final String directory) {
            return files.keySet().stream()
                    .filter(path -> path.startsWith(directory + "/"))
                    .filter(path -> path.indexOf('/', directory.length() + 1) < 0)
                    .sorted()
                    .toList();
        }

        @Override
        public List<String> directories(
                final String repository, final String commit, final String directory) {
            return List.of();
        }
    }

    @Test
    void aRepositoryWithoutIssuesAddsNoneAndNeedsNoWorkflow() {
        // A project's repository that keeps no issues, such as one holding only a parent pom, must
        // not make the whole board unreadable.
        final Tree tree = new Tree();
        tree.files.put("README.md", "# Parent\n");
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", false);
        assertThat(part.issues()).as("no issues").isEmpty();
    }

    private static final String WORKFLOW =
            """
            states:
              - {name: Idea, closed: false}
              - {name: Finished, closed: true}
              - {name: Rejected, closed: true}
            phases:
              - {name: Open, states: [Idea]}
              - {name: Done, states: [Finished, Rejected]}
            """;

    @Test
    void aFinishedIssueWhoseFileIsGoneIsReadFromItsIndexRow() {
        // A finished issue's file is deleted and its row stays; a story naming it must still find
        // it.
        final Tree tree = new Tree();
        tree.files.put("issues/workflow.yml", WORKFLOW);
        tree.files.put(
                "issues/README.md",
                """
                # Issues

                ## Finished

                | # | Title | What was built |
                |---|---|---|
                | <a id="xy02"></a>XY02 | Done Long Ago | It was built. |
                """);
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", false);
        assertThat(part.issues())
                .as("the row read as an issue in its state, with no text")
                .singleElement()
                .satisfies(
                        issue -> {
                            assertThat(issue.id()).as("id").isEqualTo("XY02");
                            assertThat(issue.state()).as("state").isEqualTo("Finished");
                            assertThat(issue.title()).as("title").isEqualTo("Done Long Ago");
                            assertThat(issue.repository())
                                    .as("repository")
                                    .isEqualTo("porelo-other");
                        });
    }

    @Test
    void issuesWithoutAWorkflowAreStillAFault() {
        // Without a workflow an issue's state cannot be checked.
        final Tree tree = new Tree();
        tree.files.put("issues/XY01-One.md", "# XY01 — One\n");
        assertThatThrownBy(() -> new StoreReader(tree, 100).read(OTHER, "c1", false))
                .as("an issue without a workflow")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("neither issues/workflow.yml nor stories/workflow.yml");
    }
}
