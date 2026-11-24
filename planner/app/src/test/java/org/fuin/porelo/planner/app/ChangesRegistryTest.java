package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.core.Changes;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The projects windows follow, each polled only while followed, and only on its own store. */
class ChangesRegistryTest {

    private final AtomicInteger opened = new AtomicInteger();

    private volatile boolean unreadable;

    private volatile @Nullable RuntimeException refreshRefused;

    private volatile @Nullable RuntimeException boardRefused;

    private final CountDownLatch slowRead = new CountDownLatch(1);

    private final Map<String, String> remote = new ConcurrentHashMap<>();

    private final AtomicInteger refreshes = new AtomicInteger();

    private final Projects projects =
            new Projects((project, repository, branch) -> store(repository));

    private final ChangesRegistry registry =
            new ChangesRegistry(
                    projects, Duration.ofHours(1), Executors.newSingleThreadScheduledExecutor());

    @AfterEach
    void close() {
        registry.close();
    }

    @Test
    void aProjectGivenAnewEndsItsStreamsSoTheyReadItsNewStore() throws Exception {
        // A stream kept on the retired store would show a repository no longer given.
        projects.put("alpha", "one", null);
        final Changes.Subscription one = registry.subscribe("alpha");
        projects.put("alpha", "two", null);
        assertThat(one.open()).as("the stream on the old store").isFalse();
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
        final Changes.Subscription two = registry.subscribe("alpha");
        assertThat(two.first().revision().value()).as("the new store's board").isEqualTo("two");
        assertThat(registry.followedProjects()).as("followed anew").isEqualTo(1);
        one.close();
        assertThat(registry.followedProjects())
                .as("the old stream's end leaves the new one followed")
                .isEqualTo(1);
    }

    @Test
    void aForgottenProjectEndsItsStreams() throws Exception {
        projects.put("alpha", "one", null);
        final Changes.Subscription one = registry.subscribe("alpha");
        projects.forgetAll();
        assertThat(one.open()).as("the stream").isFalse();
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
    }

    @Test
    void aBoardThatCannotBeReadLeavesNothingPolled() throws Exception {
        // A project followed by no one would be fetched every few minutes forever.
        projects.put("alpha", "one", null);
        unreadable = true;
        assertThatThrownBy(() -> registry.subscribe("alpha"))
                .as("the first board")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
    }

    @Test
    void aRefreshWithNoStreamFetchesButFollowsNothing() throws Exception {
        projects.put("alpha", "one", null);
        assertThat(registry.refresh("alpha")).as("the revision").isEqualTo("one");
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
    }

    @Test
    void aRefreshBeforeTheFirstBoardOfAStoreThatReadsAloneAnswersAtOnce() throws Exception {
        // Harm: a large project's first read outlasts the window's 30 s, so its Reload shows "the
        // planner did not answer" while the read goes on.
        projects.put("alpha", "alone", null);
        try {
            final CompletableFuture<@Nullable String> answered =
                    CompletableFuture.supplyAsync(() -> registry.refresh("alpha"));
            assertThat(answered.get(5, TimeUnit.SECONDS))
                    .as("the revision held, none before the first board")
                    .isNull();
        } finally {
            slowRead.countDown();
        }
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
    }

    @Test
    void closingTheRegistryEndsEveryStream() throws Exception {
        // Streams left waiting would hold the planner's stop.
        projects.put("alpha", "one", null);
        final Changes.Subscription one = registry.subscribe("alpha");
        registry.close();
        assertThat(one.open()).as("the stream").isFalse();
    }

    @Test
    void aSlowFirstReadOfOneProjectHoldsNoOtherProjectsStream() throws Exception {
        // A clone over a slow network would hang every window of every project.
        projects.put("alpha", "slow", null);
        projects.put("beta", "one", null);
        final Thread slow = Thread.ofVirtual().start(() -> registry.subscribe("alpha"));
        Thread.sleep(200);
        try {
            final CompletableFuture<Changes.Subscription> other =
                    CompletableFuture.supplyAsync(() -> registry.subscribe("beta"));
            assertThat(other.get(5, TimeUnit.SECONDS).first().revision().value())
                    .as("the other project's board while the first read hangs")
                    .isEqualTo("one");
        } finally {
            slowRead.countDown();
            slow.join();
        }
    }

    @Test
    void aProjectIsFetchedAsSoonAsAWindowFollowsIt() throws Exception {
        // A planner just started answers its clones as an earlier run left them; waiting a whole
        // interval for the first fetch shows the person faults that are long fixed.
        projects.put("alpha", "one", null);
        remote.put("one", "two");
        final Changes.Subscription one = registry.subscribe("alpha");
        assertThat(one.next(Duration.ofSeconds(5)).map(board -> board.revision().value()))
                .as("the remote's board, long before the hour's poll")
                .contains("two");
        one.close();
    }

