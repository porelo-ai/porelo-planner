package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.git.stories.StageRecords;
import org.fuin.porelo.planner.store.git.stories.Story;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class StoreReaderTest {

    private static final Repository OWN =
            new Repository(
                    "porelo-store", new CloneUrl("https://example.org/porelo-store.git"), null);

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

        @Override
        public List<Found> grep(
                final String repository,
                final String commit,
                final java.util.Collection<String> words) {
            final List<Found> found = new java.util.ArrayList<>();
            files.forEach(
                    (path, text) -> {
                        final String[] lines = text.split("\n", -1);
                        for (int i = 0; i < lines.length; i++) {
                            final String line = lines[i];
                            if (words.stream().anyMatch(line::contains)) {
                                found.add(new Found(path, i + 1, line));
                            }
                        }
                    });
            return found;
        }
    }

    @Test
    void aRepositoryWithoutIssuesAddsNoneAndNeedsNoWorkflow() {
        // A project's repository that keeps no issues, such as one holding only a parent pom, must
        // not make the whole board unreadable.
        final Tree tree = new Tree();
        tree.files.put("README.md", "# Parent\n");
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));
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
    void aStageRecordThatCannotBeReadOrIsStillOpenIsAFaultNamingItsFile() {
        // A broken or open record fails the repository's commit check and cannot be counted, and
        // nobody would see it before the next build turned red.
        final Tree tree = new Tree();
        final String good =
                StageRecords.move(
                        null,
                        "CD01",
                        null,
                        "Idea",
                        "Finished",
                        java.time.Instant.parse("2026-01-01T10:00:00Z"),
                        Workflow.read(WORKFLOW));
        tree.files.put("stats/CD01.jsonl", good);
        tree.files.put("stats/CD02.jsonl", "{\"issue\":\n");
        tree.files.put(
                "stats/CD03.jsonl",
                good.replace("CD01", "CD03")
                        .replace("\"actor\":\"person\"", "\"actor\":\"agent\""));
        tree.files.put("stats/seed.csv", "size,minutes\nM,53\n");

        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));

        assertThat(part.faults())
                .as(
                        "each unreadable record named with its file and why, the good one and the seed not")
                .containsExactly(
                        "porelo-other: stats/CD02.jsonl: invalid JSON in a stage record",
                        "porelo-other: stats/CD03.jsonl: invalid record actor for its stage");
    }

    @Test
    void aFinishedIssueWhoseFileIsGoneIsReadFromItsIndexRow() {
        // A finished issue's file is deleted and its row stays; a story naming it must still find
        // it.
        final Tree tree = new Tree();
        tree.files.put(
                "issues/README.md",
                """
                # Issues

                ## Finished

                | # | Title | What was built |
                |---|---|---|
                | <a id="xy02"></a>XY02 | Done Long Ago | It was built. |
                """);
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));
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
    void theOwnRepositoryWithoutAWorkflowIsRefused() {
        // Without the project's workflow no repository's issue can be checked.
        final Tree tree = new Tree();
        tree.files.put("issues/XY01-One.md", "# XY01 — One\n");
        assertThatThrownBy(() -> new StoreReader(tree, 100).read(OWN, "c1", null))
                .as("the project's own repository without stories/workflow.yml")
                .isInstanceOf(StoreException.WorkflowMissing.class);
    }

    @Test
    void aStoryFileWithoutATitleIsReadOntoTheBoardBesideOneWithATitle() {
        // Prevents a story the stories index lists from missing on the board without a word, when
        // its file is named ST<n>.md, which the work format allows beside ST<n>-<Title>.md.
        final Tree tree = new Tree();
        tree.files.put("stories/workflow.yml", WORKFLOW);
        tree.files.put("issues/XY01-One.md", issue("XY01", "Idea"));
        tree.files.put("stories/ST7.md", story("ST7"));
        tree.files.put("stories/ST8-Titled.md", story("ST8"));

        final Part part = new StoreReader(tree, 100).read(OWN, "c1", null);

        assertThat(part.faults()).as("nothing left out").isEmpty();
        assertThat(part.stories())
                .as("the untitled story read as the titled one is")
                .extracting(Story::id)
                .containsExactlyInAnyOrder("ST7", "ST8");
        assertThat(part.paths())
                .as("each story kept with the file a move writes")
                .containsEntry(Part.story("ST7"), "stories/ST7.md")
                .containsEntry(Part.story("ST8"), "stories/ST8-Titled.md");
    }

    private static String story(final String id) {
        return "# "
                + id
                + " — A story\n\n## Data\n\n```json\n{\n  \"$schema\": \"story-2.json\",\n"
                + "  \"id\": \""
                + id
                + "\",\n  \"title\": \"A story\",\n  \"state\": \"Idea\",\n"
                + "  \"gain\": \"Something.\",\n  \"test\": \"A test.\",\n  \"priority\": \"High\",\n"
                + "  \"issues\": [\"porelo-store#XY01\"],\n  \"tested\": null\n}\n```\n";
    }

    @Test
    void theOwnRepositorysIssuesAreCheckedAgainstTheProjectsWorkflowAndNotAnIssuesWorkflow() {
        // A second workflow beside the project's would let the own issues stand in other states.
        final Tree tree = new Tree();
        tree.files.put("stories/workflow.yml", WORKFLOW);
        tree.files.put("issues/workflow.yml", WORKFLOW.replace("Idea", "Thought"));
        tree.files.put("issues/XY01-One.md", issue("XY01", "Idea"));
        final Part part = new StoreReader(tree, 100).read(OWN, "c1", null);
        assertThat(part.faults()).as("nothing left out").isEmpty();
        assertThat(part.issues())
                .as("the issue in a state of the project's workflow")
                .extracting(Issue::id)
                .containsExactly("XY01");
    }

    @Test
    void anotherRepositorysIssuesAreCheckedAgainstTheProjectsWorkflowAlone() {
        // The repository's own workflow files are not read, so they cannot widen what it holds.
        final Tree tree = new Tree();
        tree.files.put("issues/workflow.yml", WORKFLOW.replace("Idea", "Thought"));
        tree.files.put("stories/workflow.yml", WORKFLOW.replace("Idea", "Thought"));
        tree.files.put("issues/XY01-One.md", issue("XY01", "Idea"));
        tree.files.put("issues/XY02-Two.md", issue("XY02", "Thought"));
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));
        assertThat(part.issues())
                .as("only the issue in a state of the project's workflow")
                .extracting(Issue::id)
                .containsExactly("XY01");
        assertThat(part.faults())
                .as("the other is left out, named with its file")
                .singleElement()
                .asString()
                .startsWith("porelo-other: issues/XY02-Two.md: ");
    }

    @Test
    void aSettledIssuesNumberLeftInADocumentOrALinkToItsFileIsAFaultAndWhatHoldsItOnPurposeIsNot() {
        // A mention left behind sends a reader to an issue nobody keeps; data, generated files
        // and what says it holds the shape on purpose would bury the real ones.
        final Tree tree = new Tree();
        tree.files.put(
                "issues/README.md",
                """
                # Issues

                ## Finished

                | # | Title | What was built |
                |---|---|---|
                | <a id="xy02"></a>XY02 | Done Long Ago | It was built. |
                """);
        final String one = issue("XY01", "Idea");
        tree.files.put("issues/XY01-One.md", one + "See [the old one](XY02-Done-Long-Ago.md).\n");
        tree.files.put("doc/guide.md", "How it works.\nAs XY02 asked.\nXY021 is another issue.\n");
        tree.files.put("doc/marked.md", "XY02 as a parser reads it - not-a-citation\n");
        tree.files.put("doc/exempt.md", "XY02\n");
        tree.files.put("citations-exempt.txt", "# on purpose\ndoc/exempt.md # a list\n");
        tree.files.put(
                "stats/XY02.jsonl",
                StageRecords.move(
                        null,
                        "XY02",
                        null,
                        "Idea",
                        "Finished",
                        java.time.Instant.parse("2026-01-01T10:00:00Z"),
                        Workflow.read(WORKFLOW)));
        tree.files.put("stories/ST1-A.md", "\"issues\": [\"porelo-other#XY02\"]\n");
        tree.files.put("stories/README.md", "| XY02 |\n");
        tree.files.put("src/test/resources/sample/issues/XY02-Fixture.md", "# XY02\n");
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));
        assertThat(part.faults())
                .as("the document's line and the link to the deleted file, nothing else")
                .containsExactlyInAnyOrder(
                        "porelo-other: doc/guide.md:2: XY02 names an issue that is settled",
                        "porelo-other: issues/XY01-One.md:"
                                + one.split("\n", -1).length
                                + ": links to the deleted file of XY02");
    }

    @Test
    void aLiveSubIssueOfASettledIssueIsNoMentionOfIt() {
        // A false fault would hold the repository's board and saves on an issue still worked.
        final Tree tree = new Tree();
        tree.files.put(
                "issues/README.md",
                """
                # Issues

                ## Finished

                | # | Title | What was built |
                |---|---|---|
                | <a id="xy02"></a>XY02 | Done Long Ago | It was built. |
                """);
        tree.files.put("doc/guide.md", "XY02.1 is still worked on.\nSo is XY02.12.\n");
        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));
        assertThat(part.faults())
                .as("a sub-issue's number names the sub-issue, not the settled one")
                .isEmpty();
    }

    @Test
    void anIssueIsWorkedByAnAgentUnlessItsHeadNamesManualAndASettledRowByAnAgent() {
        // A head without a worker read as Manual would stop every agent's work on the board.
        final Tree tree = new Tree();
        tree.files.put(
                "issues/README.md",
                """
                # Issues

                ## Finished

                | # | Title | What was built |
                |---|---|---|
                | <a id="xy03"></a>XY03 | Done Long Ago | It was built. |
                """);
        tree.files.put("issues/XY01-One.md", issue("XY01", "Idea"));
        tree.files.put("issues/XY02-Two.md", worked(issue("XY02", "Idea"), "Manual"));
        tree.files.put("issues/XY04-Four.md", worked(issue("XY04", "Idea"), "Agent"));

        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));

        assertThat(part.faults()).as("nothing left out").isEmpty();
        assertThat(part.issues())
                .as("each issue's worker")
                .extracting(Issue::id, Issue::worker)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("XY01", Worker.AGENT),
                        org.assertj.core.groups.Tuple.tuple("XY02", Worker.MANUAL),
                        org.assertj.core.groups.Tuple.tuple("XY03", Worker.AGENT),
                        org.assertj.core.groups.Tuple.tuple("XY04", Worker.AGENT));
    }

    @Test
    void aWorkerOtherThanAgentOrManualIsAFaultNamingTheFileAndLeavesTheIssueOut() {
        // A worker misspelled and read as either would start or hold work nobody chose.
        final Tree tree = new Tree();
        tree.files.put("issues/XY01-One.md", worked(issue("XY01", "Idea"), "agent"));
        tree.files.put("issues/XY02-Two.md", worked(issue("XY02", "Idea"), "Robot"));
        tree.files.put("issues/XY03-Three.md", issue("XY03", "Idea"));

        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));

        assertThat(part.issues())
                .as("only the issue whose head the schema takes")
                .extracting(Issue::id)
                .containsExactly("XY03");
        assertThat(part.faults())
                .as("each faulty head named with its file and its worker")
                .hasSize(2)
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .as("the first")
                                        .startsWith("porelo-other: issues/XY01-One.md: ")
                                        .contains("/worker"))
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .as("the second")
                                        .startsWith("porelo-other: issues/XY02-Two.md: ")
                                        .contains("/worker"));
    }

    @Test
    void aSubIssueIsWorkedAsItsParentIsWhateverItsOwnHeadSays() {
        // A sub-issue worked otherwise than its parent would let an agent into a person's work.
        final Tree tree = new Tree();
        tree.files.put("issues/XY01-One.md", worked(issue("XY01", "Idea"), "Manual"));
        tree.files.put("issues/XY01.1-Part.md", worked(sub("XY01.1", "XY01"), "Agent"));
        tree.files.put("issues/XY01.2-Part.md", sub("XY01.2", "XY01"));
        tree.files.put("issues/XY02-Two.md", issue("XY02", "Idea"));
        tree.files.put("issues/XY02.1-Part.md", worked(sub("XY02.1", "XY02"), "Manual"));

        final Part part = new StoreReader(tree, 100).read(OTHER, "c1", Workflow.read(WORKFLOW));

        assertThat(part.faults()).as("nothing left out").isEmpty();
        assertThat(part.issues())
                .as("each sub-issue's worker is its parent's")
                .extracting(Issue::id, Issue::worker)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("XY01", Worker.MANUAL),
                        org.assertj.core.groups.Tuple.tuple("XY01.1", Worker.MANUAL),
                        org.assertj.core.groups.Tuple.tuple("XY01.2", Worker.MANUAL),
                        org.assertj.core.groups.Tuple.tuple("XY02", Worker.AGENT),
                        org.assertj.core.groups.Tuple.tuple("XY02.1", Worker.AGENT));
    }

    /** The issue's head with a worker as its last member. */
    private static String worked(final String text, final String worker) {
        return last(text, "worker", worker);
    }

    private static String sub(final String id, final String parent) {
        return last(issue(id, "Idea"), "parent", parent);
    }

    private static String last(final String text, final String field, final String value) {
        return text.replace("\n}\n```", ",\n  \"" + field + "\": \"" + value + "\"\n}\n```");
    }

    private static String issue(final String id, final String state) {
        return "# "
                + id
                + " — One\n\n```json\n{\n  \"$schema\": \"issue-1.json\", \"id\": \""
                + id
                + "\", \"title\": \"One\",\n  \"state\": \""
                + state
                + "\", \"size\": \"S\", \"priority\": \"Medium\", \"rank\": 1,\n"
                + "  \"blockedBy\": [], \"covers\": \"Something.\"\n}\n```\n";
    }
}
