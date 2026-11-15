package org.fuin.porelo.planner.store.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.fuin.porelo.planner.store.StageResult;
import org.fuin.porelo.planner.store.git.stories.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A stage's result written into an issue's file, and nothing else of it changed. */
class ResultTextTest {

    private static final Workflow WORKFLOW =
            Workflow.read(
                    """
                    states:
                      - {name: Idea, closed: false}
                      - {name: Backlog, closed: false}
                      - {name: Review, closed: false}
                      - {name: Finished, closed: true}
                    phases:
                      - {name: Open, states: [Idea, Backlog]}
                      - {name: Build, states: [Review]}
                      - {name: Done, states: [Finished]}
                    """);

    /** A made-up issue id; the head schema asks for two digits at least. */
    private static final String ID = "AB01"; // not-a-citation

    private static final String HEAD =
            "# "
                    + ID
                    + " — An issue\n\n```json\n{\n  \"$schema\": \"issue-1.json\",\n"
                    + "  \"id\": \""
                    + ID
                    + "\",\n  \"title\": \"An issue\",\n"
                    + "  \"state\": \"Backlog\",\n  \"size\": \"S\",\n"
                    + "  \"priority\": \"Medium\",\n  \"rank\": 1,\n"
                    + "  \"blockedBy\": [],\n  \"covers\": \"Something.\"\n}\n```\n";

    private static final String ISSUE =
            HEAD
                    + "\n**Size:** S - a guess\nbefore it was read.\n\n"
                    + "**What must be true.** Something.\n\n"
                    + "## Why\n\nA reason.\n\n```\n## Plan\n```\n\n"
                    + "## Plan\n\n- Old plan.\n\n"
                    + "## Acceptance\n\n- Measured.\n\n"
                    + "## Open questions\n\n- None.\n\n"
                    + "## To be checked\n\n- Old check.\n";

    private static final String ANALYZED =
            HEAD.replace("\"Backlog\"", "\"Review\"").replace("\"size\": \"S\"", "\"size\": \"M\"")
                    + "\n**Size:** M - one module\n\n"
                    + "**What must be true.** Something.\n\n"
                    + "## Why\n\nA reason.\n\n```\n## Plan\n```\n\n"
                    + "## Plan\n\n- Read it.\n\n```\n# a shell comment\n```\n\n"
                    + "## Acceptance\n\n- Measured.\n\n"
                    + "## Open questions\n\n- None.\n\n"
                    + "## To be checked\n\n- Measure it.\n\n"
                    + "## Findings\n\n- Found one.\n";

    @Test
    void aResultRewritesTheHeadsStateAndSizeTheSizeLineThePlanAndToBeCheckedAndNothingElse() {
        // Prevents an issue's description or other sections from being lost by a save nobody
        // reviews.
        assertThat(ResultText.apply(ISSUE, analysis(List.of("Measure it."), null), WORKFLOW))
                .as("the issue with the result in it, every other line as it was")
                .isEqualTo(ANALYZED);
    }

    @Test
    void aFileWithCarriageReturnsKeepsThemInEveryLineTheResultWrites() {
        // Prevents a save from rewriting every line of a file kept with CRLF line endings.
        assertThat(
                        ResultText.apply(
                                ISSUE.replace("\n", "\r\n"),
                                analysis(List.of("Measure it."), null),
                                WORKFLOW))
                .as("the result in CRLF")
                .isEqualTo(ANALYZED.replace("\n", "\r\n"));
    }

