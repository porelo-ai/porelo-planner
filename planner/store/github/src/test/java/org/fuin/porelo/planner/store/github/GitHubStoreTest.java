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
}
