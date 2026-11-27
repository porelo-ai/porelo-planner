package org.fuin.porelo.planner.store.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.core.Rules;
import org.fuin.porelo.planner.store.Basis;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Recheck;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.Risk;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.fuin.porelo.planner.store.Writes;
import org.junit.jupiter.api.Test;

/**
 * A write's rules run inside the store's write lock on the dependencies read afresh from GitHub,
 * not on the board the store held when the caller read it.
 */
class GitHubStoreRecheckTest {

    private static final String TOKEN = "invented-token-0123456789";

    private static final Workflow WORKFLOW =
            new Workflow(
                    List.of("Idea", "Backlog", "Finished"),
                    List.of(new Workflow.Phase("All", List.of("Idea", "Backlog", "Finished"))),
                    List.of("Finished"));

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

    /** An item of the project; the issue node is I_[name], blocked by the nodes named. */
    private static ItemView item(
            final String name, final int number, final boolean open, final List<String> blockers) {
        return new ItemView(
                "PVTI_" + name,
                "I_" + name,
                "example-project",
                number,
                name,
                "# " + name,
                open,
                open ? null : "completed",
                Map.of(),
                List.of(),
                null,
                List.of(),
                blockers,
                URI.create("https://github.com/example-org/example-project/issues/" + number),
                Instant.EPOCH,
                false);
    }

