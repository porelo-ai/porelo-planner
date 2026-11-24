package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.junit.jupiter.api.Test;

/** The projects the window gives the planner, as a replaced or a failed one leaves them. */
class ProjectsTest {

    private final List<String> opened = new CopyOnWriteArrayList<>();

    private final List<String> retired = new CopyOnWriteArrayList<>();

    /** What the saves and the stores did, in order. */
    private final List<String> events = new CopyOnWriteArrayList<>();

    private final CountDownLatch entered = new CountDownLatch(1);

    private final CountDownLatch release = new CountDownLatch(1);

    private CountDownLatch slow = new CountDownLatch(0);

    private boolean failing;

    private PlanningStore open(final String project, final String repository, final String branch) {
        opened.add(project + " " + repository + " " + branch);
        if (project.equals("slow")) {
            try {
                slow.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failing) {
            throw new StoreException.Unreachable("porelo-project", "timed out");
        }
        return new PlanningStore() {
            @Override
            public Board board() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Board refresh() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void retire() {
                events.add("retired " + project);
                retired.add(project + " " + repository);
            }
        };
    }

    private Projects projects() {
        return new Projects(
                (project, repository, branch) -> open(project, repository, "" + branch));
    }

    private Projects projects(final WorkRecord records) {
        return new Projects(
                (project, repository, branch) -> open(project, repository, "" + branch), records);
    }

    /**
     * A save of project alpha held on {@link #release}, which reaches the store through the
     * registry once released, as an edit does, and then writes the record.
     */
    private CompletableFuture<Void> heldSave(final Projects projects, final WorkRecord records) {
        final WorkRun.Record record = records.open("alpha", "git@example.org:a/p.git", null);
        final CompletableFuture<Void> saving =
                CompletableFuture.runAsync(
                        () ->
                                record.locked(
                                        session -> {
                                            entered.countDown();
                                            try {
                                                release.await(10, TimeUnit.SECONDS);
                                            } catch (final InterruptedException interrupted) {
                                                Thread.currentThread().interrupt();
                                            }
                                            projects.store("alpha");
                                            session.write(new WorkRun.Snapshot(1, Map.of()));
                                            events.add("saved alpha");
                                            return null;
                                        }));
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the save is in flight").isTrue();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return saving;
    }

    @Test
    void aProjectGivenAnewStaysGivenUntilItsSaveEndsAndHoldsUpNoOther() throws Exception {
        // Prevents a save in flight failing on its own project, gone before the save ended; the
        // old store retired under a save still using it; and one slow save holding up the
        // giving of every other project.
        final WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofSeconds(10));
        final Projects projects = projects(records);
        projects.put("alpha", "git@example.org:a/p.git", null);
        final PlanningStore old = projects.store("alpha");
        final CompletableFuture<Void> saving = heldSave(projects, records);
        final ThreadPoints<Void> giving =
                ThreadPoints.run(() -> projects.put("alpha", "git@example.org:a/q.git", null));
        giving.reaches("drain");
        try {
            CompletableFuture.runAsync(() -> projects.put("beta", "git@example.org:a/b.git", null))
                    .get(2, TimeUnit.SECONDS);
            assertThat(projects.store("beta")).as("another project given meanwhile").isNotNull();
            assertThat(projects.store("alpha"))
                    .as("the project stays given while its save is in flight")
                    .isSameAs(old);
            assertThat(giving.task().isDone()).as("the give waits for the save").isFalse();
        } finally {
            release.countDown();
        }
        saving.get(5, TimeUnit.SECONDS);
        giving.task().get(5, TimeUnit.SECONDS);
        assertThat(events)
                .as("the old store retired only after the save ended")
                .containsExactly("saved alpha", "retired alpha");
        assertThat(projects.store("alpha")).as("the new store").isNotSameAs(old);
    }

