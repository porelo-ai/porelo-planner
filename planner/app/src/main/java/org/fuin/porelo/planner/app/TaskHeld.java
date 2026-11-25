package org.fuin.porelo.planner.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.core.TaskRecord;
import org.jspecify.annotations.Nullable;

/**
 * The count of held items Porelo's newest record of each task gave, as far as the planner has read
 * one since it started: what an answer shows beside a task, never waited for and never judged. A
 * task is named by its project and its name, since Porelo's names are unique only within a project.
 */
final class TaskHeld {

    /** The most tasks kept; the oldest read is forgotten first. */
    static final int MOST = 1000;

    private record Key(String project, String task) {}

    private static final Map<Key, Integer> HELD =
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<Key, Integer> eldest) {
                    return size() > MOST;
                }
            };

    private TaskHeld() {}

    /**
     * Keeps what a project's records say, the last record of a task standing; a record without a
     * count, whose hold journal Porelo could not read or that predates the count, stands as
     * unknown, never as none.
     *
     * @param project the project
     * @param records Porelo's records, oldest first
     */
    static void read(final String project, final List<TaskRecord> records) {
        synchronized (HELD) {
            for (final TaskRecord record : records) {
                final Integer held = record.held();
                final Key key = new Key(project, record.task());
                HELD.remove(key);
                HELD.put(key, held);
            }
        }
    }

    /**
     * The held items of a task's newest record read.
     *
     * @param project the project
     * @param task the task's name
     * @return the count, or null when no record of it was read
     */
    static @Nullable Integer of(final String project, final String task) {
        synchronized (HELD) {
            return HELD.get(new Key(project, task));
        }
    }

    /** Forgets every task read, for a test. */
    static void clear() {
        synchronized (HELD) {
            HELD.clear();
        }
    }
}
