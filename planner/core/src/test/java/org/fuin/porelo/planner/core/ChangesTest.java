package org.fuin.porelo.planner.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/** One project's changes, told to every window that subscribed to them. */
class ChangesTest {

    private static final Duration NOW = Duration.ZERO;

    /** A store whose remote another person can push to. */
    private static class FakeStore implements PlanningStore {

        private Board board = boardAt("r1");

        private Board remote = board;

        private RuntimeException failure;

        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public Board board() {
            return board;
        }

        @Override
        public Board refresh() {
            refreshes.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            board = remote;
            return board;
        }
    }

    private final FakeStore store = new FakeStore();

    private final AtomicInteger idle = new AtomicInteger();

    private final Changes changes = new Changes(store, idle::incrementAndGet);

    @Test
    void aSubscriberGetsTheBoardAtOnceAndEveryNewRevisionButNoRepeat() throws Exception {
        // A window would show a stale board, or redraw the same one for every poll.
        final Changes.Subscription one = changes.subscribe();
        final Changes.Subscription two = changes.subscribe();
        assertThat(one.first().revision().value()).as("the board at once").isEqualTo("r1");

        changes.poll();
        assertThat(one.next(NOW)).as("a poll with nothing new").isEmpty();

        store.remote = boardAt("r2");
        changes.poll();
        assertThat(revision(one.next(NOW)))
                .as("a push from elsewhere, first window")
                .isEqualTo("r2");
        assertThat(revision(two.next(NOW))).as("the same, second window").isEqualTo("r2");
        assertThat(one.next(NOW)).as("told once").isEmpty();
    }

    @Test
    void aRepositorysWarningLiftedAtTheSameRevisionIsToldOnce() throws Exception {
        // Harm: a correction of a repository's labels changes no item and keeps the revision, so
        // the window kept showing the warning it had just set right.
        final Board plain =
                new Board(
                        new Revision("r1"),
                        new Workflow(List.of("Idea"), List.of(), List.of()),
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        org.fuin.porelo.planner.store.ProjectRepository.named(
                                List.of("porelo-lib")));
        store.board =
                plain.withRepositories(
                        List.of(
                                new org.fuin.porelo.planner.store.RepositoryWarning(
                                                "porelo-lib",
                                                org.fuin.porelo.planner.store.RepositoryWarning
                                                        .TOPIC_LABELS,
                                                List.of("tests"),
                                                List.of(),
                                                List.of(),
                                                null)
                                        .offered()),
                        List.of());
        store.remote = store.board;
        final Changes.Subscription one = changes.subscribe();
        assertThat(one.first().repositoryWarnings()).as("the warning at once").hasSize(1);

        store.remote = plain;
        changes.poll();
        assertThat(one.next(NOW).orElseThrow().repositoryWarnings())
                .as("the board without it, at the same revision")
                .isEmpty();
        changes.poll();
        assertThat(one.next(NOW)).as("told once").isEmpty();
    }

    @Test
    void aHeldSaveDroppedAtTheSameRevisionIsToldOnce() throws Exception {
        // Harm: dropping a held save changes no item and keeps the revision, so every window kept
        // offering a save that no longer exists.
        final List<String> kept = new java.util.ArrayList<>(List.of("a held save"));
        final Changes holding = new Changes(store, idle::incrementAndGet, null, board -> kept);
        final Changes.Subscription one = holding.subscribe();
        assertThat(one.firstFrame().held()).as("the held save at once").hasSize(1);

        kept.clear();
        holding.saved();
        assertThat(one.nextFrame(NOW).map(Changes.Frame::held))
                .as("the board without it, at the same revision")
                .contains(List.of());
        holding.saved();
        assertThat(one.nextFrame(NOW)).as("told once").isEmpty();
    }

