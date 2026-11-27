package org.fuin.porelo.planner.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.planner.store.Role;
import org.jspecify.annotations.Nullable;

/**
 * What the velocity reads of Porelo's own record of one task: when it ran, how it ended, the issues
 * it was started for and the issue each of its calls was made for.
 *
 * @param task Porelo's id of the task
 * @param project the project the task belongs to
 * @param repository the repository it ran in, or null when it ran in none
 * @param issues the issues it was started for, each once, in Porelo's order; none when it was
 *     started for none
 * @param startedAt when it started
 * @param endedAt when it ended, or null while it runs
 * @param end how it ended, or null while it runs
 * @param calls its calls, in Porelo's order
 * @param workspace the kind of its last run's workspace as Porelo recorded it, or null when Porelo
 *     recorded none
 * @param place where its last run worked, or null when Porelo recorded none
 * @param held how many review items at that place are held back from its run, or null when Porelo
 *     recorded no count
 * @param callerKey the key its start was given by whoever started it, or null when it was given
 *     none; the planner's keys are its offers
 */
public record TaskRecord(
        String task,
        String project,
        @Nullable String repository,
        List<String> issues,
        Instant startedAt,
        @Nullable Instant endedAt,
        @Nullable End end,
        List<Call> calls,
        Role.@Nullable Workspace workspace,
        @Nullable RunPlace place,
        @Nullable Integer held,
        @Nullable String callerKey) {

    /** The most characters of a caller's key, as Porelo keeps it. */
    public static final int MOST_CALLER_KEY = 200;

    /**
     * A record whose start was given no caller's key.
     *
     * @param task Porelo's id of the task
     * @param project the project the task belongs to
     * @param repository the repository it ran in, or null
     * @param issues the issues it was started for
     * @param startedAt when it started
     * @param endedAt when it ended, or null while it runs
     * @param end how it ended, or null while it runs
     * @param calls its calls
     */
    public TaskRecord(
            final String task,
            final String project,
            final @Nullable String repository,
            final List<String> issues,
            final Instant startedAt,
            final @Nullable Instant endedAt,
            final @Nullable End end,
            final List<Call> calls) {
        this(
                task,
                project,
                repository,
                issues,
                startedAt,
                endedAt,
                end,
                calls,
                null,
                null,
                null,
                null);
    }

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
     * Refuses a blank task, project or issue, an issue named twice, an end before the start and an
     * end time without how it ended or the other way round.
     */
    public TaskRecord {
        Objects.requireNonNull(startedAt, "startedAt");
        if (task.isBlank()) {
            throw new IllegalArgumentException("A task record names no task");
        }
        if (project.isBlank()) {
            throw new IllegalArgumentException("Task " + task + " names no project");
        }
        issues = List.copyOf(issues);
        if (issues.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Task " + task + " names a blank issue");
        }
        if (issues.stream().distinct().count() != issues.size()) {
            throw new IllegalArgumentException("Task " + task + " names an issue twice");
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
        if (callerKey != null
                && (callerKey.length() > MOST_CALLER_KEY
                        || callerKey.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(
                    "Task "
                            + task
                            + " carries a caller's key longer than "
                            + MOST_CALLER_KEY
                            + " characters or with a control character");
        }
        if (held != null && held < 0) {
            throw new IllegalArgumentException("Task " + task + " holds fewer than no items");
        }
    }

    /**
     * A record whose start was given no caller's key.
     *
     * @param task Porelo's id of the task
     * @param project the project the task belongs to
     * @param repository the repository it ran in, or null
     * @param issues the issues it was started for
     * @param startedAt when it started
     * @param endedAt when it ended, or null while it runs
     * @param end how it ended, or null while it runs
     * @param calls its calls
     * @param workspace the kind of its last run's workspace, or null
     * @param place where its last run worked, or null
     * @param held its held items, or null
     */
    public TaskRecord(
            final String task,
            final String project,
            final @Nullable String repository,
            final List<String> issues,
            final Instant startedAt,
            final @Nullable Instant endedAt,
            final @Nullable End end,
            final List<Call> calls,
            final Role.@Nullable Workspace workspace,
            final @Nullable RunPlace place,
            final @Nullable Integer held) {
        this(
                task,
                project,
                repository,
                issues,
                startedAt,
                endedAt,
                end,
                calls,
                workspace,
                place,
                held,
                null);
    }

    /**
     * A record without a run's role, place or held items, as Porelo kept it before it recorded
     * them.
     *
     * @param task Porelo's id of the task
     * @param project the project the task belongs to
     * @param repository the repository it ran in, or null
     * @param issues the issues it was started for
     * @param startedAt when it started
     * @param endedAt when it ended, or null while it runs
     * @param end how it ended, or null while it runs
     * @param calls its calls
     * @param callerKey the key its start was given, or null
     */
    public TaskRecord(
            final String task,
            final String project,
            final @Nullable String repository,
            final List<String> issues,
            final Instant startedAt,
            final @Nullable Instant endedAt,
            final @Nullable End end,
            final List<Call> calls,
            final @Nullable String callerKey) {
        this(
                task,
                project,
                repository,
                issues,
                startedAt,
                endedAt,
                end,
                calls,
                null,
                null,
                null,
                callerKey);
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
