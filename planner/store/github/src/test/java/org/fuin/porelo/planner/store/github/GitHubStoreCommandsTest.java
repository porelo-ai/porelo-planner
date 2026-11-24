package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.fuin.porelo.planner.store.Written;
import org.junit.jupiter.api.Test;

/**
 * A group's commands written one at a time: judged once under the lock, each write checked on its
 * own target read again inside it, merged or refused for the owner account when the target moved,
 * and read after once.
 */
class GitHubStoreCommandsTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Ready", "Building", "Done"),
                    List.of(new Workflow.Phase("All", List.of("Ready", "Building", "Done"))),
                    List.of("Done"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    org.fuin.porelo.planner.store.ProjectRepository.named(
                            List.of("example-project")),
                    Map.of(),
                    Map.of());

    private static ItemView item(final String status, final String title) {
        return new ItemView(
                "PVTI_one",
                "I_one",
                "example-project",
                1,
                title,
                "# one",
                true,
                null,
                Map.of(FieldNames.STATUS, status),
                List.of(),
                null,
                List.of(),
                List.of(),
                URI.create("https://github.com/example-org/example-project/issues/1"),
                Instant.EPOCH,
                false);
    }

    private static Snapshot snapshot(final String revision, final ItemView one) {
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(
                                new Issue(
                                        "PL1",
                                        "example-project",
                                        one.title(),
                                        one.fields().get(FieldNames.STATUS),
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "# one")),
                        new Work(Map.of(), List.of()),
                        org.fuin.porelo.planner.store.ProjectRepository.named(
                                List.of("example-project"))),
                Map.of("PL1", one),
                CONFIG);
    }

    private static final ItemView JUDGED = item("Ready", "One");

    /**
     * GitHub as read: the board read whole holds PL1 as judged; a read by id gives {@link #now}.
     */
    private static final class Reads implements BoardReader {
        final List<Set<String>> byId = new ArrayList<>();
        ItemView now = JUDGED;

        @Override
        public Snapshot read(final ProjectConfig config) {
            return snapshot("r1", JUDGED);
        }

        @Override
        public Snapshot reread(
                final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
            byId.add(touched.issues());
            return touched.issues().contains("I_one") ? snapshot("r2", now) : held;
        }
    }

    /** A writer that records each save; the first throws Changed when told. */
    private static final class Saves implements BoardWriter {
        final List<Writes> saved = new ArrayList<>();
        final List<Basis> bases = new ArrayList<>();
        final List<Set<String>> readBefore = new ArrayList<>();
        boolean moved;

        @Override
        public void save(final Snapshot current, final Basis basis, final Writes writes) {
            throw new AssertionError("a write is given what it read before");
        }

        @Override
        public void save(
                final Snapshot current,
                final Basis basis,
                final Writes writes,
                final ReadBefore read) {
            readBefore.add(read.issueIds());
            bases.add(basis);
            if (moved) {
                moved = false;
                throw new StoreException.Changed(List.of("PL1"));
            }
            saved.add(writes);
        }
    }

    private static GitHubStore store(final Reads reads, final Saves saves) {
        final GitHubStore store =
                new GitHubStore(
                        CONFIG,
                        () -> CONFIG,
                        GitHubStore.Parts.reading(reads, states -> null).writer(saves),
                        origin -> TOKEN,
                        Duration.ofMillis(200));
        store.board();
        return store;
    }

    private static Writes building() {
        return new Writes(Map.of("PL1", "Building"), Map.of());
    }

    private static Command.Write move(final Recheck recheck) {
        return new Command.Write(
                "c1",
                "PL1",
                building(),
                Basis.none().with("PL1", ItemVersions.of(JUDGED)),
                recheck);
    }

    @Test
    void aGroupIsJudgedByEveryRuleOnOneRead() {
        // Harm: a later command's rule never run, so its write goes out though the rule refuses it.
        final Reads reads = new Reads();
        final GitHubStore store = store(reads, new Saves());
        final List<String> ran = new ArrayList<>();
        final Recheck first =
                new Recheck(Set.of(Recheck.Need.BLOCKERS), Set.of("PL1"), now -> ran.add("first"));
        final Recheck second =
                new Recheck(Set.of(Recheck.Need.BLOCKERS), Set.of("PL1"), now -> ran.add("second"));

        store.judge(
                new CommandGroup(
                        "g1",
                        List.of(
                                move(first),
                                new Command.Write(
                                        "c2",
                                        "PL1",
                                        building(),
                                        Basis.none().with("PL1", ItemVersions.of(JUDGED)),
                                        second))));

        assertThat(ran).containsExactly("first", "second");
        assertThat(reads.byId).as("one read afresh for both").hasSize(1);
    }

    @Test
    void aWriteReadsItsTargetAgainInsideItAndNothingAfter() {
        // Harm: a write checked on the group's judgment, so a move another made between the
        // judgment and this write is overwritten; or each write paying for a whole read after.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        store.judge(new CommandGroup("g1", List.of(move(Recheck.none()))));
        final int judgedReads = reads.byId.size();

        assertThat(store.writeAlone(move(Recheck.none()))).isInstanceOf(Written.Done.class);

        assertThat(saves.readBefore)
                .as("the writer reads the target itself, right before writing")
                .containsExactly(Set.of());
        assertThat(reads.byId).as("no read after the write").hasSize(judgedReads);

        store.readAfter(Set.of("PL1"));
        assertThat(reads.byId).as("one read after the group").hasSize(judgedReads + 1);
    }

    @Test
    void aTargetThatShowsTheWriteAlreadyIsDoneBeforeAnyMerge() {
        // Harm: a write another made already refused as a conflict for the owner account.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        reads.now = item("Building", "One");
        saves.moved = true;

        assertThat(store.writeAlone(move(Recheck.none()))).isInstanceOf(Written.Done.class);
        assertThat(saves.saved).as("nothing written again").isEmpty();
    }

    @Test
    void aMovedTargetWithoutItsJudgedItemIsAConflictOfTheItem() {
        // Harm: a write merged onto a guess of what it was judged on, after a restart forgot it.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        reads.now = item("Ready", "One renamed");
        saves.moved = true;

        assertThat(store.writeAlone(move(Recheck.none())))
                .isEqualTo(new Written.Conflict(List.of("ITEM")));
        assertThat(saves.saved).isEmpty();
    }

    @Test
    void aTargetMovedElsewhereIsWrittenAgainstItsVersionNow() {
        // Harm: a write refused because another changed a place it neither writes nor read.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        store.judge(new CommandGroup("g1", List.of(move(Recheck.none()))));
        final ItemView now = item("Ready", "One renamed");
        reads.now = now;
        saves.moved = true;

        assertThat(store.writeAlone(move(Recheck.none()))).isInstanceOf(Written.Done.class);

        assertThat(saves.saved).containsExactly(building());
        assertThat(saves.bases.getLast().of("PL1")).isEqualTo(ItemVersions.of(now));
    }

    @Test
    void aTargetMovedWhereTheWriteWritesIsAConflictNamingThePlace() {
        // Harm: another's move overwritten, or the owner account not told where it lies.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        store.judge(new CommandGroup("g1", List.of(move(Recheck.none()))));
        reads.now = item("Done", "One");
        saves.moved = true;

        assertThat(store.writeAlone(move(Recheck.none())))
                .isEqualTo(new Written.Conflict(List.of("STATE")));
        assertThat(saves.saved).isEmpty();
    }

    @Test
    void theReadAfterReadsEveryTargetTheGroupWroteByItsIds() {
        // Harm: the read after taken from what the last call to GitHub recorded, which another
        // save between the group's writes and its read after clears, so the board keeps showing a
        // target as it stood before the group.
        final Reads reads = new Reads();
        final GitHubStore store = store(reads, new Saves());
        store.writeAlone(move(Recheck.none()));

        store.readAfter(Set.of("PL1"));

        assertThat(reads.byId.getLast()).as("the target's issue read again").contains("I_one");
    }
}