    @Test
    void theHeldSavesAreToldAgainstTheBoardOfTheirOwnFrame() {
        // Harm: what a held save would change shown against another board than the one drawn
        // beside it, so the value told as GitHub's is not the one on the card.
        final AtomicInteger reads = new AtomicInteger();
        final FakeStore moving =
                new FakeStore() {
                    @Override
                    public Board board() {
                        return boardAt("r" + reads.incrementAndGet());
                    }
                };
        final Changes holding =
                new Changes(
                        moving,
                        idle::incrementAndGet,
                        null,
                        board -> List.of(board.revision().value()));

        final Changes.Frame frame = holding.subscribe().firstFrame();

        assertThat(frame.held()).isEqualTo(List.of(frame.board().revision().value()));
    }

    @Test
    void thePlannersOwnSaveReachesEachSubscriberOnceAndItsPollNoMore() throws Exception {
        final Changes.Subscription one = changes.subscribe();
        final Board saved = boardAt("r2");
        store.board = saved;
        store.remote = saved;
        changes.saved();
        assertThat(revision(one.next(NOW))).as("the save").isEqualTo("r2");
        changes.poll();
        assertThat(one.next(NOW)).as("the poll after it finds nothing new").isEmpty();
    }

    @Test
    void aSaveIsToldAtOnceWhileAFetchStillReads() throws Exception {
        // Harm: a move's answer waits for a whole read of a large tracked project, minutes, so
        // the card stays "Saving..." though the tracker took the move at once.
        final java.util.concurrent.CountDownLatch reading =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final FakeStore slow =
                new FakeStore() {
                    @Override
                    public Board poll() {
                        reading.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return board();
                    }
                };
        final Changes held = new Changes(slow, idle::incrementAndGet);
        final Changes.Subscription one = held.subscribe();
        final Thread fetch = Thread.ofVirtual().start(held::pollOnSchedule);
        try {
            assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the fetch reading")
                    .isTrue();
            slow.board = boardAt("r2");
            final Thread save = Thread.ofVirtual().start(held::saved);

            assertThat(save.join(Duration.ofSeconds(2)))
                    .as("the save told while the fetch reads")
                    .isTrue();
            assertThat(revision(one.next(NOW))).as("the save's board").isEqualTo("r2");
        } finally {
            release.countDown();
            fetch.join();
        }
    }

