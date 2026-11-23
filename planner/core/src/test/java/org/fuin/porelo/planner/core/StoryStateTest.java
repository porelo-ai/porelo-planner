package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** An open story's state as its issues give it, the one rule every store uses. */
class StoryStateTest {

    /** Porelo's own workflow, its order and its measured states. */
    private static final Workflow PORELO =
            new Workflow(
                    List.of(
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
                            "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"),
                    List.of(
                            "Implementing",
                            "Reading",
                            "Reworking",
                            "Review",
                            "Finishing",
                            "Finished"));

    private static final StoryState RULE = StoryState.of(PORELO, List.of("Rejected")).orElseThrow();

    /** The window's mock board: Reworking comes after Review and Rework. */
    private static final Workflow MOCK =
            new Workflow(
                    List.of(
                            "New",
                            "Accepted",
                            "In analysis",
                            "Analyzed",
                            "Under estimation",
                            "Estimated",
                            "Backlog",
                            "Implementing",
                            "Reading",
                            "Review",
                            "Rework",
                            "Reworking",
                            "Finishing",
                            "Finished",
                            "Rejected"),
                    List.of(),
                    List.of("Finished", "Rejected"),
                    List.of(
                            "Implementing",
                            "Reading",
                            "Review",
                            "Reworking",
                            "Finishing",
                            "Finished"));

    private static final StoryState MOCK_RULE =
            StoryState.of(MOCK, List.of("Rejected")).orElseThrow();

    @Test
    void reworkingAfterReviewIsBuiltAsInTheLiveOrder() {
        // Harm: on the mock board a story whose issue is reworked shows in Backlog or in Review.
        assertThat(MOCK_RULE.review()).isEqualTo("Review");
        assertThat(MOCK_RULE.state(List.of("Reworking", "New"))).isEqualTo("Reworking");
        assertThat(MOCK_RULE.state(List.of("Reworking"))).isEqualTo("Reworking");
        assertThat(MOCK_RULE.state(List.of("Implementing", "Reworking", "Reading")))
                .isEqualTo("Reworking");
        assertThat(MOCK_RULE.state(List.of("Implementing", "Review"))).isEqualTo("Implementing");
        assertThat(MOCK_RULE.state(List.of("Review", "Rework", "Finishing", "Finished")))
                .as("Rework and Finishing are review or later")
                .isEqualTo("Review");
        assertThat(MOCK_RULE.state(List.of("Rework", "Estimated"))).isEqualTo("Backlog");
        assertThat(MOCK_RULE.behind("Reworking", List.of("R1", "W1"), List.of("Review", "Rework")))
                .as("issues in review or later stand behind no story")
                .isEmpty();
        assertThat(MOCK_RULE.behind("Reworking", List.of("I1"), List.of("Implementing")))
                .hasValueSatisfying(
                        warning ->
                                assertThat(warning.text())
                                        .isEqualTo(
                                                "Its issue I1 stands before its state"
                                                        + " Reworking."));
    }

    @Test
    void theLastOpenStateIsNeverBuilt() {
        // Harm: a story whose issues are all finishing shows as being built, not waiting for
        // review.
        assertThat(RULE.state(List.of("Finishing", "Implementing"))).isEqualTo("Implementing");
        assertThat(RULE.state(List.of("Finishing"))).isEqualTo("Review");
        assertThat(MOCK_RULE.state(List.of("Finishing"))).isEqualTo("Review");
    }

    @Test
    void theRuleFindsReviewAsTheLastMeasuredStateBeforeAnUnmeasuredOne() {
        // Harm: stories wait for review in a state the person does not review in.
        assertThat(RULE.review()).isEqualTo("Review");
    }

    @Test
    void anIssueBeingBuiltMakesItsStoryBuiltHoweverManyStillWait() {
        // Harm: one issue left in Idea holds back a story whose work runs, so the board shows it
        // waiting for an idea.
        assertThat(of(times(12, "Implementing"), times(29, "Review"), times(1, "Idea")))
                .isEqualTo("Implementing");
    }

    @Test
    void aStoryFollowsItsIssueIntoWorkStartedElsewhere() {
        // Harm: a story stays Analyzed beside its issue in Implementing.
        assertThat(of(List.of("Analyzed", "Implementing"))).isEqualTo("Implementing");
        assertThat(of(List.of("Implementing"))).isEqualTo("Implementing");
    }

    @Test
    void aStoryBeingBuiltStandsInTheFurthestStateOfItsIssuesBeingBuilt() {
        // Harm: a story whose issue is reworked shows as freshly implemented.
        assertThat(of(List.of("Implementing", "Reworking", "Reading", "Backlog")))
                .isEqualTo("Reworking");
    }

    @Test
    void aStoryWaitsInReviewWithEveryIssueInReviewLaterOrClosed() {
        // Harm: a story whose issues are all done is shown closed, or in an earlier state, before a
        // person closed it.
        assertThat(of(List.of("Finished", "Finished"))).isEqualTo("Review");
        assertThat(of(List.of("Review", "Rework", "Finishing", "Finished"))).isEqualTo("Review");
    }

