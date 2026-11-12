package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The issues of a story that do not fit the state given to it. */
class MisfitsTest {

    private static final List<String> STATES =
            List.of("Backlog", "Implementing", "Review", "Finished", "Rejected");

    private static final List<String> CLOSED = List.of("Finished", "Rejected");

    /** The stories index's rule: the earliest open state, else the earliest closed. */
    private static final Moves.StoryState DERIVE = MisfitsTest::derive;

    private static final List<Issue> ISSUES =
            List.of(
                    issue("PL1", "Implementing"),
                    issue("PL2", "Review"),
                    issue("PL3", "Finished"),
                    issue("PL4", "Finished"),
                    issue("PL5", "Backlog"));

    @Test
    void aGivenStateItsIssuesDoNotGiveNamesEachIssueStandingElsewhere() {
        // Harm: a story shown as fitting while its issues stand elsewhere is taken as done.
        assertThat(misfits(story("Review", true, "PL1", "PL2")))
                .as("Review given over Implementing and Review: PL1 does not fit")
                .containsExactly("PL1");
    }

    @Test
    void aGivenStoryWithoutIssuesHasNoMisfits() {
        // Harm: a story the person placed by hand, with nothing under it, marked as wrong.
        assertThat(misfits(story("Review", true))).as("no issue, no misfit").isEmpty();
    }

    @Test
    void aGivenStateItsIssuesDeriveHasNoMisfitsThoughSingleIssuesStandElsewhere() {
        // Harm: a given state that the rule itself gives shown as standing against its issues.
        assertThat(misfits(story("Implementing", true, "PL1", "PL2")))
                .as("Implementing given over Implementing and Review is what the rule gives")
                .isEmpty();
    }

    @Test
    void aGivenOpenStateOverOnlyClosedIssuesMakesEachAMisfit() {
        // Harm: a story kept in Review while all its issues are finished, never seen as done.
        assertThat(misfits(story("Review", true, "PL3", "PL4")))
                .as("each Finished issue stands outside Review")
                .containsExactly("PL3", "PL4");
    }

    @Test
    void theMisfitsComeInTheStorysIssueOrder() {
        // Harm: a tooltip whose order differs from the story's, read against the wrong issue.
        assertThat(misfits(story("Review", true, "PL5", "PL3", "PL2", "PL1")))
                .as("in the story's order, PL2 fitting")
                .containsExactly("PL5", "PL3", "PL1");
    }

    @Test
    void anIssueTheBoardDoesNotHoldIsNeitherReadNorAMisfit() {
        // Harm: an issue of another repository blamed, or read as a state, for a story it does
        // not shape.
        assertThat(misfits(story("Implementing", true, "PL1", "XX9")))
                .as("XX9 is not on the board, PL1 alone gives Implementing")
                .isEmpty();
        assertThat(misfits(story("Review", true, "XX9")))
                .as("a story none of whose issues the board holds has none")
                .isEmpty();
    }

    @Test
    void aRuleThatCannotAnswerLeavesTheMisfitsUnknownAndTheBoardReadable() {
        // Harm: one given story whose rule throws making the whole board unreadable.
        final Story story = story("Review", true, "PL1");
        final Moves.StoryState failing =
                states -> {
                    throw new IllegalStateException("No board was read yet");
                };
        assertThat(Misfits.of(board(story), story, failing))
                .as("unknown, not an exception")
                .isNull();
    }

    private static List<String> misfits(final Story story) {
        return Misfits.of(board(story), story, DERIVE);
    }

    private static Board board(final Story story) {
        final Board board =
                new Board(
                        new Revision("r1"),
                        new Workflow(STATES, List.of(), CLOSED),
                        List.of(story),
                        ISSUES,
                        new Work(Map.of(), List.of()));
        return board;
    }

    private static @Nullable String derive(final List<String> states) {
        final List<String> open = states.stream().filter(state -> !CLOSED.contains(state)).toList();
        final List<String> candidates = open.isEmpty() ? states : open;
        return candidates.stream()
                .min((a, b) -> STATES.indexOf(a) - STATES.indexOf(b))
                .orElse(null);
    }

    private static Story story(final String state, final boolean given, final String... issues) {
        return new Story("ST1", "A story", state, given, "High", null, List.of(issues), "", null);
    }

    private static Issue issue(final String id, final String state) {
        return new Issue(id, "porelo-planner", "An issue", state, "S", List.of(), null, null, "");
    }
}
