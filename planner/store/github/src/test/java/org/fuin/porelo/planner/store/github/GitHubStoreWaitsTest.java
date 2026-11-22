package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.forge.github.GitHubGraphQl;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.junit.jupiter.api.Test;

/**
 * The issues the board waits on outside the project are read again when their repositories change,
 * and once more when work on a waiting issue starts.
 */
class GitHubStoreWaitsTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Backlog", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Backlog", "Finished"))),
                    List.of("Finished"));

    private static final ProjectConfig CONFIG =
            new ProjectConfig(
                    URI.create("https://github.com"),
                    new ProjectRef("example-org", 7),
                    "c0ffee0",
                    WORKFLOW,
                    new Work(Map.of(), List.of()),
                    List.of("example-lib"),
                    Map.of(),
                    Map.of());

    private static final URI FAR_LINK =
            URI.create("https://github.com/other-org/elsewhere/issues/7");

    /** A board whose issue example-lib#3 waits on other-org/elsewhere#7, done or not. */
    private static Snapshot board(final String revision, final boolean done) {
        return board(revision, done, false);
    }

    /**
     * A board whose issue example-lib#3 waits on other-org/elsewhere#7, done or not, and when
     * {@code hidden} on a blocker GitHub counts but does not name.
     */
    private static Snapshot board(final String revision, final boolean done, final boolean hidden) {
        final ItemView item =
                new ItemView(
                        "PVTI_3",
                        "I_lib_3",
                        "example-lib",
                        3,
                        "Waiting",
                        "",
                        true,
                        null,
                        Map.of(FieldNames.STATUS, "Backlog"),
                        List.of(),
                        null,
                        List.of(),
                        List.of("I_far_7"),
                        URI.create("https://github.com/example-org/example-lib/issues/3"),
                        Instant.EPOCH,
                        false,
                        List.of(new ItemView.Blocker("I_far_7", "other-org/elsewhere", 7)),
                        hidden ? 1 : 0);
        final Issue issue =
                new Issue(
                        "example-lib#3",
                        "example-lib",
                        "Waiting",
                        "Backlog",
                        null,
                        List.of(),
                        null,
                        item.link(),
                        "",
                        Worker.AGENT,
                        Risk.ORDINARY,
                        null,
                        null,
                        null,
                        List.of(),
                        hidden
                                ? List.of(
                                        new Wait("other-org/elsewhere#7", done, FAR_LINK),
                                        Wait.unreadable())
                                : List.of(new Wait("other-org/elsewhere#7", done, FAR_LINK)));
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        List.of(issue),
                        new Work(Map.of(), List.of()),
                        CONFIG.repositories()),
                Map.of("example-lib#3", item),
                CONFIG,
                Map.of(),
                List.of(),
                Map.of(),
                Map.of(),
                Map.of("I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, done)),
                List.of());
    }

    /** A probe that sees no change of the project, and a change of the waits when told. */
    private static final class Probe implements ChangeProbe {

        private final AtomicBoolean waitsChanged = new AtomicBoolean();

        private final AtomicInteger waitsRead = new AtomicInteger();

        @Override
        public boolean unchanged(final Snapshot current) {
            return true;
        }

        /** What runs while the probe asks, such as a read that replaces the board meanwhile. */
        private volatile Runnable meanwhile = () -> {};

        @Override
        public boolean waitsChanged(final Snapshot current) {
            meanwhile.run();
            return waitsChanged.get();
        }

        @Override
        public void waitsRead() {
            waitsRead.incrementAndGet();
        }
    }

    /** A reader whose whole read and whose read of the waits are counted. */
    private static final class Reader implements BoardReader {

        private final AtomicInteger wholeReads = new AtomicInteger();

        private final AtomicInteger waitReads = new AtomicInteger();

        private volatile Map<String, Snapshot.Waited> now =
                Map.of("I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, false));

        /** Whether the board read names a blocker GitHub counts but does not name. */
        private volatile boolean hidden;

        @Override
        public Snapshot read(final ProjectConfig config) {
            wholeReads.incrementAndGet();
            return board("whole-" + wholeReads.get(), false, hidden);
        }

        @Override
        public Snapshot rewait(final ProjectConfig config, final Snapshot held) {
            waitReads.incrementAndGet();
            return board("waits-" + waitReads.get(), true);
        }

        /** The blockers GitHub names now for the issues, or null for a reader that reads none. */
        private volatile Map<String, List<GitHubGraphQl.IssueRef>> blockersNow = Map.of();

        private volatile Set<String> unread = Set.of();

        @Override
        public StartRead waitsOf(
                final ProjectConfig config, final Snapshot held, final Set<String> issueIds) {
            assertThat(issueIds).as("the issues whose waits are read").containsExactly("I_lib_3");
            return new StartRead(blockersNow, unread, now);
        }
    }

    private static GitHubStore store(final Reader reader, final Probe probe) {
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reader, states -> null).probe(probe),
                origin -> TOKEN,
                Duration.ofMillis(500));
    }

    @Test
    void aLookAfterAWaitsRepositoryChangedReadsTheWaitsAloneAndShowsTheBoardTheyGive() {
        // Harm: a blocker closed in another repository that the board never takes in, so its
        // issue is never offered, or a whole read of the project for it at every look.
        final Reader reader = new Reader();
        final Probe probe = new Probe();
        final GitHubStore store = store(reader, probe);
        store.refresh();

        assertThat(store.poll().revision().value()).as("nothing changed").isEqualTo("whole-1");
        probe.waitsChanged.set(true);
        final Board after = store.poll();

        assertThat(after.revision().value()).as("the board the waits give").isEqualTo("waits-1");
        assertThat(after.issue("example-lib#3").orElseThrow().waitsOn())
                .as("the wait done")
                .extracting(Wait::done)
                .containsExactly(true);
        assertThat(reader.wholeReads.get()).as("no whole read for the waits").isEqualTo(1);
        assertThat(probe.waitsRead.get()).as("the probe told the waits were read").isEqualTo(1);
    }

    @Test
    void theWaitsAStartAsksForAreReadNowNotTakenFromTheBoard() {
        // Harm: a start on an issue whose blocker opened again since the board was read, so an
        // agent builds on work that is not there.
        final Reader reader = new Reader();
        final GitHubStore store = store(reader, new Probe());
        store.refresh();
        assertThat(store.board().issue("example-lib#3").orElseThrow().waitsOn())
                .as("the board's wait")
                .extracting(Wait::done)
                .containsExactly(false);
        reader.now = Map.of("I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, null));

        assertThat(store.waitsNow(List.of("example-lib#3", "example-lib#99")))
                .as("the wait as read now, an issue the board lacks left out")
                .containsExactly(
                        Map.entry(
                                "example-lib#3",
                                List.of(new Wait("other-org/elsewhere#7", null, FAR_LINK))));
    }

    @Test
    void aStartSeesABlockerAddedSinceTheBoardWasReadAndOneWhoseBlockersItCannotRead() {
        // Harm: a blocker a person added in another repository within one look is missed, and
        // work starts on an issue that waits on it.
        final Reader reader = new Reader();
        final GitHubStore store = store(reader, new Probe());
        store.refresh();
        reader.now =
                Map.of(
                        "I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, true),
                        "I_new_9", new Snapshot.Waited("other-org/new", 9, null));
        reader.blockersNow =
                Map.of(
                        "I_lib_3",
                        List.of(
                                new GitHubGraphQl.IssueRef(
                                        "I_far_7", 70L, 7, "other-org/elsewhere"),
                                new GitHubGraphQl.IssueRef("I_new_9", 90L, 9, "other-org/new")));

        assertThat(store.waitsNow(List.of("example-lib#3")).get("example-lib#3"))
                .as("the known wait done, the new one not known done")
                .extracting(Wait::reference, Wait::done)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("other-org/elsewhere#7", true),
                        org.assertj.core.groups.Tuple.tuple("other-org/new#9", null));

        reader.blockersNow = Map.of();
        reader.unread = Set.of("I_lib_3");
        assertThat(store.waitsNow(List.of("example-lib#3")).get("example-lib#3"))
                .as("an issue whose blockers could not be read whole")
                .contains(Wait.unreadable());
    }

    @Test
    void aStartOnAnIssueWithOneBlockerGitHubDoesNotNameListsOneWaitNotReadable() {
        // Harm: a start names one blocker the person cannot read twice, so the person is told the
        // issue waits on two things not readable where it waits on one.
        final Reader reader = new Reader();
        reader.hidden = true;
        final GitHubStore store = store(reader, new Probe());
        store.refresh();
        reader.now = Map.of("I_far_7", new Snapshot.Waited("other-org/elsewhere", 7, true));
        reader.blockersNow =
                Map.of(
                        "I_lib_3",
                        List.of(
                                new GitHubGraphQl.IssueRef(
                                        "I_far_7", 70L, 7, "other-org/elsewhere")));
        reader.unread = Set.of("I_lib_3");

        assertThat(store.waitsNow(List.of("example-lib#3")).get("example-lib#3"))
                .as("the named wait read now, and the one not readable once")
                .containsExactly(
                        new Wait("other-org/elsewhere#7", true, FAR_LINK), Wait.unreadable());
    }

    @Test
    void aChangeOfTheWaitsSeenWhileTheBoardWasReplacedIsNotTakenAsRead() {
        // Harm: a look that skipped reading the waits tells the probe it read them, so the change
        // it saw is lost and a closed blocker never frees its issue.
        final Reader reader = new Reader();
        final Probe probe = new Probe();
        final GitHubStore store = store(reader, probe);
        store.refresh();
        store.poll();
        probe.waitsChanged.set(true);
        probe.meanwhile =
                () -> {
                    probe.meanwhile = () -> {};
                    store.refresh();
                };

        store.poll();

        assertThat(reader.waitReads.get()).as("the waits not read on a replaced board").isZero();
        assertThat(probe.waitsRead.get()).as("the probe not told they were read").isZero();
    }
}
