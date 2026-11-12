package org.fuin.porelo.planner.app.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.fuin.porelo.planner.core.Misfits;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/** The mock board's saves, which the window's planning tests read back. */
class MockStoreTest {

    private static final Revision FIRST = new Revision("mock@1");

    private final MockStore store = MockStore.fromClassPath().copy();

    @Test
    void aSaveAnswersANewRevisionWithTheMoveAndLeavesTheBoardBeforeItAsItWas() {
        // A mock that never saved would let the window's tests of a save pass.
        final Board before = store.board();

        final Board after = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(after.revision().value()).as("the revision after the save").isEqualTo("mock@2");
        assertThat(state(after, "B542")).as("the moved issue in the answer").isEqualTo("Review");
        assertThat(store.board()).as("the board read after the save").isEqualTo(after);
        assertThat(state(before, "B542")).as("the board before the save").isEqualTo("Backlog");
        assertThat(before.revision()).as("the revision before the save").isEqualTo(FIRST);
    }

    @Test
    void aSaveAgainstAnOlderRevisionIsRefusedAndChangesNothing() {
        // A save over a board the window never saw would overwrite another window's move.
        final Board saved = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThatThrownBy(() -> store.save(FIRST, moves(Map.of("B543", "Review"))))
                .as("a second save against the first revision")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale ->
                                assertThat(stale.current())
                                        .as("the revision it names")
                                        .isEqualTo("mock@2"));
        assertThat(store.board()).as("the board after the refusal").isEqualTo(saved);
    }

    @Test
    void aStoryWithoutAStatedStateFollowsItsIssuesAndAStatedOneIsKept() {
        // A story left in its old column after its issues moved shows the window a wrong board.
        final Board after =
                store.save(
                        FIRST,
                        new Writes(
                                Map.of("PJ58", "Estimated", "SI05", "Backlog"),
                                Map.of("ST15", "Finished"),
                                List.of()));

        assertThat(story(after, "ST14").state())
                .as("the story of the moved issue, which states no state")
                .isEqualTo("Estimated");
        assertThat(story(after, "ST15"))
                .as("a story whose state was written")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Finished", true);

        final Board unstated =
                store.save(after.revision(), new Writes(Map.of(), Map.of(), List.of("ST15")));
        assertThat(story(unstated, "ST15"))
                .as("the story whose stated state was removed")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Backlog", false);
    }

    @Test
    void theMockBoardsGivenStoryKeepsItsStateAndItsMisfitThroughASave() {
        // Harm: the window's tests never seeing a given state that its issues do not fit.
        final Board after = store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(story(after, "ST295"))
                .as("ST295 given Review over SL38 in Backlog, after another issue's save")
                .extracting(Story::state, Story::stateGiven)
                .containsExactly("Review", true);
        assertThat(Misfits.of(after, story(after, "ST295"), store::storyState))
                .as("SL38 stands outside the given Review, SL37 fits")
                .containsExactly("SL38");
    }

    @Test
    void aStorysStateIsDerivedAsTheStoriesIndexDerivesIt() {
        // A wrong derivation lets the moves check refuse or confirm the wrong story moves.
        assertThat(store.storyState(List.of("Review", "Backlog", "Finished")))
                .as("the earliest open state in the workflow's order")
                .isEqualTo("Backlog");
        assertThat(store.storyState(List.of("Rejected", "Finished")))
                .as("closed issues, not all rejected")
                .isEqualTo("Finished");
        assertThat(store.storyState(List.of("Rejected", "Rejected")))
                .as("every issue rejected")
                .isEqualTo("Rejected");
        assertThat(store.storyState(List.of())).as("no issue").isNull();
    }

    @Test
    void aBatchOverTheFixedStorysRepositoriesSavesTheFirstAndNamesTheNext() {
        // The window's handling of a partly saved batch could not be tested against the mock.
        assertThatThrownBy(
                        () -> store.save(FIRST, moves(Map.of("HO158", "Review", "B553", "Review"))))
                .as("a batch over the issues of " + MockStore.PARTLY_SAVED)
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories pushed")
                                    .containsExactly("porelo");
                            assertThat(partly.subject())
                                    .as("the repository that failed")
                                    .isEqualTo("porelo-host");
                        });
        final Board after = store.board();
        assertThat(after.revision().value())
                .as("the revision holding what was pushed")
                .isEqualTo("mock@2");
        assertThat(state(after, "B553"))
                .as("the issue of the pushed repository")
                .isEqualTo("Review");
        assertThat(state(after, "HO158"))
                .as("the issue of the repository that failed")
                .isEqualTo("Backlog");
    }

    @Test
    void aBatchOverOneRepositoryOfTheFixedStoryIsSavedWhole() {
        // A partly saved answer for every move of the story would leave it no plain save to test.
        final Board after = store.save(FIRST, moves(Map.of("B553", "Review", "B554", "Review")));

        assertThat(List.of(state(after, "B553"), state(after, "B554")))
                .as("both moved issues of one repository")
                .containsExactly("Review", "Review");
    }

    @Test
    void aMoveInOneCopyLeavesAnotherCopysBoardAsItWas() {
        // One project's moves seen in another project would mix the window's tests.
        final MockStore other = store.copy();

        store.save(FIRST, moves(Map.of("B542", "Review")));

        assertThat(other.board().revision()).as("the other copy's revision").isEqualTo(FIRST);
        assertThat(state(other.board(), "B542"))
                .as("the issue in the other copy")
                .isEqualTo("Backlog");
    }

    @Test
    void aResetRestoresTheFirstBoard() {
        // A test's moves left for the next would make the window's tests depend on their order.
        final Board first = store.board();
        store.save(FIRST, moves(Map.of("B542", "Review")));

        store.reset();

        assertThat(store.board()).as("the board after the reset").isEqualTo(first);
        assertThat(store.save(FIRST, moves(Map.of("B543", "Review"))).revision().value())
                .as("the revision of the first save after the reset")
                .isEqualTo("mock@2");
    }

    @Test
    void concurrentSavesNeitherLoseNorDoubleAMove() throws Exception {
        // The mock board is shared by every request; a lost or doubled save hides a race.
        final List<String> ids =
                List.of("B542", "B543", "B544", "B545", "B546", "B547", "B548", "B549");
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<Integer>> tries = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(ids.size())) {
            for (final String id : ids) {
                tries.add(pool.submit(() -> saveUntilTaken(start, id)));
            }
            start.countDown();
            for (final Future<Integer> attempt : tries) {
                attempt.get();
            }
        }
        final Board after = store.board();
        assertThat(after.revision().value())
                .as("the revision after one save per move")
                .isEqualTo("mock@" + (ids.size() + 1));
        assertThat(ids.stream().map(id -> state(after, id)).toList())
                .as("every moved issue")
                .containsOnly("Review");
    }

    @Test
    void aPriorityIsNotSaved() {
        // The mock offers no priority change; one answered as saved would hide that.
        assertThatThrownBy(
                        () ->
                                store.save(
                                        FIRST,
                                        new Writes(
                                                Map.of(),
                                                Map.of(),
                                                List.of(),
                                                Map.of(),
                                                Map.of("ST294", "Lowest"))))
                .as("a priority saved into the mock")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.board().revision()).as("the revision after it").isEqualTo(FIRST);
    }

    private int saveUntilTaken(final CountDownLatch start, final String id) throws Exception {
        start.await();
        int tries = 0;
        while (true) {
            tries++;
            try {
                store.save(store.board().revision(), moves(Map.of(id, "Review")));
                return tries;
            } catch (final StoreException.StaleRevision e) {
                Thread.onSpinWait();
            }
        }
    }

    private static Writes moves(final Map<String, String> issues) {
        return new Writes(issues, Map.of(), List.of());
    }

    private static String state(final Board board, final String issue) {
        return board.issue(issue).map(Issue::state).orElseThrow();
    }

    private static Story story(final Board board, final String id) {
        return board.story(id).orElseThrow();
    }
}
