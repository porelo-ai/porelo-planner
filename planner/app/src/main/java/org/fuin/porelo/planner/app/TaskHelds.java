package org.fuin.porelo.planner.app;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.planner.core.TaskRecord;
import org.fuin.porelo.planner.core.WorkRun;
import org.jspecify.annotations.Nullable;

/**
 * The count of held items Porelo's newest record of each task gave, as far as the planner has read
 * one since it started: what an answer shows beside a task, never waited for and never judged. A
 * task is named by its project and its name, since Porelo's names are unique only within a project.
 * The application holds one, which the place watch fills and the answers read.
 */
final class TaskHelds {

    /** The most tasks kept of one project; the oldest read is forgotten first. */
    static final int MOST = 1000;

    /** The held items of one project's tasks, as an answer reads them. */
    @FunctionalInterface
    interface Counts {

        /** Counts that know no task, for an answer that names no project. */
        Counts NONE = task -> null;

        /**
         * The held items of a task's newest record read.
         *
         * @param task the task's name
         * @return the count, or null when none was read
         */
        @Nullable Integer of(String task);
    }

    /**
     * What was read of a task: the count its newest record gave, null where it gave none, and
     * whether the task was still at work when it was read.
     */
    private record Read(@Nullable Integer held, boolean atWork) {}

    /** Each project's tasks read, the oldest read first. */
    private final Map<String, LinkedHashMap<String, Read>> projects = new HashMap<>();

    /**
     * Keeps what a project's records say of the tasks asked for, the last record of a task
     * standing; a record of a task not asked for is left. A record without a count, whose hold
     * journal Porelo could not read or that predates the count, stands as unknown, never as none. A
     * task asked for that no record names is kept as read without a count, so it is not asked for
     * again while it stands. Each project keeps its own most tasks, the oldest read forgotten
     * first.
     *
     * @param project the project
     * @param records Porelo's records, oldest first
     * @param asked the tasks the records were read for
     * @return whether a task's count changed
     */
    synchronized boolean read(
            final String project,
            final List<TaskRecord> records,
            final List<WorkRun.Started> asked) {
        final Map<String, @Nullable Integer> newest = new HashMap<>();
        for (final TaskRecord record : records) {
            newest.put(record.task(), record.held());
        }
        final LinkedHashMap<String, Read> tasks =
                projects.computeIfAbsent(
                        project,
                        p ->
                                new LinkedHashMap<>() {
                                    @Override
                                    protected boolean removeEldestEntry(
                                            final Map.Entry<String, Read> eldest) {
                                        return size() > MOST;
                                    }
                                });
        boolean changed = false;
        for (final WorkRun.Started task : asked) {
            final Read before = tasks.remove(task.task());
            final Integer held =
                    newest.containsKey(task.task())
                            ? newest.get(task.task())
                            : before == null ? null : before.held();
            tasks.put(task.task(), new Read(held, task.atWork()));
            changed |= !Objects.equals(before == null ? null : before.held(), held);
        }
        return changed;
    }

    /**
     * Whether a task's records are to be read: while it is at work, when it was never read, and
     * once more after it ended when it was last read at work, so its last count is seen.
     *
     * @param project the project
     * @param task the task
     * @return true when it is to be read
     */
    synchronized boolean due(final String project, final WorkRun.Started task) {
        if (task.atWork()) {
            return true;
        }
        final Map<String, Read> tasks = projects.get(project);
        final Read read = tasks == null ? null : tasks.get(task.task());
        return read == null || read.atWork();
    }

    /**
     * Whether a task's record was read.
     *
     * @param project the project
     * @param task the task's name
     * @return true once a read named it or was asked for it
     */
    synchronized boolean known(final String project, final String task) {
        final Map<String, Read> tasks = projects.get(project);
        return tasks != null && tasks.containsKey(task);
    }

    /**
     * The held items of a task's newest record read.
     *
     * @param project the project
     * @param task the task's name
     * @return the count, or null when none was read
     */
    synchronized @Nullable Integer of(final String project, final String task) {
        final Map<String, Read> tasks = projects.get(project);
        final Read read = tasks == null ? null : tasks.get(task);
        return read == null ? null : read.held();
    }

    /**
     * The counts of one project's tasks.
     *
     * @param project the project
     * @return its counts, read when asked
     */
    Counts in(final String project) {
        return task -> of(project, task);
    }
}