    /**
     * The board at a revision: PL1 blocked by PL2, PL2 in the state given, and PL3 apart; the items
     * keyed by the board's ids.
     */
    private static Snapshot board(final String revision, final boolean blockerOpen) {
        final Map<String, ItemView> items = new LinkedHashMap<>();
        items.put("PL1", item("one", 1, true, List.of("I_two")));
        items.put("PL2", item("two", 2, blockerOpen, List.of()));
        items.put("PL3", item("three", 3, true, List.of()));
        final List<Issue> issues = new ArrayList<>();
        issues.add(issue("PL1", "Idea", List.of("PL2")));
        issues.add(issue("PL2", blockerOpen ? "Backlog" : "Finished", List.of()));
        issues.add(issue("PL3", "Idea", List.of()));
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        issues,
                        new Work(Map.of(), List.of()),
                        org.fuin.porelo.planner.store.ProjectRepository.named(
                                List.of("example-project"))),
                items,
                CONFIG);
    }

    private static Issue issue(final String id, final String state, final List<String> blockers) {
        return new Issue(id, "example-project", id, state, "S", blockers, null, null, "# " + id);
    }

    /**
     * GitHub as the store reads it: the board read whole has PL2 open; another client closed PL2
     * since, so a read by id of PL2 gives it closed. Every read by id is recorded.
     */
    private static class Reads implements BoardReader {
        final List<Set<String>> byId = new ArrayList<>();

        @Override
        public Snapshot read(final ProjectConfig config) {
            return board("r1", true);
        }

        @Override
        public Snapshot reread(
                final ProjectConfig config, final Snapshot held, final Touched.Ids touched) {
            byId.add(touched.issues());
            if (!touched.issues().contains("I_two")) {
                return held;
            }
            return board(held.board().revision().value() + "+", false);
        }
    }

    /** A writer that records each save it is given and writes nothing. */
    private static final class Saves implements BoardWriter {
        final List<Writes> saved = new ArrayList<>();

        @Override
        public void save(final Snapshot current, final Basis basis, final Writes writes) {
            saved.add(writes);
        }
    }

    private static GitHubStore store(final Reads reads, final Saves saves) {
        return new GitHubStore(
                CONFIG,
                () -> CONFIG,
                GitHubStore.Parts.reading(reads, states -> null).writer(saves),
                origin -> TOKEN,
                Duration.ofMillis(200));
    }

    /** The rule a move into work checks: every blocker of the issue is closed. */
    private static Recheck blockersClosed(final List<String> seen) {
        return new Recheck(
                Set.of(Recheck.Need.BLOCKERS),
                Set.of("PL1"),
                now -> {
                    final Board board = now.board();
                    for (final String blocker : board.issue("PL1").orElseThrow().blockedBy()) {
                        final String state = board.issue(blocker).orElseThrow().state();
                        seen.add(blocker + " " + state);
                        if (!board.workflow().closed().contains(state)) {
                            throw new StoreException.NotSavable("PL1", "blocked by " + blocker);
                        }
                    }
                });
    }

    private static Writes moved() {
        return new Writes(Map.of("PL1", "Backlog"), Map.of());
    }

    @Test
    void aBlockerClosedByAnotherClientAfterTheCallersReadCountsAsClosed() {
        // Harm: the rule judges the blocker by the board held when the caller read it, so a
        // move another client's close has freed is refused as blocked.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        assertThat(store.board().issue("PL2").orElseThrow().state())
                .as("the blocker as the caller read it")
                .isEqualTo("Backlog");
        final List<String> seen = new ArrayList<>();

        store.save(
                Basis.none().with("PL1", ItemVersions.of(item("one", 1, true, List.of("I_two")))),
                moved(),
                blockersClosed(seen));

        assertThat(seen).as("the blocker the rule judged").containsExactly("PL2 Finished");
        assertThat(saves.saved).as("the saves written").containsExactly(moved());
        assertThat(reads.byId.getFirst())
                .as("the first read by id, from the issue moved")
                .containsExactly("I_one");
        assertThat(reads.byId.get(1)).as("the walk to its blocker").containsExactly("I_two");
    }

    @Test
    void aWriteWithoutARuleReadsNothingForIt() {
        // Harm: every write pays for reads of dependencies it has no rule over.
        final Reads reads = new Reads();
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        store.board();

        store.save(Basis.none(), moved(), Recheck.none());

        assertThat(saves.saved).as("the saves written").containsExactly(moved());
        assertThat(reads.byId)
                .as("the reads by id, only the one after the write")
                .hasSize(1)
                .allSatisfy(ids -> assertThat(ids).doesNotContain("I_two"));
    }

    @Test
    void aRuleThatRefusesWritesNothing() {
        // Harm: a write goes out although its rule, checked on what GitHub holds now, refuses it.
        final Reads reads =
                new Reads() {
                    @Override
                    public Snapshot reread(
                            final ProjectConfig config,
                            final Snapshot held,
                            final Touched.Ids touched) {
                        byId.add(touched.issues());
                        return held;
                    }
                };
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        store.board();

        assertThatThrownBy(
                        () -> store.save(Basis.none(), moved(), blockersClosed(new ArrayList<>())))
                .as("the move whose blocker is still open")
                .isInstanceOf(StoreException.NotSavable.class);
        assertThat(saves.saved).as("the saves written").isEmpty();
    }

    /** Backlog is the working state of one work, so no agent's issue may stand in it unstarted. */
    private static final Work BUILDING =
            new Work(
                    Map.of(
                            "Backlog",
                            new WorkRule(
                                    List.of("Idea"),
                                    "Finished",
                                    null,
                                    null,
                                    WorkRule.Per.STORY,
                                    List.of("UNATTENDED"),
                                    "Build {story}",
                                    Map.of("codex", "gpt"),
                                    Roles.AUTHOR)),
                    List.of());

    /**
     * The board at a revision: PL1, a person's issue, with its sub-issue PL1.1 in the state given;
     * the items keyed by the board's ids.
     */
    private static Snapshot parentAndSub(final String revision, final String subState) {
        final ItemView parent = item("one", 1, true, List.of());
        final ItemView sub = item("four", 4, true, List.of());
        final Map<String, ItemView> items = new LinkedHashMap<>();
        items.put(
                "PL1",
                new ItemView(
                        parent.itemId(),
                        parent.issueId(),
                        parent.repository(),
                        parent.number(),
                        parent.title(),
                        parent.body(),
                        true,
                        null,
                        Map.of(),
                        List.of(),
                        null,
                        List.of(sub.issueId()),
                        List.of(),
                        parent.link(),
                        Instant.EPOCH,
                        false));
        items.put(
                "PL1.1",
                new ItemView(
                        sub.itemId(),
                        sub.issueId(),
                        sub.repository(),
                        sub.number(),
                        sub.title(),
                        sub.body(),
                        true,
                        null,
                        Map.of(),
                        List.of(),
                        parent.issueId(),
                        List.of(),
                        List.of(),
                        sub.link(),
                        Instant.EPOCH,
                        false));
        final List<Issue> issues =
                List.of(
                        new Issue(
                                        "PL1",
                                        "example-project",
                                        "PL1",
                                        "Idea",
                                        "S",
                                        List.of(),
                                        null,
                                        null,
                                        "# PL1")
                                .withWorkerAndRisk(Worker.MANUAL, Risk.ORDINARY),
                        new Issue(
                                        "PL1.1",
                                        "example-project",
                                        "PL1.1",
                                        subState,
                                        "S",
                                        List.of(),
                                        "PL1",
                                        null,
                                        "# PL4")
                                .withWorkerAndRisk(Worker.MANUAL, Risk.ORDINARY));
        return new Snapshot(
                new Board(
                        new Revision(revision),
                        WORKFLOW,
                        List.of(),
                        issues,
                        BUILDING,
                        org.fuin.porelo.planner.store.ProjectRepository.named(
                                List.of("example-project"))),
                items,
                CONFIG);
    }

    @Test
    void aWorkerHandedToAnAgentWhileASubIssueIsLiveInAWorkingStateIsRefused() {
        // Harm: an agent's issue whose sub-issue another client moved into a working state since
        // the board was read waits there forever, as no task entered that state.
        final Reads reads =
                new Reads() {
                    @Override
                    public Snapshot read(final ProjectConfig config) {
                        return parentAndSub("r1", "Idea");
                    }

                    @Override
                    public Snapshot reread(
                            final ProjectConfig config,
                            final Snapshot held,
                            final Touched.Ids touched) {
                        byId.add(touched.issues());
                        return touched.issues().contains("I_four")
                                ? parentAndSub(held.board().revision().value() + "+", "Backlog")
                                : held;
                    }
                };
        final Saves saves = new Saves();
        final GitHubStore store = store(reads, saves);
        assertThat(store.board().issue("PL1.1").orElseThrow().state())
                .as("the sub-issue as the caller read it")
                .isEqualTo("Idea");
        final Writes writes = new Writes(Map.of(), Map.of(), Map.of("PL1", Worker.AGENT));

        assertThatThrownBy(
                        () ->
                                store.save(
                                        Basis.none(),
                                        writes,
                                        Rules.workerRecheck("PL1", Worker.AGENT)))
                .as("the worker change over a sub-issue now in work")
                .hasMessageContaining("PL1.1 stands in Backlog");
        assertThat(saves.saved).as("the saves written").isEmpty();
    }
}