    @Test
    void aSubscriptionAndAPollAreToldAtOnceWhileARunsSaveIsAtGitHub() throws Exception {
        // Harm: a window's event stream, and every poll, stand still behind a task's save to
        // GitHub while the changes' own monitor is held, so no window opens or hears of a change.
        final WorkRunTest.Fixture f = new WorkRunTest.Fixture();
        final WorkRun.Offered offer = f.order().tasks().getFirst();
        final Changes held = new Changes(f.store, idle::incrementAndGet, f.run);
        final Changes.Subscription early = held.subscribe();
        final Board before = f.store.current;
        final java.util.concurrent.CountDownLatch saving =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        f.store.onSave =
                () -> {
                    f.store.onSave = null;
                    saving.countDown();
                    try {
                        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // The change found outside by the poll is gone again by the time GitHub
                    // answers, so the save is checked against the board it was judged on.
                    f.store.current = before;
                };
        final Thread save = Thread.ofVirtual().start(() -> f.start(offer));
        try {
            assertThat(saving.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the task's save is at GitHub")
                    .isTrue();
            final WorkRun.Snapshot recorded = f.run.snapshot();
            f.store.current =
                    new Board(
                            new Revision("outside"),
                            before.workflow(),
                            before.stories(),
                            before.issues(),
                            before.work(),
                            before.repositories());

            final var told =
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                final Changes.Subscription late = held.subscribe();
                                held.poll();
                                held.saved();
                                return late.firstFrame();
                            });

            final Changes.Frame first = told.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.board().revision().value())
                    .as("a new subscription's first board, while the save is at GitHub")
                    .isEqualTo("outside");
            assertThat(first.work())
                    .as("the run as the record holds it while its save is in flight")
                    .isEqualTo(recorded);
            assertThat(revision(early.next(NOW)))
                    .as("the poll's board, told while the save is at GitHub")
                    .isEqualTo("outside");
        } finally {
            release.countDown();
            save.join(Duration.ofSeconds(5));
        }
        assertThat(save.isAlive()).as("the save ended").isFalse();
        held.saved();
        assertThat(early.nextFrame(NOW).map(frame -> frame.work().generation()))
                .as("the save's own tell, with the work it wrote")
                .contains(f.run.snapshot().generation());
    }

    @Test
    void aReloadIsNotedWhileItWaitsBehindAFetchUnderWayAndEndedAfterIt() throws Exception {
        // Harm: a Reload queued behind the planner's own poll is told as that poll's look, so the
        // window offers Reload again when the look ends, before the read asked for began.
        final java.util.concurrent.CountDownLatch reading =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final AtomicInteger asked = new AtomicInteger();
        final AtomicInteger refreshedWhileAsked = new AtomicInteger();
        final FakeStore slow =
                new FakeStore() {
                    @Override
                    public Board poll() {
                        reading.countDown();
                        try {
                            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return board();
                    }

                    @Override
                    public Board refresh() {
                        refreshedWhileAsked.set(asked.get());
                        return super.refresh();
                    }

                    @Override
                    public void reloadAsked() {
                        asked.incrementAndGet();
                    }

                    @Override
                    public void reloadEnded() {
                        asked.decrementAndGet();
                    }
                };
        final Changes held = new Changes(slow, idle::incrementAndGet);
        final Thread fetch = Thread.ofVirtual().start(held::pollOnSchedule);
        final Thread reload;
        try {
            assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the fetch reading")
                    .isTrue();
            reload = Thread.ofVirtual().start(held::poll);
            final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (asked.get() == 0) {
                assertThat(System.nanoTime()).as("the Reload noted").isLessThan(until);
                Thread.onSpinWait();
            }
            assertThat(slow.refreshes.get()).as("the Reload's reads begun").isZero();
        } finally {
            release.countDown();
            fetch.join();
        }
        reload.join();

        assertThat(refreshedWhileAsked.get()).as("the Reload noted while it reads").isOne();
        assertThat(asked.get()).as("the Reloads noted after it").isZero();
    }

    @Test
    void aSaveToldAfterANewerPollNeverTellsTheOlderBoard() throws Exception {
        // A window would go back to a board a push elsewhere has already replaced.
        final Changes.Subscription one = changes.subscribe();
        store.remote = boardAt("r3");
        changes.poll();
        assertThat(revision(one.next(NOW))).as("the poll").isEqualTo("r3");
        changes.saved();
        assertThat(one.next(NOW)).as("the save told late").isEmpty();
        changes.poll();
        assertThat(one.next(NOW)).as("the next poll").isEmpty();
    }

    @Test
    void aSubscriberWhoseFirstBoardIsNewerThanTheOthersIsNotToldItAgain() throws Exception {
        // A board read by another call before the poll would reach the new window twice.
        final Changes.Subscription one = changes.subscribe();
        store.board = boardAt("r2");
        store.remote = store.board;
        final Changes.Subscription two = changes.subscribe();
        assertThat(two.first().revision().value()).as("the newer board first").isEqualTo("r2");
        changes.poll();
        assertThat(revision(one.next(NOW))).as("the first window").isEqualTo("r2");
        assertThat(two.next(NOW)).as("the second window, which has it").isEmpty();
    }

    @Test
    void aSubscriberAfterTheLastOneLeftGetsTheNewestBoardOnce() throws Exception {
        changes.subscribe().close();
        store.board = boardAt("r2");
        store.remote = store.board;
        final Changes.Subscription two = changes.subscribe();
        assertThat(two.first().revision().value()).as("the board at once").isEqualTo("r2");
        changes.poll();
        assertThat(two.next(NOW)).as("not again").isEmpty();
    }

    @Test
    void aSlowSubscriberGetsOnlyTheNewestBoard() throws Exception {
        // A queue per window would grow without bound while a window does not read.
        final Changes.Subscription slow = changes.subscribe();
        for (final String revision : List.of("r2", "r3", "r4")) {
            store.remote = boardAt(revision);
            changes.poll();
        }
        assertThat(revision(slow.next(NOW))).as("only the newest").isEqualTo("r4");
        assertThat(slow.next(NOW)).as("and nothing after it").isEmpty();
    }

    @Test
    void aWaitWithNothingNewEndsEmptySoTheStreamCanKeepItsConnectionAlive() throws Exception {
        final Changes.Subscription one = changes.subscribe();
        final long start = System.nanoTime();
        assertThat(one.next(Duration.ofMillis(200))).as("nothing within the wait").isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("it waited")
                .isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void aWaitingSubscriberWakesOnAChange() throws Exception {
        final Changes.Subscription one = changes.subscribe();
        final Thread poller =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    sleep(100);
                                    store.remote = boardAt("r2");
                                    changes.poll();
                                });
        assertThat(revision(one.next(Duration.ofSeconds(10))))
                .as("the change, before the wait ends")
                .isEqualTo("r2");
        poller.join();
    }

    @Test
    void aClosedSubscriberIsDroppedAndTheLastOneGoneLeavesTheChangesIdle() throws Exception {
        // Polling for nobody would call Git every few minutes forever.
        final Changes.Subscription one = changes.subscribe();
        final Changes.Subscription two = changes.subscribe();
        one.close();
        assertThat(changes.subscribers()).as("one left").isEqualTo(1);
        assertThat(idle).as("not idle with one left").hasValue(0);
        two.close();
        two.close();
        assertThat(changes.subscribers()).as("none left").isZero();
        assertThat(idle).as("idle once, when the last left").hasValue(1);
        store.remote = boardAt("r2");
        changes.poll();
        assertThat(one.next(NOW)).as("a closed subscriber is told nothing").isEmpty();
    }

    @Test
    void aRefreshThatFailsReachesNoOneAndTheNextOneDoes() throws Exception {
        // A failed fetch must neither end the windows' streams nor be shown as a board.
        final Changes.Subscription one = changes.subscribe();
        store.remote = boardAt("r2");
        store.failure = new StoreException.Unreachable("porelo-store", "no answer within 10 s");
        assertThatThrownBy(changes::poll)
                .as("the failure, for the caller to log")
                .isInstanceOf(StoreException.Unreachable.class);
        assertThat(one.next(NOW)).as("nothing told").isEmpty();
        store.failure = null;
        changes.poll();
        assertThat(revision(one.next(NOW))).as("the next poll").isEqualTo("r2");
    }

    @Test
    void closingAllEndsEverySubscriptionAndWakesItsWait() throws Exception {
        // A stream left open on a project given anew would show the old repository's board.
        final Changes.Subscription one = changes.subscribe();
        final Thread waiting =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        one.next(Duration.ofSeconds(30));
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        Thread.sleep(100);
        changes.closeAll();
        waiting.join(Duration.ofSeconds(5));
        assertThat(waiting.isAlive()).as("the wait ended at once").isFalse();
        assertThat(one.open()).as("the subscription").isFalse();
        assertThat(idle).as("idle").hasValue(1);
    }

    @Test
    void aFaultThatComesOrGoesAtTheSameRevisionReachesEachSubscriber() throws Exception {
        // Harm: GitHub's spent rate limit, or its end, never reaches the window, as the board
        // kept keeps its revision.
        final Changes.Subscription one = changes.subscribe();
        final Board kept = boardAt("r1");
        store.remote =
                new Board(
                        kept.revision(),
                        kept.workflow(),
                        kept.stories(),
                        kept.issues(),
                        kept.work(),
                        kept.repositories(),
                        List.of("GitHub's rate limit is spent"));
        changes.poll();
        final Optional<Board> held = one.next(NOW);
        store.remote = kept;
        changes.poll();
        final Optional<Board> ended = one.next(NOW);

        assertThat(held.map(Board::faults))
                .as("the board once the limit is spent")
                .contains(List.of("GitHub's rate limit is spent"));
        assertThat(ended.map(Board::faults))
                .as("the board once the wait ended")
                .contains(List.of());
    }

    private static String revision(final Optional<Board> board) {
        assertThat(board).as("a board").isPresent();
        return board.orElseThrow().revision().value();
    }

    private static Board boardAt(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea"), List.of(), List.of()),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()));
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
