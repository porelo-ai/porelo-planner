package org.fuin.porelo.planner.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The checks a stage's result passes before any store writes it into an issue. */
class StageResultTest {

    // Prevents a plan from opening a section of its own and so overwriting the issue's others.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Steps.\n## Acceptance\n- forged",
                "# Title",
                "##",
                "Steps.\n```\nopen code block",
                "Steps.\rhidden",
                " ## Acceptance\n- forged",
                "- step\n  ## Acceptance",
                " ```\n- step",
                "```java\ncode\n```still open",
                "````\ncode\n```",
                "~~~\ncode",
                "```\ncode\n~~~"
            })
    void aPlanThatWouldOpenASectionOrLeaveACodeBlockOpenIsRefused(final String plan) {
        assertThatThrownBy(() -> issue(plan, List.of(), List.of(), "Why."))
                .as("the plan %s", plan)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPlanWithAHeadingInsideACodeBlockOrADeeperHeadingIsTaken() {
        // Prevents a plan quoting a shell comment or a sub-heading from being refused for nothing.
        final String plan =
                "Steps.\n\n### Detail\n\n```\n## not a heading\n```\n\n```java\nint x;\n```\n";

        assertThat(issue(plan, List.of(), List.of(), "Why.").plan())
                .as("the plan as given")
                .isEqualTo(plan);
    }

    // Prevents a list item or the size's reason from breaking out of its line into a section.
    @Test
    void anItemOrAReasonThatIsNotOneLineOrReadsAsAHeadingIsRefused() {
        assertThatThrownBy(() -> issue(null, List.of("one\n## Two"), List.of(), "Why."))
                .as("a line to be checked with a break")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(null, List.of(), List.of("# Heading"), "Why."))
                .as("a finding that starts with #")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(null, List.of(), List.of(), "Why.\n## Plan"))
                .as("a reason with a break")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Prevents a head written with a size or minutes the work format does not take.
    @Test
    void aSizeNoStageGivesAndMinutesOutOfBoundsAreRefused() {
        assertThatThrownBy(() -> sized("-", null))
                .as("a dash")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sized("\"XL", null))
                .as("a quote")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sized("M", 0))
                .as("no minutes")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sized("M", StageResult.MOST_MINUTES + 1))
                .as("too many minutes")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new StageResult(
                                        WorkRule.Result.ESTIMATE,
                                        List.of(issue(null, List.of(), List.of(), "Why."))))
                .as("an estimate without minutes")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StageResult.ForIssue sized(final String size, final @Nullable Integer minutes) {
        return new StageResult.ForIssue(
                "AB1",
                "In analysis",
                "Analyzed",
                null,
                size,
                "Why.",
                minutes,
                List.of(),
                List.of());
    }

    // Prevents a readiness check saved with a size and plan it never gave, an analysis moving an
    // issue back, one issue moved twice, or a reason a store refuses only after others are written.
    @Test
    void aReadinessCheckGivesVerdictsAloneEachOnceWithAReasonAStoreKeeps() {
        final StageResult.Verdict ready = verdict("AB1", null);
        final StageResult.Verdict back = verdict("AB2", "Not ready: it lacks a plan. None.");

        assertThat(
                        new StageResult(WorkRule.Result.READINESS, List.of(), List.of(ready, back))
                                .ids())
                .as("the ids of the verdicts")
                .containsExactly("AB1", "AB2");
        assertThatThrownBy(
                        () ->
                                new StageResult(
                                        WorkRule.Result.READINESS,
                                        List.of(issue(null, List.of(), List.of(), "Why.")),
                                        List.of(ready)))
                .as("a readiness check with an issue's result")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new StageResult(
                                        WorkRule.Result.ANALYSIS,
                                        List.of(issue(null, List.of(), List.of(), "Why.")),
                                        List.of(verdict("AB2", null))))
                .as("an analysis with a verdict")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new StageResult(
                                        WorkRule.Result.READINESS,
                                        List.of(),
                                        List.of(ready, ready)))
                .as("one issue twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StageResult(WorkRule.Result.READINESS, List.of(), List.of()))
                .as("no issue")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verdict("AB1", "r".repeat(281)))
                .as("a reason over its bound")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> verdict("AB1", "Odd \u202E sign."))
                .as("a reason with a direction character")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StageResult.Verdict verdict(final String id, final @Nullable String reason) {
        return new StageResult.Verdict(
                id, "Preparing", reason == null ? "Backlog" : "Estimated", reason);
    }

    private static StageResult.ForIssue issue(
            final @Nullable String plan,
            final List<String> toBeChecked,
            final List<String> findings,
            final String sizeWhy) {
        return new StageResult.ForIssue(
                "AB1", "In analysis", "Analyzed", plan, "M", sizeWhy, null, toBeChecked, findings);
    }
}