    @Test
    void aGiveWhoseSaveDoesNotEndIsRefusedLeavesTheProjectUnknownAndWaitsOnNoMonitor()
            throws Exception {
        // Prevents a give hanging on a save that never ends, behind a caller that waits for the
        // record while holding a monitor the retired listeners need.
        final WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
        final Projects projects = projects(records);
        projects.put("alpha", "git@example.org:a/p.git", null);
        final WorkRun work = projects.work("alpha");
        final Object monitor = new Object();
        final CountDownLatch holding = new CountDownLatch(1);
        projects.onRetired(
                project -> {
                    synchronized (monitor) {
                        events.add("told " + project);
                    }
                });
        final CompletableFuture<Void> saving = heldSave(projects, records);
        try {
            final ThreadPoints<Void> viewer =
                    ThreadPoints.run(
                            () -> {
                                synchronized (monitor) {
                                    holding.countDown();
                                    try {
                                        work.lockedView((board, snapshot) -> snapshot);
                                    } catch (final RuntimeException refused) {
                                        events.add("viewer refused");
                                    }
                                }
                            });
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
            viewer.reaches("enter");
            final WorkRefusal refused =
                    catchThrowableOfType(
                                                    java.util.concurrent.ExecutionException.class,
                                                    () ->
                                                            CompletableFuture.runAsync(
                                                                            () ->
                                                                                    projects.put(
                                                                                            "alpha",
                                                                                            "git@example.org:a/q.git",
                                                                                            null))
                                                                    .get(3, TimeUnit.SECONDS))
                                            .getCause()
                                    instanceof WorkRefusal workRefusal
                            ? workRefusal
                            : null;
            assertThat(refused).as("the give is refused, not left hanging").isNotNull();
            assertThat(refused.name()).isEqualTo("WorkRecordUnavailable");
            assertThat(refused.parameters()).containsEntry("project", "alpha");
            viewer.task().get(2, TimeUnit.SECONDS);
            assertThatThrownBy(() -> projects.store("alpha"))
                    .as("the project is unknown after the failed give")
                    .isInstanceOf(Refusal.class);
            assertThat(events).contains("viewer refused", "retired alpha", "told alpha");
        } finally {
            release.countDown();
        }
        saving.handle((done, failed) -> null).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRetryAfterATimeOutTouchesNoClonesBeforeTheSaveEnds() throws Exception {
        // Prevents a second give dropping the project's clones while the old record still waits
        // for its save.
        final WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
        final Projects projects = projects(records);
        projects.put("alpha", "git@example.org:a/p.git", null);
        final CompletableFuture<Void> saving = heldSave(projects, records);
        try {
            assertThatThrownBy(() -> projects.put("alpha", "git@example.org:a/q.git", null))
                    .isInstanceOf(WorkRefusal.class);
            assertThatThrownBy(() -> projects.put("alpha", "git@example.org:a/q.git", null))
                    .as("the retry while the save still runs")
                    .isInstanceOf(WorkRefusal.class);
            assertThat(opened)
                    .as("no store opened for the new source while the old save runs")
                    .containsExactly("alpha git@example.org:a/p.git null");
        } finally {
            release.countDown();
        }
        saving.handle((done, failed) -> null).get(5, TimeUnit.SECONDS);
        projects.put("alpha", "git@example.org:a/q.git", null);
        assertThat(opened)
                .as("the new source opened once the save ended")
                .containsExactly(
                        "alpha git@example.org:a/p.git null", "alpha git@example.org:a/q.git null");
    }

    @Test
    void forgettingEveryProjectWaitsForAGiveUnderWay() throws Exception {
        // Prevents the mock reset passing a give under way, which then serves the project the
        // reset forgot.
        final WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofSeconds(10));
        final Projects projects = projects(records);
        projects.put("alpha", "git@example.org:a/p.git", null);
        final CompletableFuture<Void> saving = heldSave(projects, records);
        final ThreadPoints<Void> giving =
                ThreadPoints.run(() -> projects.put("alpha", "git@example.org:a/q.git", null));
        giving.reaches("drain");
        final ThreadPoints<Void> forgetting = ThreadPoints.run(projects::forgetAll);
        try {
            forgetting.reaches("forgetAll");
            assertThat(forgetting.task().isDone())
                    .as("the reset waits for the give under way")
                    .isFalse();
        } finally {
            release.countDown();
        }
        saving.get(5, TimeUnit.SECONDS);
        giving.task().get(5, TimeUnit.SECONDS);
        forgetting.task().get(5, TimeUnit.SECONDS);
        assertThatThrownBy(() -> projects.store("alpha"))
                .as("the project the reset forgot")
                .isInstanceOf(Refusal.class);
    }

