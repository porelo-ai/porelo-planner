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
import org.fuin.porelo.planner.store.NewIdea;
import org.fuin.porelo.planner.store.NewIssue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.TextFormat;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
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
        return new GitHubStore(
                CONFIG, () -> CONFIG, parts, origin -> TOKEN, Duration.ofMillis(200));
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
        store.poll();
        store.poll();

        assertThat(reads.get())
                .as("full reads for a reload and two polls of an unchanged project")
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
    void anEditThatWroteIsAnsweredWithTheBoardReadAgainAndOneThatWroteNothingIsNot() {
        // Harm: the window shown the board from before a written edit, or the person's rate
        // limit spent on a full read after an edit or a comment that changed no board.
        final AtomicInteger reads = new AtomicInteger();
        final java.util.List<String> written = new java.util.ArrayList<>();
        final GitHubStore store =
                store(
                        GitHubStore.Parts.reading(
                                        config -> snapshot("r" + reads.incrementAndGet()),
                                        states -> null)
                                .probe(current -> true)
                                .writer(
                                        new BoardWriter() {
                                            @Override
                                            public boolean setBlockedBy(
                                                    final Snapshot current,
                                                    final Revision revision,
                                                    final String issue,
                                                    final List<String> blockedBy) {
                                                return !blockedBy.isEmpty();
                                            }

                                            @Override
                                            public void comment(
                                                    final Snapshot current,
                                                    final String issue,
                                                    final String text) {
                                                written.add(text);
                                            }
                                        }));
        store.board();

        assertThat(store.setBlockedBy(new Revision("r1"), "AB1", List.of()).revision())
                .as("the board after an edit that wrote nothing")
                .isEqualTo(new Revision("r1"));
        store.comment("AB1", "A note.");
        assertThat(reads.get()).as("full reads before anything was written").isEqualTo(1);
        assertThat(written).as("the comment written").containsExactly("A note.");

        assertThat(store.setBlockedBy(new Revision("r1"), "AB1", List.of("AB2")).revision())
                .as("the board after an edit that wrote")
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

    /** A store that makes the given items, then fails to read the board after the first read. */
    private static GitHubStore failingReadAfterMaking(
            final String issue, final BoardWriter.IdeaIds idea) {
        final AtomicInteger reads = new AtomicInteger();
        return store(
                GitHubStore.Parts.reading(
                                config -> {
                                    if (reads.incrementAndGet() == 1) {
                                        return snapshot("r1");
                                    }
                                    throw new IllegalStateException("GitHub went away");
                                },
                                states -> null)
                        .writer(
                                new BoardWriter() {
                                    @Override
                                    public String createIssue(
                                            final Snapshot current,
                                            final Revision revision,
                                            final NewIssue added) {
                                        return issue;
                                    }

                                    @Override
                                    public BoardWriter.IdeaIds createIdea(
                                            final Snapshot current,
                                            final Revision revision,
                                            final NewIdea added) {
                                        return idea;
                                    }
                                }));
    }

    @Test
    void aBoardReadFailingAfterAWholeCreateIsPartlySavedNamingWhatWasMade() {
        // Harm: an issue or an idea GitHub holds answered as if nothing was made, so the window
        // records nothing pushed and the same idea is handed in and made again.
        final NewIssue issue =
                new NewIssue("example-lib", "A new thing", "It works.", "S", Worker.AGENT, null);
        final BoardWriter.IdeaIds made =
                new BoardWriter.IdeaIds(
                        "example-project#5", List.of("example-lib#6", "example-other#7"));
        final GitHubStore issues = failingReadAfterMaking("example-lib#100", made);
        issues.board();
        final GitHubStore ideas = failingReadAfterMaking("example-lib#100", made);
        ideas.board();

        assertThatThrownBy(() -> issues.createIssue(new Revision("r1"), issue))
                .as("an issue made whose following read failed")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories written")
                                    .containsExactly("example-lib");
                            assertThat(partly.getMessage())
                                    .as("the refusal's words")
                                    .contains("example-lib#100");
                        });
        assertThatThrownBy(
                        () ->
                                ideas.createIdea(
                                        new Revision("r1"),
                                        new NewIdea(
                                                "A story",
                                                "A gain",
                                                "A test",
                                                "High",
                                                List.of(issue))))
                .as("an idea made whose following read failed")
                .isInstanceOfSatisfying(
                        StoreException.PartlySaved.class,
                        partly -> {
                            assertThat(partly.pushed())
                                    .as("the repositories written")
                                    .containsExactly(
                                            "example-project", "example-lib", "example-other");
                            assertThat(partly.getMessage())
                                    .as("the refusal's words")
                                    .contains(
                                            "example-project#5",
                                            "example-lib#6",
                                            "example-other#7");
                        });
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

    @Test
    void projectFilesChangedAtTheOwnHeadAreReadThoughGitHubSawNoChange() {
        // Harm: a workflow or work rule changed in the own repository never reaches the board,
        // as GitHub's items stayed as they were.
        final ProjectConfig moved =
                new ProjectConfig(
                        CONFIG.forge(),
                        CONFIG.project(),
                        "d00d1e5",
                        new Workflow(
                                List.of("Idea", "Doing", "Finished"),
                                List.of(),
                                List.of("Finished")),
                        CONFIG.work(),
                        CONFIG.repositories(),
                        Map.of(),
                        Map.of());
        final java.util.concurrent.atomic.AtomicReference<ProjectConfig> files =
                new java.util.concurrent.atomic.AtomicReference<>(CONFIG);
        final List<ProjectConfig> read = new java.util.ArrayList<>();
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        files::get,
                        GitHubStore.Parts.reading(
                                        config -> {
                                            read.add(config);
                                            return new Snapshot(
                                                    snapshot("one").board(), Map.of(), config);
                                        },
                                        states -> null)
                                .probe(current -> true),
                        origin -> TOKEN,
                        Duration.ofMillis(200));
        store.refresh();
        files.set(moved);
        store.refresh();

        assertThat(read)
                .as("the project read with the files of each head")
                .extracting(ProjectConfig::commit)
                .containsExactly("c0ffee0", "d00d1e5");
    }
}
