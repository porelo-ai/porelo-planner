package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.StoreException;
import org.fuin.porelo.planner.store.Tracker;
import org.fuin.porelo.planner.store.git.LogText;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/** The projects the window has given the planner, each with its store, while the planner runs. */
public final class Projects {

    private static final Logger LOG = LoggerFactory.getLogger(Projects.class);

    /** Opens a project's store from its own repository. */
    @FunctionalInterface
    public interface Opener {

        /**
         * Opens a store.
         *
         * @param project the project's name
         * @param repository its own repository's clone URL
         * @param branch the own repository's branch, or null for the remote's default
         * @return the store
         * @throws Refusal if the URL or the branch is refused
         */
        PlanningStore open(String project, String repository, @Nullable String branch);
    }

    private record Source(String repository, @Nullable String branch) {}

    /**
     * One given project.
     *
     * @param source where it is read from
     * @param store its store
     * @param work its work service
     * @param queue the queue its saves are handed to
     * @param kept the same queue when it keeps the saves in an outbox, or null
     */
    private record Entry(
            Source source,
            PlanningStore store,
            WorkRun work,
            CommandQueue queue,
            @Nullable ProjectQueue kept) {}

    private final Map<String, Entry> projects = new ConcurrentHashMap<>();

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private final List<Consumer<String>> retired = new CopyOnWriteArrayList<>();

    private final List<Consumer<String>> saved = new CopyOnWriteArrayList<>();

    /**
     * The queues of projects no longer given whose drain did not end in time, drained again before
     * the project's next outbox opens; changed only under the project's lock.
     */
    private final Map<String, ProjectQueue> draining = new ConcurrentHashMap<>();

    private final ProjectQueues queues;

    private final Opener opener;

    private final WorkRecord records;

    /**
     * Makes a registry that keeps every project it is given.
     *
     * @param opener opens a project's store
     */
    public Projects(final Opener opener) {
        this(opener, new WorkRecord(Map.of(), true));
    }

    /** Creates a registry using the supplied durable or mock work records. */
    public Projects(final Opener opener, final WorkRecord records) {
        this(opener, records, new RunnerQueues());
    }

    /**
     * Creates a registry using the supplied work records, a GitHub project's saves kept in an
     * outbox beside its durable record by the given queues.
     *
     * @param opener opens a project's store
     * @param records the durable or mock work records
     * @param queues makes the queue of a project whose saves are kept in an outbox
     */
    Projects(final Opener opener, final WorkRecord records, final ProjectQueues queues) {
        this.opener = opener;
        this.records = records;
        this.queues = queues;
    }

    /**
     * Gives the planner a project; the same source again changes nothing, another replaces it,
     * retiring the old one first once its save in flight ended, so a failure leaves the project
     * unknown. Projects are given one at a time each, never waiting on another.
     *
     * @param project the project's name, already checked
     * @param repository its own repository's clone URL
     * @param branch the own repository's branch, or null
     */
    public void put(final String project, final String repository, final @Nullable String branch) {
        final Source source = new Source(repository, branch);
        synchronized (locks.computeIfAbsent(project, any -> new Object())) {
            final Entry known = projects.get(project);
            if (known != null && known.source().equals(source)) {
                return;
            }
            replace(project, source, known);
        }
    }

    /**
     * Opens a known project's store anew from the same source, after a change the store reads only
     * when it is opened, such as the directory a local project's items are kept in; the change is
     * made under the project's lock, so no call opens the store meanwhile. A failure after the
     * change leaves the project unknown, as a project given anew does.
     *
     * @param project the project's name, already checked
     * @param change the change, made before the old store is retired; a change that fails leaves
     *     the project as it was
     * @throws Refusal {@code NoSuchProject} when the planner was not given it
     */
    public void reopen(final String project, final Runnable change) {
        synchronized (locks.computeIfAbsent(project, any -> new Object())) {
            final Entry known = projects.get(project);
            if (known == null) {
                store(project);
                throw new IllegalStateException("The project was retired");
            }
            change.run();
            replace(project, known.source(), known);
        }
    }