    @Test
    void forgettingEveryProjectGoesOnPastASaveThatDoesNotEnd() throws Exception {
        // The mock reset declares no refusal: a save that never ends must not fail it.
        final WorkRecord records = new WorkRecord(Map.of(), true, Duration.ofMillis(200));
        final Projects projects = projects(records);
        projects.put("alpha", "git@example.org:a/p.git", null);
        projects.put("beta", "git@example.org:a/b.git", null);
        final CompletableFuture<Void> saving = heldSave(projects, records);
        try {
            projects.forgetAll();
            assertThatThrownBy(() -> projects.store("alpha")).isInstanceOf(Refusal.class);
            assertThatThrownBy(() -> projects.store("beta")).isInstanceOf(Refusal.class);
        } finally {
            release.countDown();
        }
        saving.handle((done, failed) -> null).get(5, TimeUnit.SECONDS);
    }

    @Test
    void theSameSourceAgainOpensNothingAndAnotherReplacesTheStore() {
        // The window sends its PUT on every choice; opening again would fetch the project anew.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        final PlanningStore first = projects.store("alpha");
        projects.put("alpha", "git@example.org:a/p.git", null);
        assertThat(projects.store("alpha")).as("the same store kept").isSameAs(first);
        projects.put("alpha", "git@example.org:a/p.git", "other");
        assertThat(projects.store("alpha")).as("a new store for a new branch").isNotSameAs(first);
        assertThat(opened).as("opened twice, not three times").hasSize(2);
    }

    @Test
    void aStoreWhoseWorkRecordCannotBeOpenedIsRetired() {
        // Harm: a store opened for a project whose work record is refused stays live, kept
        // nowhere, beside the store the next try opens for the same project.
        final Projects projects = projects();
        // A name the work record refuses before it opens anything, which the opener here takes.
        assertThatThrownBy(() -> projects.put("a..b", "git@example.org:a/p.git", null))
                .as("a project whose work record is refused")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(opened)
                .as("the store opened")
                .containsExactly("a..b git@example.org:a/p.git null");
        assertThat(retired)
                .as("the store retired on the failure")
                .containsExactly("a..b git@example.org:a/p.git");
    }

    @Test
    void aFailedReplacementLeavesTheProjectUnknownNeverTheOldOne() {
        // The old store would answer for a source the window no longer means.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        failing = true;
        assertThatThrownBy(() -> projects.put("alpha", "git@example.org:a/q.git", null))
                .as("the failed open")
                .isInstanceOf(StoreException.Unreachable.class);
        assertThatThrownBy(() -> projects.store("alpha"))
                .as("the project after the failure")
                .isInstanceOfSatisfying(
                        Refusal.class,
                        refusal -> {
                            assertThat(refusal.name()).as("its name").isEqualTo("NoSuchProject");
                            assertThat(refusal.parameters())
                                    .as("what it names")
                                    .containsEntry("project", "alpha");
                        });
    }

    @Test
    void aReplacedStoreIsRetiredBeforeTheNewOneOpens() {
        // The old store would go on fetching its source into the clones the new one reads.
        final Projects projects = projects();
        projects.put("alpha", "git@example.org:a/p.git", null);
        projects.put("alpha", "git@example.org:a/q.git", null);
        assertThat(retired)
                .as("the old store retired")
                .containsExactly("alpha git@example.org:a/p.git");
    }

    @Test
    void aSlowProjectDoesNotHoldUpAnother() throws Exception {
        // One unreachable remote would leave every other project unreadable for a minute.
        final Projects projects = projects();
        slow = new CountDownLatch(1);
        final CompletableFuture<Void> waiting =
                CompletableFuture.runAsync(
                        () -> projects.put("slow", "git@example.org:a/s.git", null));
        Thread.sleep(200);
        final CompletableFuture<Void> other =
                CompletableFuture.runAsync(
                        () -> projects.put("beta", "git@example.org:a/b.git", null));
        try {
            other.get(2, TimeUnit.SECONDS);
            assertThat(projects.store("beta")).as("the other project given meanwhile").isNotNull();
        } finally {
            slow.countDown();
            waiting.get(5, TimeUnit.SECONDS);
        }
    }
}
