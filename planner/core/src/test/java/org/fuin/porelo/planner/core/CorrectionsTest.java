package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Correction;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Warning;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

class CorrectionsTest {

    private static final String REPOSITORY = "https://git.example.org/project";

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
                                        true,
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

    /** The workflow's open states no work alone enters: Building is entered by work. */
    private static Correction stated() {
        return new Correction(Correction.STATE_STATED, List.of("Backlog", "Review"));
    }

    private static Story story(
            final String id, final String state, final List<String> issues, final Warning... w) {
        return new Story(id, "Story " + id, state, !state.isEmpty(), "High", null, issues, "", null)
                .withWarnings(List.of(w));
    }

    private static Board board(
            final Revision revision, final List<Story> stories, final List<Issue> issues) {
        final Board plain = WorkProposalTest.board(WorkRule.Per.REPOSITORY);
        return new Board(
                revision, plain.workflow(), stories, issues, plain.work(), plain.repositories());
    }

    /**
     * ST1 states a state none of the workflow's and holds AA1 in Backlog; ST2 tells no state; AA2
     * stands in a state none of the workflow's.
     */
    private static Board board(final Warning... more) {
        final List<Warning> st1 = new ArrayList<>();
        st1.add(Warning.stateOutsideWorkflow().offering(stated()));
        st1.addAll(List.of(more));
        return board(
                new Revision("board@1"),
                List.of(
                        story("ST1", "Parked", List.of("AA1"), st1.toArray(Warning[]::new)),
                        story("ST2", "", List.of(), Warning.noState().offering(stated()))),
                List.of(
                        WorkProposalTest.issue("AA1", "alpha", "Built", "Backlog", null, ""),
                        WorkProposalTest.issue("AA2", "alpha", "Parked", "Parked", null, "")
                                .withWarnings(
                                        List.of(
                                                Warning.stateOutsideWorkflow()
                                                        .offering(
                                                                new Correction(
                                                                        Correction.MOVE,
                                                                        List.of(
                                                                                "Backlog",
                                                                                "Review")))))));
    }

    private static Corrections.Asked asked(final String id, final String warning, final String to) {
        return new Corrections.Asked(true, id, warning, to);
    }

    private static Board save(
            final CountingStore store, final Corrections.Asked asked, final String digest) {
        return Corrections.save(store, asked, "board@1", digest, REPOSITORY, null);
    }

    private static String digest(final CountingStore store, final Corrections.Asked asked) {
        return Corrections.preview(store, asked, "board@1").digest(REPOSITORY, null);
    }

    @Test
    void aStorysStateCorrectionWritesItsStatedStateAloneAndMovesNoneOfItsIssues() {
        // Harm: correcting a hand-edited story state carries its issues along, changing states
        // nothing was wrong with.
        final CountingStore store = new CountingStore(board());
        final Corrections.Asked asked = asked("ST1", Warning.STATE_OUTSIDE_WORKFLOW, "Review");

        final Corrections.Preview preview = Corrections.preview(store, asked, "board@1");
        assertThat(preview.from()).as("the state it stands in now").isEqualTo("Parked");
        assertThat(preview.writes())
                .as("only the story's stated state")
                .isEqualTo(new Writes(Map.of(), Map.of("ST1", "Review"), List.of()));

        final Board saved = save(store, asked, preview.digest(REPOSITORY, null));

        assertThat(store.saves).as("one save of that write").containsExactly(preview.writes());
        assertThat(saved.issue("AA1").orElseThrow().state())
                .as("its issue stays where it stood")
                .isEqualTo("Backlog");
        assertThat(saved.story("ST1").orElseThrow().warnings()).as("the warning is gone").isEmpty();
    }

    @Test
    void aStoryWithoutAStateIsGivenOnlyAStateTheCorrectionOffersAndNeverAnEmptyOne() {
        // Harm: an empty or working state written as the correction leaves the story without a
        // state still, or starts no work where the board says it runs.
        final CountingStore store = new CountingStore(board());

        for (final String to : List.of("", "Building", "Finished", "Parked")) {
            final Throwable refused =
                    catchThrowable(() -> digest(store, asked("ST2", Warning.NO_STATE, to)));
            assertThat(refused)
                    .as("'%s' is refused", to)
                    .isInstanceOf(Moves.Refused.class)
                    .hasMessage("The state is none of those the correction offers.");
            assertThat(((Moves.Refused) refused).name()).isEqualTo(Corrections.REFUSED);
        }
        assertThat(
                        Corrections.preview(
                                        store, asked("ST2", Warning.NO_STATE, "Backlog"), "board@1")
                                .writes()
                                .stories())
                .as("an offered state is written")
                .containsExactly(Map.entry("ST2", "Backlog"));
        assertThat(store.saves).as("nothing written").isEmpty();
    }

