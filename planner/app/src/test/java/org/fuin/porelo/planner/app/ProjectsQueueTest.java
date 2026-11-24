package org.fuin.porelo.planner.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Command;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.ProjectRepository;
import org.fuin.porelo.planner.store.Revision;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.Work;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A GitHub project's saves handed to one queue per project, kept in an outbox beside its durable
 * work record: opened under the record's file guard, reconciled with the runs' pending marks,
 * resumed once the project is given, and drained before the project is given anew.
 */
class ProjectsQueueTest {

    private static final String A = "git@example.org:a/porelo-store.git";

    private static final String B = "git@example.org:b/porelo-store.git";

    private static final String GROUP_NONE = "00000000-0000-0000-0000-0000000000a1";

    private static final String GROUP_DONE = "00000000-0000-0000-0000-0000000000a2";

    private static final String GROUP_AGAIN = "00000000-0000-0000-0000-0000000000a3";

    private static final String GROUP_WINDOW = "00000000-0000-0000-0000-0000000000a4";

    @TempDir Path directory;

    /** What every queue and the registry did, in order. */
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    private final List<Fake> made = Collections.synchronizedList(new ArrayList<>());

    private final List<WorkRecord> opened = new ArrayList<>();

    private final List<Store> stores = new ArrayList<>();

    private Tracker tracker = Tracker.GITHUB;

    /** The groups a command of which was begun before, settled back to wait at open. */
    private java.util.Set<String> begunBefore = java.util.Set.of();

    /** Null makes the outbox fail to open. */
    private @Nullable List<CommandOutbox.Group> pending = List.of();

    @AfterEach
    void close() {
        opened.forEach(WorkRecord::close);
    }

