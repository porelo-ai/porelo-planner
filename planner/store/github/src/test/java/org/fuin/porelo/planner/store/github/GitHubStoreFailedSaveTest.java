package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * A save that fails or comes back stale keeps the board held, read again by id where the save
 * touched anything, so no call waits behind a whole read of the project.
 */
class GitHubStoreFailedSaveTest {

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

    /** A board at [revision] holding the issue PL1, whose issue node is I_one. */
    private static Snapshot board(final String revision) {
        final Board board =
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(
                                new Issue(
                                        "PL1",
                                        "example-project",
                                        "One",
                                        "Idea",
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "# One")),
                        new Work(Map.of(), List.of()),
                        List.of("example-project"));
        final ItemView item =
                new ItemView(
                        "PVTI_one",
                        "I_one",
                        "example-org/example-project",
                        1,
                        "One",
                        "# One",
                        true,
                        null,
                        Map.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        URI.create("https://github.com/example-org/example-project/issues/1"),
                        java.time.Instant.EPOCH,
                        false);
        return new Snapshot(board, Map.of("PL1", item), CONFIG);
    }

    /** GitHub's writes that answer nothing; only what they touch is recorded. */
    private static ProjectWrites silent() {
        return (ProjectWrites)
                Proxy.newProxyInstance(
                        ProjectWrites.class.getClassLoader(),
                        new Class<?>[] {ProjectWrites.class},
                        (proxy, method, args) -> null);
    }

    /** What the store read: whole reads counted, reads by id recorded, a read by id failing. */
    private static final class Reads implements BoardReader {
        final AtomicInteger whole = new AtomicInteger();
        final List<Touched.Ids> byId = new ArrayList<>();
        @Nullable RuntimeException byIdFailure;
        @Nullable RuntimeException wholeFailure;

        @Override
        public Snapshot read(final ProjectConfig config) {
            final int count = whole.incrementAndGet();
            if (wholeFailure != null) {
                throw wholeFailure;
            }
            return board("r" + count);
        }

        @Override
        public Snapshot reread(
                final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
            byId.add(touched);
            if (byIdFailure != null) {
                throw byIdFailure;
            }
            return board(held.board().revision().value() + "+");
        }
    }

    /** A store whose saves run [save] with a recording of GitHub's writes. */
    private static GitHubStore store(final Reads reads, final Save save) {
        final Touched touched = new Touched();
        final ProjectWrites writes = touched.recording(silent());
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reads, states -> null)
                        .writer(
                                new BoardWriter() {
                                    @Override
                                    public void save(
                                            final Snapshot current,
                                            final Revision revision,
                                            final Writes ignored) {
                                        save.run(current, writes);
                                    }
                                }),
                origin -> TOKEN,
                Duration.ofMillis(200),
                touched);
    }

    /** A save's writes against the board held. */
    private interface Save {
        void run(Snapshot current, ProjectWrites writes);
    }

    private static Writes nothing() {
        return new Writes(Map.of(), Map.of(), List.of());
    }

    @Test
    void aSaveFailingBeforeAnyWriteKeepsTheBoardAndReadsNothingAgain() {
        // Harm: one 502 from GitHub before anything was written drops the board, and every call
        // after it waits behind a whole read of the project, minutes on a large one.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            throw new IllegalStateException("HTTP 502");
                        });
        store.board();

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("the save GitHub refused")
                .isInstanceOf(StoreException.class);
        assertThat(store.board().revision().value()).as("the board held").isEqualTo("r1");
        assertThat(reads.whole.get()).as("whole reads").isEqualTo(1);
        assertThat(reads.byId).as("reads by id of a save that wrote nothing").isEmpty();
    }

    @Test
    void aSaveFailingAfterAWriteReadsAgainOnlyWhatItTouched() {
        // Harm: a save GitHub refused halfway drops the board, and every call waits behind a
        // whole read; or the part written stays unseen on the board held.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            writes.issueTaken("I_one");
                            throw new IllegalStateException("HTTP 502");
                        });
        store.board();

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("the save GitHub refused halfway")
                .isInstanceOf(StoreException.class);
        assertThat(reads.byId)
                .as("the issues read again")
                .singleElement()
                .satisfies(ids -> assertThat(ids.issues()).containsExactly("I_one"));
        assertThat(store.board().revision().value())
                .as("the board held, read again by id")
                .isEqualTo("r1+");
        assertThat(reads.whole.get()).as("whole reads").isEqualTo(1);
    }

    @Test
    void aStaleSaveReadsAgainByIdTheItemNamedAndNamesTheRevisionNow() {
        // Harm: a stale save drops the board and reads it whole under the lock, so every call
        // waits minutes; or the window is told the revision it showed and is refused without end.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            throw new StoreException.StaleRevision(
                                    current.board().revision().value(), "PL1");
                        });
        store.board();

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("a save over an item a person changed")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale ->
                                assertThat(stale.current())
                                        .as("the revision named")
                                        .isEqualTo("r1+"));
        assertThat(reads.byId)
                .as("the item named read again")
                .singleElement()
                .satisfies(ids -> assertThat(ids.issues()).containsExactly("I_one"));
        assertThat(reads.whole.get()).as("whole reads").isEqualTo(1);
        assertThat(store.board().revision().value()).as("the board held").isEqualTo("r1+");
    }

    @Test
    void aSaveWhoseReadAgainFailsDropsTheBoard() {
        // Harm: a board that may hold a part GitHub never showed is served as current.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            writes.issueTaken("I_one");
                            throw new IllegalStateException("HTTP 502");
                        });
        store.board();
        reads.byIdFailure = new IllegalStateException("HTTP 502 again");

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("the save GitHub refused halfway")
                .isInstanceOf(StoreException.class);
        assertThat(store.board().revision().value())
                .as("the board read whole anew")
                .isEqualTo("r2");
        assertThat(reads.whole.get()).as("whole reads").isEqualTo(2);
    }

    @Test
    void aStaleSaveWhoseReadAgainFailsNamesNoRevisionToSaveAgainst() {
        // Harm: the window is told the revision it showed is current, saves against it again
        // and is refused again, the board never read anew.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            throw new StoreException.StaleRevision(
                                    current.board().revision().value(), "PL1");
                        });
        store.board();
        reads.byIdFailure = new IllegalStateException("HTTP 502 again");

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("a save over an item a person changed")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale -> {
                            assertThat(stale.current())
                                    .as("the revision named")
                                    .isEqualTo(GitHubStore.UNREAD);
                            assertThat(stale.changed()).as("the item named").isEqualTo("PL1");
                        });
        assertThat(store.board().revision().value())
                .as("the board read whole anew")
                .isEqualTo("r2");
    }

    @Test
    void aStaleSaveNamingAnItemTheBoardLacksNamesTheBoardReadWholeAnew() {
        // Harm: an item a person added since the board was read stays off it, and the window is
        // told a revision that does not show it.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            throw new StoreException.StaleRevision(
                                    current.board().revision().value(), "PL9");
                        });
        store.board();

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("a save over an item the board lacks")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale ->
                                assertThat(stale.current())
                                        .as("the revision named")
                                        .isEqualTo("r2"));
        assertThat(reads.byId).as("reads by id").isEmpty();
        assertThat(store.board().revision().value()).as("the board held").isEqualTo("r2");
    }

    @Test
    void aStaleSaveNamingAnItemTheBoardLacksNamesNoRevisionWhenTheBoardCannotBeRead() {
        // Harm: the window is told the revision it showed is current and is refused without end.
        final Reads reads = new Reads();
        final GitHubStore store =
                store(
                        reads,
                        (current, writes) -> {
                            throw new StoreException.StaleRevision(
                                    current.board().revision().value(), "PL9");
                        });
        store.board();
        reads.wholeFailure = new IllegalStateException("HTTP 502");

        assertThatThrownBy(() -> store.save(new Revision("r1"), nothing()))
                .as("a save over an item the board lacks")
                .isInstanceOfSatisfying(
                        StoreException.StaleRevision.class,
                        stale ->
                                assertThat(stale.current())
                                        .as("the revision named")
                                        .isEqualTo(GitHubStore.UNREAD));
    }
}