    /**
     * Closes the project's work record, retires its store, if any, and opens it from the source;
     * held under the project's lock alone. The record is closed first, while the project is still
     * given, since a save holding it reaches its store through this registry; and always, so a
     * record left by a save that outlasted an earlier try is waited for before the source's clones
     * are touched.
     *
     * @throws org.fuin.porelo.planner.core.WorkRefusal {@code WorkRecordUnavailable} when the
     *     record's save did not end in time; the project is then unknown, as after any failure
     */
    private void replace(final String project, final Source source, final @Nullable Entry known) {
        try {
            // The old queue first: its groups end, or are refused, before the record they settle
            // in closes and before a new outbox of the project can open.
            drained(project, known);
            records.forget(project);
        } finally {
            if (known != null) {
                retire(project, known);
            }
        }
        final PlanningStore store =
                new LoggedStore(
                        project,
                        Objects.requireNonNull(
                                opener.open(project, source.repository(), source.branch()),
                                "store"));
        @Nullable ProjectQueue kept = null;
        boolean opened = false;
        final Entry entry;
        try {
            // The record's file guard first: a second planner on the same state directory is
            // refused here, before anything reads or writes the outbox beside it.
            final WorkRun.Record record =
                    records.open(project, source.repository(), source.branch());
            opened = true;
            final CommandQueue queue;
            if (records.durable() && store.tracker().kind() == Tracker.Kind.GITHUB) {
                kept = queues.queue(records.place(project), store, () -> told(project));
                kept.open(store::board);
                reconciled(kept, record.locked(WorkRun.Session::read));
                queue = kept;
            } else {
                // The Git and the local store write a group under their own lock, all or none.
                queue = new DirectQueue(store);
            }
            final WorkRun work =
                    new WorkRun(
                            store,
                            record,
                            java.time.Clock.systemUTC(),
                            why ->
                                    LOG.info(
                                            "No next step in project {}: {}",
                                            LogText.of(project),
                                            LogText.of(why)),
                            queue);
            // Readers no longer repair, so a save that died midway is settled here, once.
            work.repair();
            entry = new Entry(source, store, work, queue, kept);
        } catch (final RuntimeException | Error e) {
            try {
                boolean drained = true;
                if (kept != null) {
                    try {
                        drainedOrKept(project, kept, Duration.ZERO);
                    } catch (final RuntimeException notDrained) {
                        // Its guard stays held while a command of it may be at the store.
                        drained = false;
                        e.addSuppressed(notDrained);
                    }
                }
                if (opened && drained) {
                    try {
                        records.forget(project);
                    } catch (final RuntimeException unforgotten) {
                        e.addSuppressed(unforgotten);
                    }
                }
            } finally {
                // The store is kept nowhere, so it is retired here or never.
                store.retire();
            }
            throw e;
        }
        projects.put(project, entry);
        if (kept != null) {
            kept.resumeAside();
        }
    }

    /**
     * Settles each run's group the outbox kept, before the repair halts the run: a run's group runs
     * again only through a new Start, never behind its halt. A group none of whose commands was
     * begun, now or before, is removed, since nothing of it was written; any other is held for the
     * owner account, as it may stand in part. A stop between this and the repair leaves the marks,
     * and the next open does the same again, so the order holds across a crash.
     *
     * @param queue the project's queue, its outbox open
     * @param snapshot the work record as it stands, a save's pending marks included
     */
    static void reconciled(final ProjectQueue queue, final WorkRun.Snapshot snapshot) {
        final java.util.Set<String> marked = new java.util.HashSet<>();
        for (final WorkRun.Run run : snapshot.runs().values()) {
            final WorkRun.@Nullable PendingSave pending = run.pending();
            if (pending != null) {
                marked.add(pending.id());
            }
        }
        for (final CommandOutbox.Group group : queue.pending()) {
            if (group.earlier() || !marked.contains(group.id())) {
                continue;
            }
            if (queue.fresh(group.id())) {
                queue.unqueue(group.id());
            } else {
                queue.hold(
                        group.id(),
                        new StoreException.PartlySaved(
                                "", List.of(), "The run's save stopped; a new Start goes on."));
            }
        }
    }

    /** Drains the project's queue left from before, then the one of the project as given. */
    private void drained(final String project, final @Nullable Entry known) {
        final ProjectQueue left = draining.get(project);
        if (left != null) {
            drainedOrKept(project, left, records.drainBound());
            draining.remove(project, left);
        }
        final @Nullable ProjectQueue kept = known == null ? null : known.kept();
        if (kept != null) {
            drainedOrKept(project, kept, records.drainBound());
        }
    }

    /**
     * Drains a queue; one whose groups did not end in time is kept to drain again before the
     * project's next outbox opens, since a command of it may still be at the store.
     */
    private void drainedOrKept(
            final String project, final ProjectQueue queue, final Duration within) {
        if (!queue.drain(within)) {
            draining.put(project, queue);
            throw WorkRefusal.recordUnavailable(
                    project,
                    "A save of "
                            + project
                            + " did not end in time; the project was not given anew.");
        }
    }

