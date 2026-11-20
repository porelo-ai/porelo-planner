package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.GitHubExceptions;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A GitHub project is read whole only once GitHub answers a conditional ask that it changed, and
 * not asked at all in the background while GitHub's rate limit is spent.
 */
class ConditionalChangeProbeTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final List<String> REPOSITORIES =
            List.of("example-project", "example-two", "example-three");

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    REPOSITORIES,
                    Map.of(),
                    Map.of());

    private static final Duration FETCHED = Duration.ofMinutes(3);

    private static final Duration PROBED = Duration.ofSeconds(30);

    private final AtomicReference<Instant> now =
            new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));

    private final InstantSource clock = now::get;

    private final GitHubPace pace = new GitHubPace(clock);

    private final FakeGitHub github = new FakeGitHub();

    private final AtomicInteger reads = new AtomicInteger();

    private volatile @Nullable RuntimeException readFailure;

    private ConditionalChangeProbe probe(final ConditionalReads reads) {
        return new ConditionalChangeProbe(reads, origin -> TOKEN, pace, Runnable::run);
    }

    private GitHubStore store(final ChangeProbe probe) {
        return store(probe, BoardWriter.NONE);
    }

    private GitHubStore store(final ChangeProbe probe, final BoardWriter writer) {
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(
                                config -> {
                                    final RuntimeException failure = readFailure;
                                    if (failure != null) {
                                        readFailure = null;
                                        throw failure;
                                    }
                                    return snapshot("read-" + reads.incrementAndGet());
                                },
                                states -> null)
                        .writer(writer)
                        .probe(probe),
                origin -> TOKEN,
                Duration.ofMillis(500));
    }

    /** The store after its first read and the look that learns GitHub's tags. */
    private GitHubStore warmed(final ChangeProbe probe) {
        final GitHubStore store = store(probe);
        store.refresh();
        store.refresh();
        return store;
    }

    @Test
    void threeRepositoriesAndTheProjectAnsweringNotModifiedLeadToNoWholeRead() {
        // Harm: every look at the board spends the person's rate limit on a whole read.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();
        final int asksBefore = github.asks.get();

        final Board board = store.refresh();
        store.poll();

        assertThat(reads.get()).as("whole reads while GitHub answers 304").isEqualTo(before);
        assertThat(github.asks.get() - asksBefore)
                .as("conditional asks: three repositories and two pages of items, twice")
                .isEqualTo(10);
        assertThat(board.revision().value()).as("the board kept").isEqualTo("read-" + before);
    }

    @Test
    void oneChangedAnswerLeadsToOneWholeRead() {
        // Harm: a change made on GitHub never reaches the window, or is read again at every look.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.issueTags.put("example-two", "\"two-2\"");
        final Board changed = store.refresh();
        store.refresh();

        assertThat(reads.get())
                .as("whole reads after one repository changed")
                .isEqualTo(before + 1);
        assertThat(changed.revision().value())
                .as("the board read anew")
                .isEqualTo("read-" + (before + 1));

        github.pageTags.set(1, "\"items-1b\"");
        store.refresh();
        store.refresh();

        assertThat(reads.get())
                .as("whole reads after a later page of the project's items changed")
                .isEqualTo(before + 2);
    }

    @Test
    void aWholeReadThatFailedAfterAChangeIsReadAgainAtTheNextLook() {
        // Harm: a change GitHub reported is lost for good when the read after it failed, as the
        // next ask answers 304 to the tags of the failed look.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.issueTags.put("example-project", "\"project-2\"");
        readFailure = new StoreException.Unreachable("example-project", "timed out");
        assertThatThrownBy(store::refresh)
                .as("the read after the change")
                .isInstanceOf(StoreException.Unreachable.class);
        final Board after = store.refresh();

        assertThat(after.revision().value())
                .as("the board at the next look")
                .isEqualTo("read-" + (before + 1));
    }

    @Test
    void theAsksOfOneLookRunSideBySide() throws Exception {
        // Harm: a look at a project of many repositories takes as long as all its asks one after
        // another.
        final CountDownLatch all = new CountDownLatch(REPOSITORIES.size() + 1);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger most = new AtomicInteger();
        final ConditionalReads waiting =
                new ConditionalReads() {
                    @Override
                    public Answer issues(
                            final String token,
                            final String owner,
                            final String repository,
                            final @Nullable String etag) {
                        meet();
                        return github.issues(token, owner, repository, etag);
                    }

                    @Override
                    public Answer projectItems(
                            final String token,
                            final String owner,
                            final int number,
                            final @Nullable String after,
                            final @Nullable String etag) {
                        if (after == null) {
                            meet();
                        }
                        return github.projectItems(token, owner, number, after, etag);
                    }

                    private void meet() {
                        most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        all.countDown();
                        try {
                            all.await(2, TimeUnit.SECONDS);
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        inFlight.decrementAndGet();
                    }
                };
        final GitHubStore store =
                store(
                        new ConditionalChangeProbe(
                                waiting,
                                origin -> TOKEN,
                                pace,
                                task -> Thread.ofVirtual().start(task)));
        store.refresh();
        store.refresh();

        assertThat(most.get())
                .as("asks in flight at once: three repositories and the project")
                .isEqualTo(REPOSITORIES.size() + 1);
    }

    @Test
    void aLowRateLimitLengthensTheIntervalAndTheBoardSaysSo() {
        // Harm: the planner spends the last of the person's rate limit at the usual pace, leaving
        // nothing for the person's own work, unseen.
        final GitHubStore store = warmed(probe(github));
        assertThat(store.pollDelay(FETCHED, PROBED)).as("the usual wait").isEqualTo(PROBED);

        github.remaining = 400L;
        final Board board = store.refresh();

        assertThat(store.pollDelay(FETCHED, PROBED))
                .as("the wait with 400 of 5000 left")
                .isEqualTo(PROBED.multipliedBy(GitHubPace.LENGTHENED));
        assertThat(board.faults())
                .as("the board's faults")
                .anySatisfy(fault -> assertThat(fault).contains("rate limit is low (400 of 5000"));
    }

    @Test
    void aSpentRateLimitHoldsBackgroundReadsAndServesTheBoardKeptUntilTheWaitEnds() {
        // Harm: the planner asks GitHub again and again while it refuses, the window loses its
        // board, and the person is never told when GitHub is read again.
        final GitHubStore store = warmed(probe(github));
        final int asksBefore = github.asks.get();
        final String kept = store.board().revision().value();

        github.failure =
                GitHubExceptions.rateLimited(403, "rate limit exceeded", Duration.ofSeconds(120));
        final Board first = store.poll();
        final int asksAtTheRefusal = github.asks.get();
        now.set(now.get().plusSeconds(60));
        final Board held = store.poll();
        store.poll();

        assertThat(first.revision().value()).as("the board at the refusal").isEqualTo(kept);
        assertThat(held.revision().value()).as("the board while held").isEqualTo(kept);
        assertThat(github.asks.get())
                .as("asks while held")
                .isEqualTo(asksAtTheRefusal)
                .isGreaterThan(asksBefore);
        assertThat(held.faults())
                .as("the board's faults while held")
                .anySatisfy(
                        fault ->
                                assertThat(fault)
                                        .contains("rate limit is spent")
                                        .contains("2026-11-20T10:02:00Z"));
        assertThat(store.pollDelay(FETCHED, PROBED))
                .as("the wait while held")
                .isEqualTo(Duration.ofSeconds(60));

        github.failure = null;
        now.set(now.get().plusSeconds(61));
        final Board after = store.poll();

        assertThat(github.asks.get())
                .as("asks once the wait ended")
                .isGreaterThan(asksAtTheRefusal);
        assertThat(after.faults()).as("the board's faults once the wait ended").isEmpty();
    }

    @Test
    void aPersonsReloadWhileHeldAsksOnceAndGetsTheSameRefusal() {
        // Harm: the person's own reload silently answered from the board kept, or asking without
        // end; or told something other than the wait.
        final GitHubStore store = warmed(probe(github));
        github.failure =
                GitHubExceptions.rateLimited(403, "rate limit exceeded", Duration.ofSeconds(120));
        store.poll();
        final int asksHeld = github.asks.get();

        assertThatThrownBy(store::refresh)
                .as("the person's reload while held")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageContaining("GitHub's rate limit is spent; it reads again in 120 s");
        assertThat(github.asks.get() - asksHeld)
                .as("asks of the reload: one look, three repositories and the project")
                .isEqualTo(REPOSITORIES.size() + 1);
    }

    @Test
    void aSpentRateLimitOfTheWholeReadHoldsTheBackgroundReads() {
        // Harm: GitHub's GraphQL refusal of a spent limit is asked again at every poll.
        final AtomicInteger asked = new AtomicInteger();
        final ProjectQueries watched =
                pace.watching(
                        new ProjectQueries() {
                            @Override
                            public GitHubGraphQl.ProjectFields projectFields(
                                    final String token,
                                    final String owner,
                                    final int number,
                                    final @Nullable String after) {
                                asked.incrementAndGet();
                                throw GitHubExceptions.rateLimited(
                                        403, "spent", Duration.ofSeconds(90));
                            }

                            @Override
                            public GitHubGraphQl.ProjectItems projectItems(
                                    final String token,
                                    final String owner,
                                    final int number,
                                    final @Nullable String after) {
                                throw new UnsupportedOperationException("not read here");
                            }
                        });

        assertThatThrownBy(() -> watched.projectFields(TOKEN, "example-org", 7, null))
                .as("the read GitHub refused")
                .hasMessageContaining("spent");

        assertThat(pace.hold())
                .as("the hold after the refusal")
                .isEqualTo("GitHub's rate limit is spent; it reads again in 90 s");
    }

    @Test
    void aChangeCheckGitHubRefusesReadsWholeAndNamesNoToken() {
        // Harm: the person's token shown on the board, or a refused change check answered as
        // unchanged so the board never moves.
        final GitHubStore store = warmed(probe(github));
        final int before = reads.get();

        github.failure = GitHubExceptions.refused(404, "Not Found for Bearer " + TOKEN);
        final Board board = store.refresh();

        assertThat(reads.get()).as("whole reads after the refused check").isEqualTo(before + 1);
        assertThat(board.faults())
                .as("the board's faults")
                .anySatisfy(fault -> assertThat(fault).contains("HTTP 404"))
                .allSatisfy(fault -> assertThat(fault).doesNotContain(TOKEN));
    }

    @Test
    void theBoardAfterAWriteIsReadWholeEvenWhenGitHubAnswersUnchanged() {
        // Harm: a field value the planner wrote that the change check does not see leaves the
        // window showing the move undone.
        final GitHubStore store =
                store(
                        current -> true,
                        new BoardWriter() {
                            @Override
                            public void save(
                                    final Snapshot current,
                                    final Revision revision,
                                    final Writes writes) {
                                // Written; GitHub's answer to the change check is unchanged.
                            }
                        });
        store.refresh();
        final int before = reads.get();

        final Board saved =
                store.save(new Revision("read-1"), new Writes(Map.of(), Map.of(), List.of()));

        assertThat(reads.get()).as("whole reads after the write").isEqualTo(before + 1);
        assertThat(saved.revision().value()).as("the board after the write").isEqualTo("read-2");
    }

    private static Snapshot snapshot(final String revision) {
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        REPOSITORIES),
                Map.of(),
                CONFIG);
    }

    /**
     * GitHub's conditional answers: each repository's issues and each page of the project's items
     * hold a tag, answered 304 when it is the one sent.
     */
    private static final class FakeGitHub implements ConditionalReads {

        private final Map<String, String> issueTags = new ConcurrentHashMap<>();

        private final List<String> pageTags =
                new CopyOnWriteArrayList<>(List.of("\"items-0\"", "\"items-1\""));

        private final AtomicInteger asks = new AtomicInteger();

        private volatile @Nullable RuntimeException failure;

        private volatile long remaining = 4900L;

        FakeGitHub() {
            REPOSITORIES.forEach(name -> issueTags.put(name, "\"" + name + "-1\""));
        }

        @Override
        public Answer issues(
                final String token,
                final String owner,
                final String repository,
                final @Nullable String etag) {
            assertThat(token).as("the token sent").isEqualTo(TOKEN);
            assertThat(owner).as("the owner asked").isEqualTo("example-org");
            return answer(issueTags.get(repository), etag, null);
        }

        @Override
        public Answer projectItems(
                final String token,
                final String owner,
                final int number,
                final @Nullable String after,
                final @Nullable String etag) {
            assertThat(number).as("the project asked").isEqualTo(7);
            final int page = after == null ? 0 : Integer.parseInt(after.substring(1));
            final String next = page + 1 < pageTags.size() ? "p" + (page + 1) : null;
            return answer(pageTags.get(page), etag, next);
        }

        private Answer answer(
                final @Nullable String current,
                final @Nullable String sent,
                final @Nullable String next) {
            asks.incrementAndGet();
            final RuntimeException refused = failure;
            if (refused != null) {
                throw refused;
            }
            final boolean same = sent != null && sent.equals(current);
            return new Answer(!same, current, same ? null : next, remaining, 5000L);
        }
    }
}
