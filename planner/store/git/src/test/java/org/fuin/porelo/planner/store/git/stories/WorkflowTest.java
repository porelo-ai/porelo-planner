package org.fuin.porelo.planner.store.git.stories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards the shared state contract and computed progress against silent fallback. */
class WorkflowTest {
    @TempDir Path root;

    private Path stories(String workflow, String state, String issues) throws Exception {
        final Path dir = Files.createDirectories(root.resolve("stories"));
        Files.writeString(dir.resolve("workflow.yml"), workflow);
        Files.writeString(
                root.resolve("project.yml"),
                "project:\n  central: git@github.com:porelo-ai/porelo-project.git\nrepositories: {}\n");
        Files.writeString(
                dir.resolve("ST1.md"),
                "# ST1 — Sample\n\n## Data\n\n```json\n{\"id\":\"ST1\",\"title\":\"Sample\",\"state\":\""
                        + state
                        + "\",\"priority\":\"High\",\"gain\":\"Gain\",\"test\":\"Test\",\"issues\":"
                        + issues
                        + ",\"tested\":null}\n```\n");
        return dir;
    }

    static String workflow() {
        return """
                states:
                  - {name: Idea, closed: false}
                  - {name: Rework, closed: false}
                  - {name: Reworking, closed: false, measured: true}
                  - {name: Finished, closed: true}
                  - {name: Rejected, closed: true}
                phases:
                  - {name: Early, states: [Idea]}
                  - {name: Working, states: [Rework, Reworking]}
                  - {name: Done, states: [Finished, Rejected]}
                """;
    }

    // Rejected must never be mistaken for completed work across module boundaries.
    // Rejected work must not require the tested date reserved for completed work.
    @Test
    void rejectedStoryNeedsNoTestedDate() throws Exception {
        final Path dir = stories(workflow(), "Rejected", "[]");
        assertThat(StoriesIndex.write(dir, repo -> "# Issues\n").faults()).isEmpty();
        assertThat(Files.readString(dir.resolve("ST1.md"))).contains("**State:** Rejected");
    }