    @Test
    void withNoIssueBeingBuiltAnIssueInReviewOrLaterCountsAsBacklog() {
        // Harm: a story with an idea not yet planned beside finished issues shows as an idea, or as
        // finished, where its finished part is planned work done.
        assertThat(of(times(5, "Finished"), times(1, "Idea"))).isEqualTo("Backlog");
        assertThat(of(times(5, "Finished"), times(1, "Analyzed"))).isEqualTo("Backlog");
        assertThat(of(List.of("Rework", "Estimated"))).isEqualTo("Backlog");
        assertThat(of(List.of("Analyzed", "Idea", "Analyzed"))).isEqualTo("Analyzed");
    }

    @Test
    void anIssueRejectedIsLeftOut() {
        // Harm: a rejected issue makes its story look done, or behind the issues that are planned.
        assertThat(of(List.of("Rejected", "Idea", "Analyzed"))).isEqualTo("Analyzed");
        assertThat(of(List.of("Rejected", "Rejected")))
                .as("no issue left: a story without issues, which the store decides")
                .isNull();
    }

    @Test
    void anIssueInAStateTheWorkflowLacksIsLeftOut() {
        // Harm: a state written by hand moves its story to a state the workflow does not have.
        assertThat(of(List.of("Nowhere", "Analyzed"))).isEqualTo("Analyzed");
        assertThat(of(List.of("Nowhere"))).isNull();
        assertThat(of(List.of())).isNull();
    }

    @Test
    void eachIssueBeforeTheStorysStateIsNamedInOneWarning() {
        // Harm: a story shown as built hides the issue still waiting in Idea, so nobody plans it.
        final List<String> states = List.of("Idea", "Implementing", "Review", "Analyzed");
        final String state = RULE.state(states);
        assertThat(state).isEqualTo("Implementing");
        final Warning warning =
                RULE.behind(state, List.of("A1", "A2", "A3", "A4"), states).orElseThrow();
        assertThat(warning.kind()).isEqualTo(Warning.ISSUE_BEHIND_STORY);
        assertThat(warning.text())
                .isEqualTo("Its issues A1 and A4 stand before its state Implementing.");
        assertThat(warning.concerns()).containsExactly(Warning.Concern.ISSUES);
    }

    @Test
    void anIssueCountedAsBacklogStandsBehindNoStory() {
        // Harm: a finished issue is warned about as lagging behind a story it is ahead of.
        final List<String> states = List.of("Finished", "Idea");
        final String state = RULE.state(states);
        assertThat(state).isEqualTo("Backlog");
        assertThat(RULE.behind(state, List.of("F1", "I1"), states).orElseThrow().text())
                .isEqualTo("Its issue I1 stands before its state Backlog.");
        assertThat(RULE.behind("Review", List.of("R1", "F1"), List.of("Review", "Finished")))
                .as("every issue in review or later")
                .isEmpty();
    }

    @Test
    void aWorkflowWithoutTheStatesTheRuleNeedsGivesNoRule() {
        // Harm: a workflow the rule cannot read gives stories a guessed state.
        final List<String> states = List.of("Idea", "Building", "Review", "Done");
        assertThat(StoryState.of(new Workflow(states, List.of(), List.of("Done")), List.of()))
                .as("nothing measured")
                .isEmpty();
        assertThat(
                        StoryState.of(
                                new Workflow(
                                        states,
                                        List.of(),
                                        List.of("Done"),
                                        List.of("Idea", "Building")),
                                List.of()))
                .as("the first measured state is the first state, with none before it")
                .isEmpty();
        assertThat(
                        StoryState.of(
                                new Workflow(
                                        states,
                                        List.of(),
                                        List.of("Idea", "Done"),
                                        List.of("Building")),
                                List.of()))
                .as("the state before the first measured open state is closed")
                .isEmpty();
    }

    @Test
    void reviewIsTheMeasuredOpenStateDirectlyFollowedByAnUnmeasuredOpenOne() {
        // Harm: a story waits for review in a state its work has not reached, or past it.
        assertThat(
                        StoryState.of(
                                        new Workflow(
                                                List.of(
                                                        "Idea",
                                                        "Building",
                                                        "Review",
                                                        "Rework",
                                                        "Done"),
                                                List.of(),
                                                List.of("Done"),
                                                List.of("Building", "Review", "Done")),
                                        List.of())
                                .map(StoryState::review))
                .as("up to an open state that is not measured")
                .hasValue("Review");
        assertThat(
                        StoryState.of(
                                        new Workflow(
                                                List.of("Idea", "Building", "Review", "Done"),
                                                List.of(),
                                                List.of("Done"),
                                                List.of("Building", "Review", "Done")),
                                        List.of())
                                .map(StoryState::review))
                .as("R needs an unmeasured open state after it, a closed one is not enough")
                .isEmpty();
    }

    @SafeVarargs
    private static @Nullable String of(final List<String>... groups) {
        final List<String> states = new ArrayList<>();
        for (final List<String> group : groups) states.addAll(group);
        return RULE.state(states);
    }

    private static List<String> times(final int count, final String state) {
        return Collections.nCopies(count, state);
    }
}