    @Test
    void theStatesOfAStateCorrectionAreTheOpenOnesNoWorkAloneEnters() {
        // Harm: a correction into a closed state drops the item's file with the person's text, and
        // one into a working state stands where only work may put it.
        final Board board = board();

        assertThat(Correction.openStates(board.workflow(), board.work().rules().keySet()))
                .as("Backlog and Review, never Building nor Finished")
                .containsExactly("Backlog", "Review");
        assertThat(Correction.ofState(false, board.workflow(), board.work().rules().keySet()))
                .as("an issue's is a move")
                .isEqualTo(new Correction(Correction.MOVE, List.of("Backlog", "Review")));
        assertThat(
                        Correction.ofState(
                                true,
                                new org.fuin.porelo.planner.store.Workflow(
                                        List.of("Building", "Finished"),
                                        List.of(),
                                        List.of("Finished")),
                                Set.of("Building")))
                .as("none where the workflow offers no such state")
                .isNull();
    }

    @Test
    void anIssuesStateCorrectionIsAMoveCheckedAsAnyMoveAndNoCorrectionOfItsOwn() {
        // Harm: a second route that moves an issue would pass by the move's rules.
        final CountingStore store = new CountingStore(board());

        final Throwable refused =
                catchThrowable(
                        () ->
                                Corrections.preview(
                                        store,
                                        new Corrections.Asked(
                                                false,
                                                "AA2",
                                                Warning.STATE_OUTSIDE_WORKFLOW,
                                                "Review"),
                                        "board@1"));
        assertThat(refused)
                .as("sent to the move")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("It is set right by a move of it, previewed and saved as any move.");
        assertThat(
                        Moves.check(
                                        store.board(),
                                        List.of(new Moves.Move(false, "AA2", "Review")),
                                        false,
                                        states -> null,
                                        (issue, stories) -> false)
                                .writes())
                .as("the move writes its state alone")
                .isEqualTo(new Writes(Map.of("AA2", "Review"), Map.of(), List.of()));
        assertThat(store.saves).as("nothing written").isEmpty();
    }

    @Test
    void anotherWarningHoldingTheItemLeavesTheCorrectionUnofferedAndRefusedAndHoldsStill() {
        // Harm: lifting a hold to correct one warning lets a hand edit of the parent, or a state
        // with it, slip past the work rules.
        final CountingStore store = new CountingStore(board(Warning.wrongParent()));
        final Story story = store.board().story("ST1").orElseThrow();

        assertThat(story.warnings())
                .as("its state's correction is not offered while the parent holds it")
                .containsExactly(Warning.stateOutsideWorkflow(), Warning.wrongParent());
        final Corrections.Asked asked = asked("ST1", Warning.STATE_OUTSIDE_WORKFLOW, "Review");
        final Throwable refused = catchThrowable(() -> digest(store, asked));
        assertThat(refused)
                .as("refused with the other warning's words")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage(Warning.wrongParent().text());
        assertThat(((Moves.Refused) refused).name()).isEqualTo(Corrections.REFUSED);
        assertThat(catchThrowable(() -> Priorities.check(store.board(), "ST1", "Low")))
                .as("the other warning still holds any other write")
                .isInstanceOf(Moves.Refused.class)
                .hasMessageContaining(Warning.wrongParent().text());
        assertThat(store.saves).as("nothing written").isEmpty();
    }

    @Test
    void aStaleAChangedARepeatedOrAnOutdatedConfirmWritesNothing() {
        // Harm: a confirm sent again after a lost answer, or over a board a hand edit changed,
        // writes onto an item the person no longer saw.
        final CountingStore store = new CountingStore(board());
        final Corrections.Asked asked = asked("ST1", Warning.STATE_OUTSIDE_WORKFLOW, "Review");
        final String digest = digest(store, asked);

        assertThat(catchThrowable(() -> save(store, asked, "0".repeat(64))))
                .as("another digest")
                .isInstanceOf(WorkRefusal.class)
                .satisfies(e -> assertThat(((WorkRefusal) e).name()).isEqualTo("PreviewChanged"));
        assertThat(store.saves).as("nothing written for another digest").isEmpty();

        assertThat(
                        catchThrowable(
                                () ->
                                        save(
                                                store,
                                                asked(
                                                        "ST1",
                                                        Warning.STATE_OUTSIDE_WORKFLOW,
                                                        "Backlog"),
                                                digest)))
                .as("another state than previewed")
                .isInstanceOf(WorkRefusal.class);
        assertThat(store.saves).as("nothing written for another state").isEmpty();

        save(store, asked, digest);
        assertThat(store.saves).as("the confirm saved once").hasSize(1);

        assertThat(catchThrowable(() -> save(store, asked, digest)))
                .as("the same confirm again")
                .isInstanceOf(StoreException.StaleRevision.class);
        assertThat(store.saves).as("the repeat wrote nothing").hasSize(1);

        final CountingStore gone =
                new CountingStore(
                        board(
                                new Revision("board@1"),
                                List.of(story("ST1", "Review", List.of("AA1"))),
                                board().issues()));
        assertThat(catchThrowable(() -> save(gone, asked, digest)))
                .as("the warning no longer stands at that revision")
                .isInstanceOf(Moves.Refused.class)
                .hasMessage("The warning no longer stands on it.");
        assertThat(gone.saves).as("nothing written over a warning gone").isEmpty();
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
                                        Corrections.preview(
                                                store,
                                                asked(
                                                        "ST1",
                                                        Warning.ISSUE_OUTSIDE_PROJECT,
                                                        "Backlog"),
                                                "board@1")))
                .as("not offered")
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage(PlanningStore.NOT_OFFERED);
        assertThat(store.saves).as("nothing written").isEmpty();
    }
}