    @Test
    void aFileMixingLineEndingsIsRefused() {
        // Prevents a save from changing the endings of lines it does not write.
        assertThatThrownBy(
                        () ->
                                ResultText.apply(
                                        ISSUE.replace("## Why\n", "## Why\r\n"),
                                        analysis(List.of(), null),
                                        WORKFLOW))
                .as("a file with both endings")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEstimateWritesItsLineRightAfterTheSizeLineAndReplacesItTheNextTime() {
        // Prevents a second estimate from standing beside the first, so a reader takes the old one.
        final String once = ResultText.apply(ISSUE, analysis(List.of(), 90), WORKFLOW);
        final String twice = ResultText.apply(once, analysis(List.of(), 45), WORKFLOW);

        assertThat(once)
                .as("the estimate after the size")
                .contains("\n**Size:** M - one module\n\n**Estimate:** 90 min\n\n**What must be");
        assertThat(twice)
                .as("one estimate, the new one")
                .contains("\n**Size:** M - one module\n\n**Estimate:** 45 min\n\n**What must be")
                .doesNotContain("90 min");
    }

    @Test
    void anEmptyToBeCheckedRemovesItsSection() {
        // Prevents a list of checks already done from staying as if still open.
        assertThat(ResultText.apply(ISSUE, analysis(List.of(), null), WORKFLOW))
                .as("without To be checked, Findings after Open questions")
                .doesNotContain("## To be checked")
                .doesNotContain("Old check")
                .endsWith("## Open questions\n\n- None.\n\n## Findings\n\n- Found one.\n");
    }

    @Test
    void aMissingSizeLinePlanAndToBeCheckedAreInsertedWhereTheSkeletonPutsThem() {
        // Prevents a result from being dropped, or put where no reader looks, in a sparse file.
        final String sparse =
                HEAD
                        + "\n**What must be true.** Something.\n\n"
                        + "## Acceptance\n\n- Measured.\n\n"
                        + "## Open questions\n\n- None.\n\n"
                        + "## Notes\n\nKept.\n";

        assertThat(ResultText.apply(sparse, analysis(List.of("Measure it."), null), WORKFLOW))
                .as("each part in its place, the rest untouched")
                .isEqualTo(
                        ANALYZED.substring(0, ANALYZED.indexOf("\n**Size:**"))
                                + "\n**What must be true.** Something.\n\n"
                                + "**Size:** M - one module\n\n"
                                + "## Plan\n\n- Read it.\n\n```\n# a shell comment\n```\n\n"
                                + "## Acceptance\n\n- Measured.\n\n"
                                + "## Open questions\n\n- None.\n\n"
                                + "## To be checked\n\n- Measure it.\n\n"
                                + "## Findings\n\n- Found one.\n\n"
                                + "## Notes\n\nKept.\n");
    }

    @Test
    void aStateTheWorkflowDoesNotKnowIsRefusedByTheHeadsOwnCheck() {
        // Prevents a head the work format refuses from being written and hiding the issue.
        assertThatThrownBy(
                        () ->
                                ResultText.apply(
                                        ISSUE,
                                        new StageResult.ForIssue(
                                                ID,
                                                "Backlog",
                                                "Nowhere",
                                                null,
                                                "M",
                                                "one module",
                                                null,
                                                List.of(),
                                                List.of()),
                                        WORKFLOW))
                .as("a state outside the workflow")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/state");
    }

    @Test
    void aResultLinksEveryBareNumberOfTheIndexAndLeavesLinksCodeAndOtherNumbersAsTheyAre()
            throws Exception {
        // Prevents a saved result that makes the store's own issue check warn on every board.
        final String other = "AB02"; // not-a-citation
        final String foreign = "CD07"; // not-a-citation
        final StageResult.ForIssue result =
                new StageResult.ForIssue(
                        ID,
                        "Backlog",
                        "Review",
                        "- Touch "
                                + ID
                                + " and "
                                + other
                                + ", see "
                                + link(other)
                                + " and `"
                                + ID
                                + "`.\n\n```\n"
                                + other
                                + " in a fence\n```\n\n- Not "
                                + foreign
                                + ".\n",
                        "M",
                        "one module",
                        null,
                        List.of("Ask about " + other + "."),
                        List.of(ID + " holds."));
        final String written =
                ResultText.apply(ISSUE, result, WORKFLOW, java.util.Set.of(ID, other));
        assertThat(written.lines().toList())
                .as("exactly the bare numbers of the index are links to their rows")
                .contains(
                        "- Touch "
                                + link(ID)
                                + " and "
                                + link(other)
                                + ", see "
                                + link(other)
                                + " and `"
                                + ID
                                + "`.",
                        other + " in a fence",
                        "- Not " + foreign + ".",
                        "- Ask about " + link(other) + ".",
                        "- " + link(ID) + " holds.");
        final String index =
                "## Idea\n| <a id=\""
                        + ID.toLowerCase(java.util.Locale.ROOT)
                        + "\"></a>["
                        + ID
                        + "]("
                        + ID
                        + "-An-issue.md) | an issue |\n| <a id=\""
                        + other.toLowerCase(java.util.Locale.ROOT)
                        + "\"></a>["
                        + other
                        + "]("
                        + other
                        + "-Other.md) | another |\n";
        final java.util.Map<String, String> pages = new java.util.LinkedHashMap<>();
        pages.put("issues/README.md", index);
        pages.put("issues/" + ID + "-An-issue.md", written);
        assertThat(
                        org.fuin.porelo.planner.store.git.stories.IssueLinks.check(
                                pages,
                                java.util.Map.of(
                                        "issues/README.md",
                                        org.fuin.porelo.planner.store.git.stories.IssueIndexes.read(
                                                index, "sample", WORKFLOW)),
                                path -> java.util.Optional.empty()))
                .as("the written file passes the store's issue check")
                .isEmpty();
    }

    private static String link(final String number) {
        return "[" + number + "](README.md#" + number.toLowerCase(java.util.Locale.ROOT) + ")";
    }

    private static StageResult.ForIssue analysis(
            final List<String> toBeChecked, final @Nullable Integer minutes) {
        return new StageResult.ForIssue(
                ID,
                "Backlog",
                "Review",
                "- Read it.\n\n```\n# a shell comment\n```\n",
                "M",
                "one module",
                minutes,
                toBeChecked,
                List.of("Found one."));
    }
}
