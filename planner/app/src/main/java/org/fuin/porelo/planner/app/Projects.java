package org.fuin.porelo.planner.app;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.fuin.porelo.planner.core.WorkRun;
import org.fuin.porelo.planner.store.PlanningStore;
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

    private record Entry(Source source, PlanningStore store, WorkRun work) {}

    private final Map<String, Entry> projects = new ConcurrentHashMap<>();

    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    private final List<Consumer<String>> retired = new CopyOnWriteArrayList<>();

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
        this.opener = opener;
        this.records = records;
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
        final WorkRun work;
        try {
            final WorkRun.Record record =
                    records.open(project, source.repository(), source.branch());
            work =
                    new WorkRun(
                            store,
                            record,
                            java.time.Clock.systemUTC(),
                            why ->
                                    LOG.info(
                                            "No next step in project {}: {}",
                                            LogText.of(project),
                                            LogText.of(why)));
            // Readers no longer repair, so a save that died midway is settled here, once.
            work.repair();
        } catch (final RuntimeException | Error e) {
            // The store is kept nowhere, so it is retired here or never.
            store.retire();
            throw e;
        }
        projects.put(project, new Entry(source, store, work));
    }

    /** Makes a given project unknown, retires its store and tells the listeners. */
    private void retire(final String project, final Entry known) {
        projects.remove(project, known);
        known.store().retire();
        retired.forEach(listener -> listener.accept(project));
    }

    /**
     * Forgets every project, retiring its store, each under its own lock as a project given anew
     * is; the mock mode's reset. A project whose save does not end in time is forgotten all the
     * same and logged, its record left to that save.
     */
    public void forgetAll() {
        for (final String project : List.copyOf(projects.keySet())) {
            synchronized (locks.computeIfAbsent(project, any -> new Object())) {
                final Entry known = projects.get(project);
                if (known == null) {
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