    @Test
    void aFollowedProjectIsPolledUntilItsLastStreamEnds() throws Exception {
        // A push elsewhere would never show, or Git would be called for nobody forever.
        try (ChangesRegistry polled =
                new ChangesRegistry(
                        projects,
                        Duration.ofMillis(50),
                        Executors.newSingleThreadScheduledExecutor())) {
            projects.put("alpha", "one", null);
            final Changes.Subscription one = polled.subscribe("alpha");
            final Changes.Subscription two = polled.subscribe("alpha");
            remote.put("one", "two");
            assertThat(one.next(Duration.ofSeconds(5)).map(board -> board.revision().value()))
                    .as("the push, without a refresh")
                    .contains("two");
            one.close();
            assertThat(polled.followedProjects()).as("followed while one is left").isEqualTo(1);
            two.close();
            assertThat(polled.followedProjects()).as("nothing followed").isZero();
            Thread.sleep(200);
            final int after = refreshes.get();
            Thread.sleep(300);
            assertThat(refreshes.get()).as("no fetch once nobody follows").isEqualTo(after);
        }
    }

    @Test
    void aFetchRefusedForAMissingForgeAccessEndsTheStreamAndAnotherFailureDoesNot()
            throws Exception {
        // Harm: the window keeps showing a live board while the person's access is gone, never
        // told the refusal and never offered to hand a token on; or every passing failure ends
        // the stream.
        try (ChangesRegistry polled =
                new ChangesRegistry(
                        projects,
                        Duration.ofMillis(20),
                        Executors.newSingleThreadScheduledExecutor())) {
            projects.put("alpha", "one", null);
            final Changes.Subscription one = polled.subscribe("alpha");
            refreshRefused = new StoreException.Unreachable("porelo-store", "timed out");
            final int before = refreshes.get();
            final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (refreshes.get() < before + 3 && System.nanoTime() < end) {
                Thread.sleep(10);
            }
            assertThat(refreshes.get())
                    .as("fetches that failed")
                    .isGreaterThanOrEqualTo(before + 3);
            assertThat(one.open()).as("the stream after fetches that failed").isTrue();

            refreshRefused = new StoreException.NoForgeAccess("https://github.com", null);
            while (one.open() && System.nanoTime() < end + TimeUnit.SECONDS.toNanos(5)) {
                Thread.sleep(10);
            }
            assertThat(one.open()).as("the stream after the forge refused the access").isFalse();
            assertThat(polled.followedProjects()).as("nothing followed").isZero();
        }
    }

    @Test
    void aReadAfterASaveThatFailsNeverReplacesTheSavesAnswer() throws Exception {
        // Harm: a save answered with the read's failure instead of its own refusal or board, so
        // the window is offered no token, or told a written save failed and makes it again.
        projects.put("alpha", "one", null);
        final Changes.Subscription one = registry.subscribe("alpha");
        boardRefused = new StoreException.Unreachable("porelo-store", "timed out");
        registry.saved("alpha");
        assertThat(one.open()).as("the stream after a read that failed").isTrue();

        boardRefused = new StoreException.NoForgeAccess("https://github.com", "Issues");
        registry.saved("alpha");
        assertThat(one.open()).as("the stream after the forge refused the read").isFalse();
        assertThat(registry.followedProjects()).as("nothing followed").isZero();
    }

    private PlanningStore store(final String repository) {
        opened.incrementAndGet();
        return new PlanningStore() {
            private Board board = boardAt(repository);

            @Override
            public Board board() {
                if (unreadable) {
                    throw new StoreException.Unreadable("porelo-store", "no JSON head");
                }
                final RuntimeException refused = boardRefused;
                if (refused != null) {
                    throw refused;
                }
                if (repository.equals("slow")) {
                    awaitSlowRead();
                }
                return board;
            }

            @Override
            public boolean reloadsAlone() {
                return repository.equals("alone");
            }

            @Override
            public java.util.Optional<Revision> held() {
                return java.util.Optional.empty();
            }

            @Override
            public Board refresh() {
                refreshes.incrementAndGet();
                final RuntimeException refused = refreshRefused;
                if (refused != null) {
                    throw refused;
                }
                if (repository.equals("alone")) {
                    awaitSlowRead();
                }
                board = boardAt(remote.getOrDefault(repository, repository));
                return board();
            }
        };
    }

    private void awaitSlowRead() {
        try {
            slowRead.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Board boardAt(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea"), List.of(), List.of()),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()));
    }
}
