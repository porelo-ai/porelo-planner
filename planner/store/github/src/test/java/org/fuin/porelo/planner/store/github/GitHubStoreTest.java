package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.forge.github.GitHubRefusals;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

class GitHubStoreTest {

    private static final String TOKEN = "invented-token-0123456789";

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
                    List.of("example-project"),
                    Map.of(),
                    Map.of());

    private static Snapshot snapshot(final String revision) {
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(),
                        new Work(Map.of(), List.of()),
                        List.of("example-project")),
                Map.of(),
                CONFIG);
    }

    private static GitHubStore store(final GitHubStore.Parts parts) {
        return new GitHubStore(CONFIG, parts, origin -> TOKEN, Duration.ofMillis(200));
    }

    @Test
    void aFailureOfGitHubReachesTheWindowWithoutTheToken() {
        // Harm: the person's token shown in the window or written to the log.
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                config -> {
                                    throw new IllegalStateException("401 for Bearer " + TOKEN);
                                },
                                states -> null));

        assertThatThrownBy(store::refresh)
                .as("a failure that names the token")
                .isInstanceOf(StoreException.Unreachable.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageContaining(Scrubbed.MARK);
    }

    @Test
    void aSecondCallWaitingTooLongOnTheProjectIsRefusedAsBusy() throws Exception {
        // Harm: two saves on one project interleaving and overwriting each other's fields.
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                config -> {
                                    inside.countDown();
                                    try {
                                        // Bounded, so a broken lock fails the test instead of
                                        // hanging it.
                                        release.await(5, TimeUnit.SECONDS);
                                    } catch (final InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return snapshot("one");
                                },
                                states -> null));
        final Thread first = Thread.ofVirtual().start(store::refresh);
        inside.await();
        try {
            assertThatThrownBy(store::refresh)
                    .as("the second refresh while the first holds the project")
                    .isInstanceOf(StoreException.Busy.class);
        } finally {
            release.countDown();
            first.join();
        }
    }

    @Test
    void aStoreGivenAnewReadsNothingMore() {
        // Harm: a project given anew still read or written through its old store.
        final AtomicInteger reads = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                config -> {
                                    reads.incrementAndGet();
                                    return snapshot("one");
                                },
                                states -> null));
        store.retire();

        assertThatThrownBy(store::refresh)
                .as("a refresh after the store was retired")
                .isInstanceOf(StoreException.Unreadable.class);
        assertThat(reads.get()).as("GitHub reads after the retirement").isZero();
    }

    @Test
    void anUnchangedProjectIsNotReadAgain() {
        // Harm: every look at the board spends the person's rate limit on a full read.
        final AtomicInteger reads = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                        config -> {
                                            reads.incrementAndGet();
                                            return snapshot("one");
                                        },
                                        states -> null)
                                .probe(current -> true));
        store.refresh();
        store.refresh();

        assertThat(reads.get())
                .as("full reads for two refreshes of an unchanged project")
                .isEqualTo(1);
    }

    @Test
    void aStoreThatOnlyReadsRefusesAMoveWithoutWritingAnything() {
        // Harm: a move answered as saved while nothing reached GitHub.
        final GitHubStore store =
                store(GitHubStore.Parts.reading(config -> snapshot("one"), states -> null));

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("one"),
                                        new Writes(Map.of(), Map.of(), List.of())))
                .as("a move on a store without a writer")
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(store.textFormat())
                .as("the text format GitHub keeps")
                .isEqualTo(TextFormat.MARKDOWN);
    }

    @Test
    void aStoreWhoseAccessIsRefusedReadsAgainInsteadOfAnsweringTheBoardItKept() {
        // Harm: the window shown the board kept while the person's access is gone, never offered
        // a token, its changes ending again at every poll.
        final AtomicInteger reads = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                config -> {
                                    if (reads.incrementAndGet() == 1) {
                                        return snapshot("one");
                                    }
                                    throw new StoreException.NoForgeAccess(
                                            "https://github.com", null);
                                },
                                states -> null));
        store.board();

        assertThatThrownBy(store::refresh)
                .as("the refresh GitHub refused")
                .isInstanceOf(StoreException.NoForgeAccess.class);
        assertThatThrownBy(store::board)
                .as("the board asked for after the refusal")
                .isInstanceOf(StoreException.NoForgeAccess.class);
        assertThat(reads.get()).as("the board was read again").isEqualTo(3);
    }

    @Test
    void aSaveRefusedAfterAPartWasWrittenReadsTheBoardAgainInsteadOfAnsweringTheBoardItKept() {
        // Harm: the window shown the board from before the save, as if nothing of it was written.
        final AtomicInteger reads = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                        config -> snapshot("r" + reads.incrementAndGet()),
                                        states -> null)
                                .refusals(new ForgeAccessRefusals(URI.create("https://github.com")))
                                .writer(
                                        new BoardWriter() {
                                            @Override
                                            public void save(
                                                    final Snapshot current,
                                                    final Revision revision,
                                                    final Writes writes) {
                                                throw new BoardWriter.AfterWriting(
                                                        GitHubRefusals.answered(
                                                                403,
                                                                "refused",
                                                                false,
                                                                "issues=write"));
                                            }
                                        }));
        store.board();

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("r1"),
                                        new Writes(Map.of(), Map.of(), List.of())))
                .as("a save refused after its first part was written")
                .isInstanceOf(StoreException.PartlySaved.class);
        assertThat(store.board().revision())
                .as("the board asked for after the partly saved write")
                .isEqualTo(new Revision("r2"));
    }

    @Test
    void aReadRefusedAfterAWriteIsPartlySavedNeverAMissingAccess() {
        // Harm: a write that reached GitHub answered as refused before writing, so the window or
        // a stage's hand-in makes it again once a token is handed on, writing it twice.
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                        config -> {
                                            if (reads.incrementAndGet() == 1) {
                                                return snapshot("r1");
                                            }
                                            throw GitHubRefusals.answered(
                                                    403,
                                                    "refused",
                                                    false,
                                                    "organization_projects=read");
                                        },
                                        states -> null)
                                .refusals(new ForgeAccessRefusals(URI.create("https://github.com")))
                                .writer(
                                        new BoardWriter() {
                                            @Override
                                            public void save(
                                                    final Snapshot current,
                                                    final Revision revision,
                                                    final Writes what) {
                                                writes.incrementAndGet();
                                            }
                                        }));
        store.board();

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("r1"),
                                        new Writes(Map.of(), Map.of(), List.of())))
                .as("a save written whose following read GitHub refused")
                .isInstanceOf(StoreException.PartlySaved.class)
                .isNotInstanceOf(StoreException.NoForgeAccess.class);
        assertThat(writes.get()).as("the writes made").isEqualTo(1);
    }

    @Test
    void aChangeByAPersonIsRefusedNamingTheRevisionGitHubHoldsNow() {
        // Harm: the window told the revision it showed is still current, so it saves against it
        // again and is refused again without end.
        final AtomicInteger reads = new AtomicInteger();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                        config -> snapshot("r" + reads.incrementAndGet()),
                                        states -> null)
                                .writer(
                                        new BoardWriter() {
                                            @Override
                                            public void save(
                                                    final Snapshot current,
                                                    final Revision revision,
                                                    final Writes writes) {
                                                throw new StoreException.StaleRevision(
                                                        current.board().revision().value(), "PL1");
                                            }
                                        }));
        store.board();

        assertThatThrownBy(
                        () ->
                                store.save(
                                        new Revision("r1"),
                                        new Writes(Map.of(), Map.of(), List.of())))
                .as("a save over an item a person changed")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> {
                            assertThat(stale.current()).as("the revision named").isEqualTo("r2");
                            assertThat(stale.changed()).as("the item named").isEqualTo("PL1");
                        });
    }
}