    private WorkRecord durable() {
        final WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false);
        opened.add(records);
        return records;
    }

    /** Holds each store's write until counted down; null lets it write at once. */
    private volatile @Nullable CountDownLatch release;

    /** Counted down when a store's write began. */
    private final CountDownLatch atStore = new CountDownLatch(1);

    /** The board each store opens with. */
    private Board opening = boardAt("r1");

    /** A registry over real command runners and outboxes, its stores those of this test. */
    private Projects real(final WorkRecord records) {
        return new Projects(
                (project, repository, branch) -> {
                    final Store store = new Store(tracker);
                    store.board = opening;
                    store.release = release;
                    store.atStore = atStore;
                    stores.add(store);
                    return store;
                },
                records,
                new RunnerQueues());
    }

    private WorkRecord durable(final Duration drain) {
        final WorkRecord records =
                new WorkRecord(Map.of("XDG_STATE_HOME", directory.toString()), false, drain);
        opened.add(records);
        return records;
    }

    /** The bean's destroy method as the planner's configuration names it. */
    private static String destroyOf(final String bean) {
        for (final java.lang.reflect.Method method :
                StoreConfiguration.class.getDeclaredMethods()) {
            if (method.getName().equals(bean)) {
                return method.getAnnotation(org.springframework.context.annotation.Bean.class)
                        .destroyMethod();
            }
        }
        throw new IllegalArgumentException(bean);
    }

    /** A context holding the record and the registry as the planner's configuration makes them. */
    private org.springframework.context.support.GenericApplicationContext context(
            final WorkRecord records) {
        final org.springframework.context.support.GenericApplicationContext context =
                new org.springframework.context.support.GenericApplicationContext();
        context.registerBean(
                "workRecord",
                WorkRecord.class,
                () -> records,
                bean -> bean.setDestroyMethodName(destroyOf("workRecord")));
        context.registerBean(
                "projects",
                Projects.class,
                () -> real(context.getBean(WorkRecord.class)),
                bean -> {
                    bean.setDestroyMethodName(destroyOf("projects"));
                    // As the configuration's parameter makes it: the registry is closed first.
                    bean.setDependsOn("workRecord");
                });
        context.refresh();
        return context;
    }

    /** Whether a second planner on the same state directory is refused its record now. */
    private boolean guardHeld() {
        return catchThrowable(() -> durable().open("alpha", A, null)) instanceof WorkRefusal;
    }

    private CompletableFuture<CommandOutcome> windowSave(final Projects projects) {
        final String group = java.util.UUID.randomUUID().toString();
        return CompletableFuture.supplyAsync(
                () ->
                        projects.queue("alpha", projects.store("alpha"))
                                .submit(
                                        new CommandGroup(
                                                group,
                                                List.of(
                                                        new Command.Comment(
                                                                group + "/0", "AA1", "x")))));
    }

    @Test
    void theShutdownDrainsEveryQueueBeforeTheRecordReleasesItsGuard() throws Exception {
        // Harm: a second planner opening the outbox while this one still writes a command of it.
        release = new CountDownLatch(1);
        final var context = context(durable(Duration.ofSeconds(10)));
        final Projects projects = context.getBean(Projects.class);
        projects.put("alpha", A, null);
        final CompletableFuture<CommandOutcome> saving = windowSave(projects);
        assertThat(atStore.await(5, TimeUnit.SECONDS)).as("the group is at the store").isTrue();

        final CompletableFuture<Void> closing = CompletableFuture.runAsync(context::close);

        assertThat(catchThrowable(() -> closing.get(500, TimeUnit.MILLISECONDS)))
                .as("the shutdown waits for the group at the store")
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(guardHeld()).as("the guard held while the group writes").isTrue();
        release.countDown();
        closing.get(5, TimeUnit.SECONDS);
        assertThat(saving.get(5, TimeUnit.SECONDS)).isInstanceOf(CommandOutcome.Done.class);
        assertThat(guardHeld()).as("released once the group ended").isFalse();
    }

    @Test
    void aQueueNotDrainedAtShutdownKeepsItsRecordsGuardUntilThePlannerEnds() throws Exception {
        // Harm: the guard released early, so a second planner sends again what this one writes.
        release = new CountDownLatch(1);
        final var context = context(durable(Duration.ofMillis(200)));
        final Projects projects = context.getBean(Projects.class);
        projects.put("alpha", A, null);
        final CompletableFuture<CommandOutcome> saving = windowSave(projects);
        assertThat(atStore.await(5, TimeUnit.SECONDS)).as("the group is at the store").isTrue();

        CompletableFuture.runAsync(context::close).get(5, TimeUnit.SECONDS);

        assertThat(guardHeld()).as("the guard stays held").isTrue();
        release.countDown();
        saving.get(5, TimeUnit.SECONDS);
    }

    @Test
    void aRestartSettlesTheRunsGroupsBeforeRepairAndWritesNothingTwice() throws Exception {
        // Harm: a run's command written twice after a restart, or a run's waiting group sent
        // behind its halt.
        final String flight = "00000000-0000-0000-0000-0000000000b1";
        final String waiting = "00000000-0000-0000-0000-0000000000b2";
        opening = finished("r1");
        final WorkRecord before = durable();
        before.open("alpha", A, null)
                .locked(
                        session -> {
                            session.write(
                                    new WorkRun.Snapshot(
                                            1,
                                            Map.of(
                                                    "ST1", marked("ST1", "1", flight),
                                                    "ST2", marked("ST2", "2", waiting))));
                            return true;
                        });
        final ProjectQueues.Place place = before.place("alpha");
        final CommandOutbox outbox =
                new CommandOutbox(
                        place.environment(),
                        place.directory(),
                        place.project(),
                        place.repository(),
                        place.branch(),
                        place.files(),
                        place.limits());
        outbox.open(() -> opening);
        final Command.Write written = finish(flight);
        outbox.enqueue(new CommandGroup(flight, List.of(written)));
        outbox.begin(written.id());
        outbox.enqueue(new CommandGroup(waiting, List.of(finish(waiting))));
        outbox.close();
        before.close();

        final WorkRecord records = durable();
        final Projects projects = real(records);
        final CountDownLatch resumed = new CountDownLatch(1);
        projects.onSaved(project -> resumed.countDown());
        projects.put("alpha", A, null);
        assertThat(resumed.await(5, TimeUnit.SECONDS)).as("the resume ended").isTrue();

        assertThat(stores.getLast().writes).as("nothing written again").isZero();
        assertThat(projects.held("alpha").groups())
                .as("neither run's group waits to be sent")
                .allSatisfy(
                        group ->
                                assertThat(group.commands())
                                        .allSatisfy(
                                                told ->
                                                        assertThat(told.state())
                                                                .isNotEqualTo(
                                                                        CommandOutbox.State
                                                                                .QUEUED)));
        final WorkRun.Snapshot after = records.open("alpha", A, null).locked(WorkRun.Session::read);
        assertThat(after.runs().values())
                .allSatisfy(
                        run -> {
                            assertThat(run.halted()).as("halted").isTrue();
                            assertThat(run.pending()).as("mark cleared").isNull();
                        });
    }

    /** A run's command finishing ST1, as the store already shows it. */
    private Command.Write finish(final String group) {
        return new Command.Write(
                group + "/0",
                "ST1",
                new org.fuin.porelo.planner.store.Writes(Map.of(), Map.of("ST1", "Finished")),
                org.fuin.porelo.planner.store.Basis.of(opening),
                org.fuin.porelo.planner.store.Recheck.none());
    }

    private static Board finished(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(
                        new org.fuin.porelo.planner.store.Story(
                                "ST1", "Story", "Finished", "High", null, List.of(), "", null)),
                List.of(),
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("porelo-store")));
    }

    /** The registry the last queue was made for. */
    private @Nullable Projects current;

    private Projects projects(final WorkRecord records) {
        final Projects projects =
                new Projects(
                        (project, repository, branch) -> {
                            final Store store = new Store(tracker);
                            stores.add(store);
                            return store;
                        },
                        records,
                        (place, store, saved) -> {
                            final Fake fake = new Fake(place.repository(), store, saved);
                            made.add(fake);
                            return fake;
                        });
        current = projects;
        return projects;
    }

    @Test
    void aGitHubProjectsWindowAndRunsWriteThroughItsOneQueueResumedOnceGiven() throws Exception {
        // Harm: a run and a window save of one issue on two queues, racing; or groups resumed for
        // a project nobody can read yet.
        final Projects projects = projects(durable());

        projects.put("alpha", A, null);

        assertThat(made).hasSize(1);
        final Fake queue = made.getFirst();
        assertThat(projects.queue("alpha", projects.store("alpha"))).isSameAs(queue);
        final java.lang.reflect.Field field = WorkRun.class.getDeclaredField("queue");
        field.setAccessible(true);
        assertThat(field.get(projects.work("alpha"))).as("the run's queue").isSameAs(queue);
        assertThat(events).containsExactly("open " + A, "resume " + A);
        assertThat(queue.resumedGiven).as("resumed once the project is given").isTrue();
    }

    @Test
    void aGitOrMemoryProjectKeepsTheDirectQueueAndNoOutbox() {
        // Harm: a Git store's move split into a commit per item, or the mock mode writing a file.
        tracker = Tracker.GIT;
        final Projects git = projects(durable());
        git.put("alpha", A, null);
        tracker = Tracker.GITHUB;
        final Projects memory = projects(new WorkRecord(Map.of(), true));
        memory.put("alpha", A, null);

        assertThat(made).isEmpty();
        assertThat(git.queue("alpha", git.store("alpha"))).isInstanceOf(DirectQueue.class);
        assertThat(memory.queue("alpha", memory.store("alpha"))).isInstanceOf(DirectQueue.class);
    }

    @Test
    void givenAnewTheOldQueueEndsItsGroupBeforeTheNewOutboxOpens() throws Exception {
        // Harm: an old runner still writing a command while a new outbox opens and runs it again,
        // so it is written twice.
        final Projects projects = projects(durable());
        projects.put("alpha", A, null);
        final Fake old = made.getFirst();
        old.hold = new CountDownLatch(1);
        final CompletableFuture<CommandOutcome> running =
                CompletableFuture.supplyAsync(
                        () ->
                                old.submit(
                                        new CommandGroup(
                                                GROUP_WINDOW,
                                                List.of(
                                                        new Command.Comment(
                                                                GROUP_WINDOW + "/0",
                                                                "AA1",
                                                                "A note.")))));
        assertThat(old.inside.await(5, TimeUnit.SECONDS)).as("the group is writing").isTrue();

        final CompletableFuture<Void> anew =
                CompletableFuture.runAsync(() -> projects.put("alpha", B, null));
        Thread.sleep(200);
        assertThat(made).as("no new outbox while the old group writes").hasSize(1);
        old.hold.countDown();
        anew.get(5, TimeUnit.SECONDS);
        running.get(5, TimeUnit.SECONDS);

        assertThat(old.writes.get()).as("written once").isEqualTo(1);
        assertThat(events)
                .containsSubsequence("write " + A, "drained " + A, "open " + B, "resume " + B);
    }

    @Test
    void aQueueNotDrainedInTimeLeavesTheProjectUnknownAndIsDrainedFirstNextTime() {
        // Harm: a project given again beside a runner of before that still writes.
        final Projects projects = projects(durable());
        projects.put("alpha", A, null);
        final Fake old = made.getFirst();
        old.drainFails = 1;

        assertThat(catchThrowable(() -> projects.put("alpha", B, null)))
                .isInstanceOf(WorkRefusal.class);
        assertThat(catchThrowable(() -> projects.store("alpha")))
                .as("the project is unknown")
                .isInstanceOf(Refusal.class);
        assertThat(made).hasSize(1);

        projects.put("alpha", B, null);

        assertThat(events).containsSubsequence("not drained " + A, "drained " + A, "open " + B);
    }

    @Test
    void aSecondPlannerOnTheSameStateIsRefusedBeforeItsOutboxIsMade() {
        // Harm: two planners settling and running one outbox file, each writing its commands.
        final WorkRecord first = durable();
        projects(first).put("alpha", A, null);
        final List<Fake> before = List.copyOf(made);

        final Throwable refused = catchThrowable(() -> projects(durable()).put("alpha", A, null));

        assertThat(refused).isInstanceOf(WorkRefusal.class);
        assertThat(made).as("no queue made for the second planner").isEqualTo(before);
    }

    @Test
    void aRunsGroupIsHeldBeforeItsRunIsHaltedAndDroppedOnlyWhenNothingOfItBegan() {
        // Harm: a run's moves written behind its halt; or a group that may stand in part dropped
        // from the owner account's view.
        final WorkRecord records = durable();
        records.open("alpha", A, null)
                .locked(
                        session -> {
                            session.write(
                                    new WorkRun.Snapshot(
                                            1,
                                            Map.of(
                                                    "ST1", marked("ST1", "1", GROUP_NONE),
                                                    "ST2", marked("ST2", "2", GROUP_DONE),
                                                    "ST3", marked("ST3", "3", GROUP_AGAIN))));
                            return true;
                        });
        pending =
                List.of(
                        group(GROUP_NONE, CommandOutbox.State.QUEUED),
                        group(GROUP_DONE, CommandOutbox.State.DONE),
                        group(GROUP_AGAIN, CommandOutbox.State.QUEUED),
                        group(GROUP_WINDOW, CommandOutbox.State.QUEUED));
        begunBefore = java.util.Set.of(GROUP_AGAIN);
        final Projects projects = projects(records);

        projects.put("alpha", A, null);

        assertThat(events)
                .containsExactly(
                        "open " + A,
                        "unqueue " + GROUP_NONE,
                        "hold " + GROUP_DONE,
                        "hold " + GROUP_AGAIN,
                        "resume " + A);
        assertThat(made.getFirst().marksWhenHeld)
                .as("each group settled while its run's mark still stood")
                .containsExactly(3, 3, 3);
        final WorkRun.Snapshot after = records.open("alpha", A, null).locked(WorkRun.Session::read);
        assertThat(after.runs().values())
                .allSatisfy(
                        run -> {
                            assertThat(run.halted()).as("halted").isTrue();
                            assertThat(run.pending()).as("mark cleared").isNull();
                        });
    }

    @Test
    void aSaveTheQueueEndsOutsideAWindowsCallIsToldToTheListeners() {
        // Harm: windows showing the board before a group the queue ran again after a restart.
        final Projects projects = projects(durable());
        final List<String> told = new ArrayList<>();
        projects.onSaved(told::add);
        projects.put("alpha", A, null);

        made.getFirst().saved.run();

        assertThat(told).containsExactly("alpha");
    }

    @Test
    void aSaveTheQueueEndsOutsideAWindowsCallReachesTheWindowsStream() throws Exception {
        // Harm: a window that follows the project never told a group the queue ran again.
        final Projects projects = projects(durable());
        projects.put("alpha", A, null);
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try (ChangesRegistry changes =
                new ChangesRegistry(projects, Duration.ofHours(1), scheduler)) {
            final var window = changes.subscribe("alpha");

            // The board the window is given first, before anything is saved.
            window.next(Duration.ofSeconds(2));
            // The queue wrote a group it ran again, and tells nobody's call of it.
            stores.getLast().board = boardAt("r2");
            made.getFirst().saved.run();

            assertThat(window.next(Duration.ofSeconds(2)).map(told -> told.revision().value()))
                    .as("the board after the save, told")
                    .contains("r2");
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aCallerHoldingTheStoreOfBeforeIsNotGivenTheNewQueue() {
        // Harm: a judgment made on the old project's board written through the new one's queue.
        final Projects projects = projects(durable());
        projects.put("alpha", A, null);
        final PlanningStore before = projects.store("alpha");
        projects.put("alpha", B, null);

        assertThat(catchThrowable(() -> projects.queue("alpha", before)))
                .isInstanceOf(StoreException.Busy.class);
    }

    @Test
    void anOutboxThatCannotOpenLeavesTheProjectUnknownAndItsQueueClosed() {
        // Harm: a project served while its outbox was not settled, or a queue left open beside the
        // next one.
        pending = null;
        final Projects projects = projects(durable());

        assertThat(catchThrowable(() -> projects.put("alpha", A, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(catchThrowable(() -> projects.store("alpha"))).isInstanceOf(Refusal.class);
        assertThat(events).containsExactly("open " + A, "drained " + A);
    }

    @Test
    void anOpenThatFailsAfterTheRecordReleasesItsGuardAndKeepsItsOwnFailure() {
        // Harm: a project whose open failed holding its record's guard, so no planner can open it
        // until a restart; or the failure's cause hidden behind a later one.
        pending = null;
        final Projects projects = projects(durable());

        final Throwable failed = catchThrowable(() -> projects.put("alpha", A, null));

        assertThat(failed)
                .as("the outbox's own failure")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox");
        assertThat(guardHeld()).as("the record's guard released").isFalse();
        assertThat(stores.getLast().retired).as("the store retired").isTrue();
    }

    private static WorkRun.Run marked(final String story, final String id, final String group) {
        return new WorkRun.Run(
                "00000000-0000-0000-0000-00000000000" + id,
                story,
                "Building",
                "AGENT",
                "",
                "START",
                2,
                List.of(),
                List.of(),
                Instant.EPOCH.toString(),
                List.of(),
                Map.of(),
                false,
                false,
                new WorkRun.PendingSave(group, List.of("AA" + id)));
    }

    private static CommandOutbox.Group group(final String id, final CommandOutbox.State state) {
        return new CommandOutbox.Group(
                new CommandOutbox.Source(A, ""),
                id,
                List.of(new CommandOutbox.Told(id + "/0", "Write", "AA1", state, null, List.of())),
                false,
                "");
    }

    /** A project's queue that records what the registry asks of it. */
    private final class Fake implements ProjectQueue {

        private final String repository;

        private final Runnable saved;

        private final CountDownLatch inside = new CountDownLatch(1);

        private final AtomicInteger writes = new AtomicInteger();

        private final AtomicInteger running = new AtomicInteger();

        private final List<Integer> marksWhenHeld = new ArrayList<>();

        private volatile @Nullable CountDownLatch hold;

        private int drainFails;

        private boolean resumedGiven;

        Fake(final String repository, final PlanningStore store, final Runnable saved) {
            this.repository = repository;
            this.saved = saved;
        }

        @Override
        public CommandOutcome submit(final CommandGroup group) {
            running.incrementAndGet();
            try {
                events.add("write " + repository);
                writes.incrementAndGet();
                inside.countDown();
                final CountDownLatch latch = hold;
                if (latch != null) {
                    latch.await(5, TimeUnit.SECONDS);
                }
                return new CommandOutcome.Done(null, Map.of());
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                synchronized (this) {
                    running.decrementAndGet();
                    notifyAll();
                }
            }
        }

        @Override
        public Map<String, CommandOutbox.State> open(final Supplier<Board> board) {
            events.add("open " + repository);
            if (pending == null) {
                throw new IllegalStateException("The outbox cannot be read.");
            }
            return Map.of();
        }

        @Override
        public List<CommandOutbox.Group> pending() {
            final List<CommandOutbox.Group> groups = pending;
            return groups != null ? groups : List.of();
        }

        @Override
        public boolean fresh(final String group) {
            return !begunBefore.contains(group)
                    && pending().stream()
                            .filter(each -> each.id().equals(group))
                            .flatMap(each -> each.commands().stream())
                            .allMatch(told -> told.state() == CommandOutbox.State.QUEUED);
        }

        @Override
        public void unqueue(final String group) {
            events.add("unqueue " + group);
            marks();
        }

        @Override
        public void hold(final String group, final Throwable why) {
            events.add("hold " + group);
            marks();
        }

        /** The record as it stands while a group is settled: the repair has not run yet. */
        private void marks() {
            marksWhenHeld.add(
                    (int)
                            opened
                                    .getLast()
                                    .open("alpha", repository, null)
                                    .locked(WorkRun.Session::read)
                                    .runs()
                                    .values()
                                    .stream()
                                    .filter(run -> run.pending() != null)
                                    .count());
        }

        @Override
        public Thread resumeAside() {
            events.add("resume " + repository);
            final Projects given = current;
            resumedGiven = given != null && catchThrowable(() -> given.store("alpha")) == null;
            return Thread.ofPlatform().start(() -> {});
        }

        @Override
        public HeldSaves held(final org.fuin.porelo.planner.core.RefusedEventResave runs) {
            return HeldSaves.NONE;
        }

        @Override
        public synchronized boolean drain(final Duration within) {
            if (drainFails > 0) {
                drainFails--;
                events.add("not drained " + repository);
                return false;
            }
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (running.get() > 0 && System.nanoTime() < until) {
                try {
                    wait(50);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            events.add("drained " + repository);
            return true;
        }
    }

    private static Board boardAt(final String revision) {
        return new Board(
                new Revision(revision),
                new Workflow(List.of("Idea", "Finished"), List.of(), List.of("Finished")),
                List.of(),
                List.of(),
                new Work(Map.of(), List.of()),
                ProjectRepository.named(List.of("porelo-store")));
    }

    /** A store of one board, of the given tracker. */
    private static final class Store implements PlanningStore {

        private volatile Board board = boardAt("r1");

        private final Tracker tracker;

        private volatile @Nullable CountDownLatch release;

        private volatile @Nullable CountDownLatch atStore;

        private volatile int writes;

        private volatile boolean retired;

        @Override
        public void retire() {
            retired = true;
        }

        Store(final Tracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public Board board() {
            return board;
        }

        @Override
        public Board refresh() {
            return board;
        }

        @Override
        public Tracker tracker() {
            return tracker;
        }

        /** Counts each write, held at the store until released. */
        @Override
        public org.fuin.porelo.planner.store.Written writeAlone(final Command command) {
            writes++;
            final CountDownLatch began = atStore;
            if (began != null) {
                began.countDown();
            }
            final CountDownLatch held = release;
            if (held != null) {
                try {
                    if (!held.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("never released");
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return new org.fuin.porelo.planner.store.Written.Done(null, null);
        }
    }
}