    /** Tells the listeners a save of the project's queue that no window's call answers. */
    private void told(final String project) {
        saved.forEach(listener -> listener.accept(project));
    }

    /** Makes a given project unknown, retires its store and tells the listeners. */
    private void retire(final String project, final Entry known) {
        projects.remove(project, known);
        known.store().retire();
        retired.forEach(listener -> listener.accept(project));
    }

    /**
     * Forgets every project, retiring its store, each under its own lock as a project given anew
     * is; the mock mode's reset and the planner's shutdown, which drains every queue before the
     * work record releases its file guards. A project whose save does not end in time is forgotten
     * all the same and logged, its record left to that save; one whose queue did not drain keeps
     * its record's guard until the planner ends, since a command of it may still be at the store.
     */
    public void forgetAll() {
        for (final String project : List.copyOf(projects.keySet())) {
            synchronized (locks.computeIfAbsent(project, any -> new Object())) {
                final Entry known = projects.get(project);
                if (known == null) {
                    continue;
                }
                try {
                    drained(project, known);
                } catch (final org.fuin.porelo.planner.core.WorkRefusal refused) {
                    LOG.warn(
                            "Project {} is forgotten while its queue still writes; its record's"
                                    + " guard stays held until the planner ends: {}",
                            LogText.of(project),
                            LogText.of(String.valueOf(refused.getMessage())));
                    records.keep(project);
                    retire(project, known);
                    continue;
                }
                try {
                    records.forget(project);
                } catch (final org.fuin.porelo.planner.core.WorkRefusal refused) {
                    LOG.warn(
                            "Project {} is forgotten while a save of it goes on: {}",
                            LogText.of(project),
                            LogText.of(String.valueOf(refused.getMessage())));
                }
                retire(project, known);
            }
        }
    }

    /**
     * Tells a listener each project whose store is retired, given anew or forgotten.
     *
     * @param listener takes the project's name
     */
    public void onRetired(final Consumer<String> listener) {
        retired.add(listener);
    }

    /**
     * Tells a listener each project a save was made in that no window's call answers, such as a
     * group its queue ran again after the planner started.
     *
     * @param listener takes the project's name
     */
    public void onSaved(final Consumer<String> listener) {
        saved.add(listener);
    }

    /**
     * Answers the saves a project's outbox keeps for the owner account: none for a project whose
     * saves go through no outbox, or one not given.
     *
     * @param project the project's name
     * @return its held saves
     */
    HeldSaves held(final String project) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            return HeldSaves.NONE;
        }
        final @Nullable ProjectQueue kept = entry.kept();
        return kept == null ? HeldSaves.NONE : kept.held(entry.work());
    }

    /**
     * Answers the queue a project's saves are handed to, the one its work service writes through.
     *
     * @param project the project's name
     * @param store the store the caller read the project's board from
     * @return the queue
     * @throws Refusal {@code NoSuchProject} when the planner was not given it
     * @throws StoreException.Busy when the project was given anew since the caller read its store,
     *     so the caller's judgment is not handed to the new project's queue
     */
    public CommandQueue queue(final String project, final PlanningStore store) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            store(project);
            throw new IllegalStateException("The project was retired");
        }
        if (entry.store() != store) {
            throw new StoreException.Busy("");
        }
        return entry.queue();
    }

    /**
     * One given project as one entry: its source, its store and its work, read together, so a call
     * that reads them never mixes a project given anew with the one before.
     *
     * @param repository its own repository's clone URL
     * @param branch its branch, or null for the remote's default
     * @param store its store
     * @param work its work service
     */
    public record Given(
            String repository, @Nullable String branch, PlanningStore store, WorkRun work) {}

    /**
     * Answers a known project's source, store and work from one entry.
     *
     * @param project the project's name
     * @return the project as given
     * @throws Refusal {@code NoSuchProject} when the planner was not given it
     */
    public Given given(final String project) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            store(project);
            throw new IllegalStateException("The project was retired");
        }
        return new Given(
                entry.source().repository(), entry.source().branch(), entry.store(), entry.work());
    }

    /** Answers a known project's work service with the same lifetime as its store. */
    public WorkRun work(final String project) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            store(project);
            throw new IllegalStateException("The project was retired");
        }
        return entry.work();
    }

    /**
     * Answers a known project's store.
     *
     * @param project the project's name
     * @return its store
     */
    public PlanningStore store(final String project) {
        final Entry entry = projects.get(project);
        if (entry == null) {
            throw new Refusal(
                    "NoSuchProject",
                    HttpStatus.NOT_FOUND,
                    "The planner was not given project " + project,
                    Map.of("project", project));
        }
        return entry.store();
    }
}
