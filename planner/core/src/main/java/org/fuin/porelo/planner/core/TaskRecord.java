package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What the velocity reads of Porelo's own record of one task: when it ran, how it ended, the issue
 * it was started for and the issue each of its calls was made for.
 *
 * @param task Porelo's id of the task
 * @param project the project the task belongs to
 * @param repository the repository it ran in, or null when it ran in none
 * @param issue the issue it was started for, or null when it was started for none
 * @param startedAt when it started
 * @param endedAt when it ended, or null while it runs
 * @param end how it ended, or null while it runs
 * @param calls its calls, in Porelo's order
 */
public record TaskRecord(
        String task,
        String project,
        @Nullable String repository,
        @Nullable String issue,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable End end,
        List<Call> calls) {

    /** How a task ended, as Porelo records it. */
    public enum End {
        /** The agent finished. */
        DONE,
        /** The run failed. */
        FAILED,
        /** Someone stopped it. */
        STOPPED,
        /** It was removed. */
        REMOVED
    }

    /**
     * Refuses a blank task, project or issue, an end before the start and an end time without how
     * it ended or the other way round.
     */
    public TaskRecord {
        Objects.requireNonNull(startedAt, "startedAt");
        if (task.isBlank()) {
            throw new IllegalArgumentException("A task record names no task");
        }
        if (project.isBlank()) {
            throw new IllegalArgumentException("Task " + task + " names no project");
        }
        if (issue != null && issue.isBlank()) {
            throw new IllegalArgumentException("Task " + task + " names a blank issue");
        }
        if ((endedAt == null) != (end == null)) {
            throw new IllegalArgumentException(
                    "Task "
                            + task
                            + " gives an end time without how it ended, or the other way round");
        }
        if (endedAt != null && endedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Task " + task + " ends before it starts");
        }
        calls = List.copyOf(calls);
    }

    /**
     * One call a task made.
     *
     * @param issue the issue whose sub-agent made it, or null when no sub-agent of an issue did; an
     *     id no board holds simply names no issue
     * @param start when it started
     * @param end when it ended, or null when Porelo saw no end
     */
    public record Call(@Nullable String issue, Instant start, @Nullable Instant end) {

        /** Refuses a blank issue and an end before the start. */
        public Call {
            Objects.requireNonNull(start, "start");
            if (issue != null && issue.isBlank()) {
                throw new IllegalArgumentException("A call names a blank issue");
            }
            if (end != null && end.isBefore(start)) {
                throw new IllegalArgumentException("A call ends before it starts");
            }
        }
    }
}
