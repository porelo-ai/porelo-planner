package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

class CorrectionsTest {

    /** A store over one board, counting its saves; a save gives the corrected story its state. */
    private static final class CountingStore implements PlanningStore {

        Board board;

        final List<Writes> saves = new ArrayList<>();

        CountingStore(final Board board) {
            this.board = board;
        }

        @Override
        public Board board() {
            return board;
        }

        @Override
        public Board refresh() {
            return board;
        }

        /** A story stands where the least of its issues stands, in the workflow's order. */
        @Override
        public @org.jspecify.annotations.Nullable String storyState(final List<String> states) {
            return board.workflow().states().stream()
                    .filter(states::contains)
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public Board save(final Revision revision, final Writes writes) {
            saves.add(writes);
            final List<Story> stories = new ArrayList<>();
            for (final Story story : board.stories()) {
                final String state = writes.stories().get(story.id());
                stories.add(
                        state == null
                                ? story
                                : new Story(
                                        story.id(),
                                        story.title(),
                                        state,
                                        story.priority(),
                                        story.size(),
                                        story.issues(),
                                        story.description(),
                                        story.link()));
            }
            board =
                    CorrectionsTest.board(
                            new Revision("board@" + (saves.size() + 1)), stories, board.issues());
            return board;
        }
    }

    private static Story story(
            final String id, final String state, final List<String> issues, final Warning... w) {
        return new Story(id, "Story " + id, state, "High", null, issues, "", null)
                .withWarnings(List.of(w));
    }

    private static Board board(
            final Revision revision, final List<Story> stories, final List<Issue> issues) {
        final Board plain = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        return new Board(
                revision, plain.workflow(), stories, issues, plain.work(), plain.repositories());
    }

    /**
     * ST1 holds AA1 in Backlog and stands where its issues do; AA2 stands in a state none of the
     * workflow's, set right by a move, and carries the warnings given as well.
     */
    private static Board board(final Warning... more) {
        final List<Warning> aa2 = new ArrayList<>();
        aa2.add(
                Warning.stateOutsideWorkflow()
                        .offering(new Correction(Correction.MOVE, List.of("Backlog", "Review"))));
        aa2.addAll(List.of(more));
        return board(
                new Revision("board@1"),
                List.of(story("ST1", "Backlog", List.of("AA1"))),
                List.of(
                        WorkProposalTest.issue("AA1", "alpha", "Built", "Backlog", null, ""),
                        WorkProposalTest.issue("AA2", "alpha", "Parked", "Parked", null, "")
                                .withWarnings(aa2)));
    }

    private static Corrections.Asked asked(final String id, final String warning, final String to) {
        return new Corrections.Asked(true, id, warning, to);
    }

    private static final java.time.Clock CLOCK =
            java.time.Clock.fixed(
                    java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    /** A run with no work at hand, over the store. */
    private static WorkRun run(final CountingStore store) {
        return new WorkRun(store, new WorkRunTest.Memory(), CLOCK);
    }

    private static Corrections.Preview preview(
            final CountingStore store, final Corrections.Asked asked) {
        return run(store).correctionPreview(asked, "board@1");
    }

    @Test
    void anIssuesStateCorrectionIsAMoveCheckedAsAnyMoveAndNoCorrectionOfItsOwn() {
        // Harm: a second route that moves an issue would pass by the move's rules.
        final CountingStore store = new CountingStore(board());

        final Throwable refused =
                catchThrowable(
                        () ->
                                preview(
                                        store,
                                        new Corrections.Asked(
                                                false,
                                                "AA2",
                                                Warning.STATE_OUTSIDE_WORKFLOW,
                                                "Review")));
        assertThat(refused)
                .as("sent to the move")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("It is set right by a move of it, previewed and saved as any move.");
        assertThat(
                        Moves.check(
                                        store.board(),
                                        List.of(new Moves.Move(false, "AA2", "Review")),
                                        (issue, stories) -> false)
                                .writes())
                .as("the move writes its state alone")
                .isEqualTo(new Writes(Map.of("AA2", "Review"), Map.of()));
        assertThat(store.saves).as("nothing written").isEmpty();
    }

    @Test
    void anotherWarningHoldingTheItemLeavesTheCorrectionUnofferedAndRefusedAndHoldsStill() {
        // Harm: lifting a hold to correct one warning lets a hand edit of the parent, or a state
        // with it, slip past the work rules.
        final CountingStore store = new CountingStore(board(Warning.wrongParent()));

        final Corrections.Asked asked =
                new Corrections.Asked(false, "AA2", Warning.STATE_OUTSIDE_WORKFLOW, "Review");
        final Throwable refused = catchThrowable(() -> preview(store, asked));
        assertThat(refused)
                .as("refused with the other warning's words")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage(Warning.wrongParent().text());
        assertThat(((Moves.Refused) refused).name()).isEqualTo(Corrections.REFUSED);
        assertThat(store.saves).as("nothing written").isEmpty();
    }

    @Test
    void aWarningTheStoreOffersNoCorrectionForIsRefusedBeforeAnyWrite() {
        // Harm: a correction a store cannot carry out half happens, or is improvised outside its
        // writes.
        final CountingStore store =
                new CountingStore(
                        board(
                                new Revision("board@1"),
                                List.of(
                                        story(
                                                "ST1",
                                                "Backlog",
                                                List.of("AA1"),
                                                Warning.issueOutsideProject())),
                                board().issues()));

        assertThat(
                        catchThrowable(
                                () ->
                                        preview(
                                                store,
                                                asked(
                                                        "ST1",
                                                        Warning.ISSUE_OUTSIDE_PROJECT,
                                                        "Backlog"))))
                .as("not offered")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(PlanningStore.NOT_OFFERED);
        assertThat(store.saves).as("nothing written").isEmpty();
    }
}