    @Test
    void rejectionIsTheSharedExceptionToDoneWork() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow());
        final Workflow workflow = Workflow.read(file);
        for (final String state : workflow.states()) {
            assertThat(workflow.rejected(state)).isEqualTo(state.equals("Rejected"));
        }
    }

    // Prose must not make valid indexes unusable or create issue rows from examples.
    @Test
    void proseHeadingsDoNotBecomeStates() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow());
        final String index =
                """
                # Issues
                ## How to read one
                A description.
                ```markdown
                | # | Size | What it covers |
                ```
                ## Where the rest of the open work lives
                ## Idea
                | # | Size | Blocked by | What it covers |
                | <a id="ab01"></a>AB01 | S | - | Real work |
                ## What was decided along the way
                | <a id="ab02"></a>AB02 | An example without an issue table header |
                """;
        assertThat(IssueIndexes.read(index, "sample", Workflow.read(file)).keySet())
                .containsExactly("AB01");
    }

    // An empty table must not let an invalid workflow vocabulary pass validation.
    @Test
    void unknownHeadingWithAnEmptyIssueTableIsRefused() throws Exception {
        refusesUnknownTable("");
    }

    @Test
    void unknownHeadingWithIssueRowsIsRefused() throws Exception {
        refusesUnknownTable("| <a id=\"ab01\"></a>AB01 | S | - | Work |\n");
    }

    private void refusesUnknownTable(String rows) throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow());
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        "# Issues\n\n## Alien\nA description before the table.\n\n"
                                                + "| # | Size | Blocked by | What it covers |\n"
                                                + "|---|---|---|---|\n"
                                                + rows,
                                        "sample",
                                        Workflow.read(file)))
                .hasMessageContaining("issues/README.md:3")
                .hasMessageContaining("Alien")
                .hasMessageContaining(file.toString());
    }

    // A workflow state can reserve a section before any issue has entered it.
    @Test
    void knownStateHeadingWithoutATableIsAllowed() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow());
        assertThat(IssueIndexes.read("# Issues\n\n## Idea\n", "sample", Workflow.read(file)))
                .isEmpty();
    }

    @Test
    void addedStateGetsItsSection() throws Exception {
        final Path dir = stories(workflow(), "Reworking", "[]");
        Files.writeString(
                dir.resolve("ST2.md"),
                Files.readString(dir.resolve("ST1.md"))
                        .replace("ST1", "ST2")
                        .replace("Reworking", "Rework"));
        Files.writeString(
                dir.resolve("ST3.md"),
                Files.readString(dir.resolve("ST1.md"))
                        .replace("ST1", "ST3")
                        .replace("Reworking", "Finished")
                        .replace("\"tested\":null", "\"tested\":\"2026-01-01\""));
        assertThat(StoriesIndex.write(dir, repo -> "# Issues\n").faults()).isEmpty();
        assertThat(Files.readString(dir.resolve("README.md")))
                .containsSubsequence("## Rework\n", "## Reworking\n", "## Finished\n");
    }

    @Test
    void refusesAStateTheFileLacks() throws Exception {
        final Path dir =
                stories(
                        workflow()
                                .replace("name: Idea", "name: Proposed")
                                .replace("[Idea]", "[Proposed]"),
                        "Idea",
                        "[]");
        assertThat(StoriesIndex.write(dir, repo -> "# Issues\n").faults())
                .anyMatch(fault -> fault.contains("Idea") && fault.contains("workflow.yml"));
        assertThat(dir.resolve("README.md")).doesNotExist();
    }

    @Test
    void missingFileIsRefused() throws Exception {
        final Path dir = stories(workflow(), "Idea", "[]");
        Files.delete(dir.resolve("workflow.yml"));
        assertThatThrownBy(() -> StoriesIndex.write(dir, repo -> "# Issues\n"))
                .hasMessageContaining(dir.resolve("workflow.yml").toString());
    }

    @Test
    void storyStateIsComputedFromItsIssues() throws Exception {
        final Path dir = stories(workflow(), "Idea", "[\"porelo-project#PJ01\"]");
        assertThat(
                        StoriesIndex.write(
                                        dir,
                                        repo ->
                                                "## Rework\n| <a id=\"pj01\"></a>PJ01 | S | - | Sample |\n")
                                .faults())
                .isEmpty();
        assertThat(Files.readString(dir.resolve("ST1.md")))
                .contains("**State:** Rework")
                .doesNotContain("\"state\"");
    }

    // Prevents a misspelled phase from silently losing progress on the board.
    @Test
    void refusesAPhaseNamingAnUnknownState() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow().replace("[Idea]", "[Unknown]"));
        assertThatThrownBy(() -> Workflow.read(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining(file + ":8:")
                .hasMessageContaining("Unknown");
    }

    // Prevents ambiguous configuration from assigning different meanings to the same state.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "unknown-root",
                "unknown-state-key",
                "unknown-phase-key",
                "duplicate-key",
                "duplicate-state",
                "duplicate-phase",
                "duplicate-member",
                "two-phases",
                "empty-name",
                "multiline-name",
                "folded-name",
                "boolean",
                "two-documents",
                "empty-states"
            })
    void refusesMalformedWorkflowsWithFileAndLine(String defect) throws Exception {
        String yaml = workflow();
        yaml =
                switch (defect) {
                    case "unknown-root" -> yaml + "extra: false\n";
                    case "unknown-state-key" ->
                            yaml.replace("name: Idea", "extra: true, name: Idea");
                    case "unknown-phase-key" ->
                            yaml.replace("name: Early", "extra: true, name: Early");
                    case "duplicate-key" -> yaml.replace("name: Idea", "name: Idea, name: Idea");
                    case "duplicate-state" -> yaml.replace("name: Rework,", "name: Idea,");
                    case "duplicate-phase" -> yaml.replace("name: Working", "name: Early");
                    case "duplicate-member" -> yaml.replace("[Idea]", "[Idea, Idea]");
                    case "two-phases" ->
                            yaml.replace("[Rework, Reworking]", "[Idea, Rework, Reworking]");
                    case "empty-name" -> yaml.replace("name: Idea", "name: ''");
                    case "multiline-name" -> yaml.replace("name: Idea", "name: \"one\\ntwo\"");
                    case "folded-name" ->
                            yaml.replace(
                                    "  - {name: Idea, closed: false}",
                                    "  - name: >-\n      one\n      two\n    closed: false");
                    case "boolean" -> yaml.replace("closed: false", "closed: 'false'");
                    case "two-documents" -> yaml + "---\nstates: []\nphases: []\n";
                    case "empty-states" -> "states: []\nphases: []\n";
                    default -> throw new AssertionError(defect);
                };
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, yaml);
        assertThatThrownBy(() -> Workflow.read(file))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageMatching(java.util.regex.Pattern.quote(file.toString()) + ":[0-9]+:.*");
    }

    @Test
    void readsFlagsOrderAndImmutablePhases() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, workflow());
        final Workflow workflow = Workflow.read(file);
        assertThat(workflow.states())
                .containsExactly("Idea", "Rework", "Reworking", "Finished", "Rejected");
        assertThat(workflow.order("Reworking")).isEqualTo(2);
        assertThat(workflow.measured("Idea")).isFalse();
        assertThat(workflow.measured("Reworking")).isTrue();
        assertThat(workflow.closed("Finished")).isTrue();
        assertThat(workflow.phases().get(1).states()).containsExactly("Rework", "Reworking");
        assertThatThrownBy(() -> workflow.states().add("Other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> workflow.phases().get(1).states().add("Other"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> workflow.order("Unknown")).hasMessageContaining(file.toString());
    }

    // Prevents closed issues from holding an open story back, and rejection from counting as
    // completion.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "Rework;Reworking,Rework",
        "Reworking;Rework,Rework",
        "Finished;Reworking,Reworking",
        "Rejected;Rework,Rework",
        "Finished;Rejected,Finished",
        "Finished;Finished,Finished",
        "Rejected;Rejected,Rejected"
    })
    void computesEveryCombinationOfOpenClosedAndRejectedIssues(String states, String expected)
            throws Exception {
        final Path dir =
                stories(workflow(), "Idea", "[\"porelo-project#PJ01\",\"porelo-project#PJ02\"]");
        final String[] values = states.split(";");
        final IssueSource source =
                repo ->
                        "## "
                                + values[0]
                                + "\n| PJ01 | S | - | First |\n## "
                                + values[1]
                                + "\n| PJ02 | S | - | Second |\n";
        final StoriesIndex.Result result = StoriesIndex.write(dir, source);
        assertThat(result.faults()).isEmpty();
        assertThat(Files.readString(dir.resolve("ST1.md")))
                .contains("**State:** " + expected)
                .contains("\"$schema\": \"story-2.json\"")
                .doesNotContain("\"state\"");
        assertThat(result.notes())
                .hasSize(1)
                .first()
                .asString()
                .contains("NOTE", "ST1", "Idea", expected);
        assertThat(StoriesIndex.write(dir, source).notes()).isEmpty();
        assertThat(StoriesIndex.check(dir, source).faults()).isEmpty();
    }

    // Prevents a state given in a new head being overruled by its issues, or refused beside them.
    @Test
    void keepsAStateGivenBesideIssuesInANewHeadAndWarns() throws Exception {
        final Path dir = stories(workflow(), "Idea", "[\"porelo-project#PJ01\"]");
        final Path story = dir.resolve("ST1.md");
        Files.writeString(
                story,
                Files.readString(story).replace("{\"id\"", "{\"$schema\":\"story-2.json\",\"id\""));
        final StoriesIndex.Result result =
                StoriesIndex.write(dir, repo -> "## Rework\n| PJ01 | S | - | Sample |\n");
        assertThat(result.faults()).isEmpty();
        assertThat(result.notes())
                .containsExactly(
                        "WARN  ST1: stated Idea, its issues give Rework (porelo-project#PJ01 Rework)");
        assertThat(Files.readString(story)).contains("**State:** Idea").contains("\"state\"");
    }

    // Prevents new workflow spellings from disappearing between issue parsing and story grouping.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"In analysis", "Under estimation", "Reworking", "Finishing"})
    void computesNewStatesWithoutARelease(String state) throws Exception {
        String yaml = workflow();
        if (!state.equals("Reworking"))
            yaml =
                    yaml.replace(
                            "states:\n", "states:\n  - {name: '" + state + "', closed: false}\n");
        final Path dir = stories(yaml, "Idea", "[\"porelo-project#PJ01\"]");
        final IssueSource source = repo -> "## " + state + "\n| PJ01 | S | - | Sample |\n";
        assertThat(StoriesIndex.write(dir, source).faults()).isEmpty();
        assertThat(Files.readString(dir.resolve("README.md"))).contains("## " + state + "\n");
    }

    // Prevents the shared workflow's comments or new states from making callers use a fallback.
    @Test
    void readsTheSettledWorkflowWithComments() throws Exception {
        final Path file =
                Path.of("src/test/resources/checkouts/porelo-project/stories/workflow.yml");
        final Workflow workflow = Workflow.read(file);
        assertThat(workflow.states())
                .containsExactly(
                        "Idea",
                        "Accepted",
                        "In analysis",
                        "Analyzed",
                        "Under estimation",
                        "Estimated",
                        "Backlog",
                        "Implementing",
                        "Reading",
                        "Reworking",
                        "Review",
                        "Rework",
                        "Finishing",
                        "Finished",
                        "Rejected");
        assertThat(workflow.phases().stream().map(Workflow.Phase::name))
                .containsExactly("Ideas", "Analysis", "Estimation", "Backlog", "Build", "Done");
        assertThat(workflow.states().stream().filter(workflow::measured))
                .containsExactly(
                        "Implementing", "Reading", "Reworking", "Review", "Finishing", "Finished");
        final Path inline = root.resolve("inline.yml");
        Files.writeString(
                inline,
                "# leading comment\nstates: # states comment\n  - {name: Idea, closed: false} # state comment\nphases: [] # phases comment\n");
        assertThat(Workflow.read(inline).states()).containsExactly("Idea");
    }

    // Prevents any configured state from being skipped or grouped in the old built-in order.
    @Test
    void everySettledStateGetsItsSectionInBoardOrder() throws Exception {
        final String yaml =
                Files.readString(
                        Path.of(
                                "src/test/resources/checkouts/porelo-project/stories/workflow.yml"));
        final Path dir = stories(yaml, "Idea", "[]");
        Files.delete(dir.resolve("ST1.md"));
        final Workflow workflow = Workflow.read(dir.resolve("workflow.yml"));
        final StringBuilder index = new StringBuilder();
        int number = 10;
        for (final String state : workflow.states().reversed()) {
            final String id = "ST" + number;
            Files.writeString(
                    dir.resolve(id + ".md"),
                    "# "
                            + id
                            + " — Sample\n\n## Data\n\n```json\n{\"$schema\":\"story-2.json\",\"id\":\""
                            + id
                            + "\",\"title\":\"Sample\",\"priority\":\"High\",\"gain\":\"Gain\",\"test\":\"Test\",\"issues\":[\"porelo-project#PJ"
                            + number
                            + "\"],\"tested\":null}\n```\n");
            index.append("## ")
                    .append(state)
                    .append("\n| PJ")
                    .append(number++)
                    .append(" | S | - | Sample |\n");
        }
        assertThat(StoriesIndex.write(dir, repository -> index.toString()).faults()).isEmpty();
        assertThat(
                        Files.readString(dir.resolve("README.md"))
                                .lines()
                                .filter(line -> line.startsWith("## "))
                                .map(line -> line.substring(3))
                                .toList())
                .containsExactlyElementsOf(workflow.states());
    }

    private Workflow sectionWorkflow() throws Exception {
        final Path file = root.resolve("workflow.yml");
        Files.writeString(file, WorkflowTest.workflow());
        return Workflow.read(file);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void everyAtxLevelReadsKnownStatesAndRefusesUnknownTables(int level) throws Exception {
        final Workflow workflow = sectionWorkflow();
        final String prefix = "#".repeat(level);
        final String table =
                "\n| # | Size | Blocked by | What it covers |\n| <a id=\"ab01\"></a>AB01 | S | - | Work |\n";
        assertThat(IssueIndexes.read(prefix + " Idea" + table, "sample", workflow).keySet())
                .containsExactly("AB01");
        assertThat(IssueIndexes.read(prefix + " Idea\n", "sample", workflow)).isEmpty();
        assertThatThrownBy(() -> IssueIndexes.read(prefix + " Alien" + table, "sample", workflow))
                .hasMessageContaining("issues/README.md:1")
                .hasMessageContaining("Alien")
                .hasMessageContaining(root.resolve("workflow.yml").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"#", "##", "######", "##   ##", "# Alien", "# Issues"})
    void invalidHeadingsCannotHideAnEmptyTable(String heading) throws Exception {
        final Workflow workflow = sectionWorkflow();
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        heading + "\n\n| # | Size | Title |\n|---|---|---|\n",
                                        "sample",
                                        workflow))
                .hasMessageContaining("issues/README.md:1")
                .hasMessageContaining(root.resolve("workflow.yml").toString());
    }

    @Test
    void anIssueTableWithoutAnyHeadingIsRefused() throws Exception {
        final Workflow workflow = sectionWorkflow();
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        "| # | Size | Title |\n| AB01 | S | Work |\n",
                                        "sample",
                                        workflow))
                .hasMessageContaining("issues/README.md:1")
                .hasMessageContaining("heading")
                .hasMessageContaining(root.resolve("workflow.yml").toString());
    }

    @Test
    void blankSeparatedProseEndsTheHeadingsTableScope() throws Exception {
        final Workflow workflow = sectionWorkflow();
        assertThat(IssueIndexes.read("# Issues\n\nHow to read this index.\n", "sample", workflow))
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                IssueIndexes.read(
                                        "# Issues\n\nHow to read this index.\n\n| # | Size | Title |\n| AB01 | S | Work |\n",
                                        "sample",
                                        workflow))
                .hasMessageContaining("issues/README.md:5")
                .hasMessageContaining("heading")
                .hasMessageContaining(root.resolve("workflow.yml").toString());
    }
}
