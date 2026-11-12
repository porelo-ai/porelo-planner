package org.fuin.porelo.planner.core;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.jspecify.annotations.Nullable;

/**
 * The minutes a repository's finished issues of one size took, read from Porelo's own record of
 * each task that worked them, never from what an agent wrote about itself.
 */
public final class Velocity {

    private static final String REJECTED = "Rejected";

    private Velocity() {}

    /**
     * Answers the median of the minutes each finished issue of a size in a repository took.
     *
     * <p>A task counts once it ended, however it ended. An issue its calls name gets the union of
     * those calls' intervals within the task, scaled down where parallel issues add up to more than
     * the task lasted; the rest of the task is split evenly among every issue it worked. The
     * records must reach back to the first task of every issue counted, or its sample is too low.
     *
     * @param records Porelo's records of the project's tasks
     * @param board the board that says each issue's repository, size and state
     * @param repository the repository's name
     * @param size the size
     * @return the median minutes, or empty when no finished issue of the size has a record
     * @throws IllegalArgumentException when two records name one task
     */
    public static Optional<Double> median(
            final List<TaskRecord> records,
            final Board board,
            final String repository,
            final String size) {
        final Map<String, Double> minutes = minutes(records);
        final List<Double> samples = new ArrayList<>();
        for (final Issue issue : board.issues()) {
            final Double taken = minutes.get(issue.id());
            // A task of no length measured nothing.
            if (taken != null
                    && taken > 0
                    && issue.repository().equals(repository)
                    && size.equals(issue.size())
                    && finished(board, issue.state())) {
                samples.add(taken);
            }
        }
        if (samples.isEmpty()) {
            return Optional.empty();
        }
        samples.sort(Comparator.naturalOrder());
        final int middle = samples.size() / 2;
        return Optional.of(
                samples.size() % 2 == 1
                        ? samples.get(middle)
                        : samples.get(middle - 1) / 2 + samples.get(middle) / 2);
    }

    /** Whether a state ended the work with a result. */
    private static boolean finished(final Board board, final String state) {
        return board.workflow().closed().contains(state) && !REJECTED.equals(state);
    }

    /** The minutes every ended task gave each issue it worked, summed over the tasks. */
    private static Map<String, Double> minutes(final List<TaskRecord> records) {
        final Set<String> tasks = new HashSet<>();
        final Map<String, Double> minutes = new LinkedHashMap<>();
        for (final TaskRecord record : records) {
            if (!tasks.add(record.task())) {
                throw new IllegalArgumentException("Task " + record.task() + " is recorded twice");
            }
            final Instant ended = record.endedAt();
            if (ended == null) {
                continue;
            }
            final Set<String> issues = new LinkedHashSet<>();
            if (record.issue() != null) {
                issues.add(record.issue());
            }
            final Map<String, List<Instant[]>> intervals = new LinkedHashMap<>();
            for (final TaskRecord.Call call : record.calls()) {
                final String issue = call.issue();
                if (issue == null) {
                    continue;
                }
                issues.add(issue);
                final Instant[] cut = within(call, record.startedAt(), ended);
                if (cut != null) {
                    intervals.computeIfAbsent(issue, key -> new ArrayList<>()).add(cut);
                }
            }
            if (issues.isEmpty()) {
                continue;
            }
            final double total = between(record.startedAt(), ended);
            final Map<String, Double> spans = new LinkedHashMap<>();
            double spanned = 0;
            for (final Map.Entry<String, List<Instant[]>> issue : intervals.entrySet()) {
                final double span = union(issue.getValue());
                spans.put(issue.getKey(), span);
                spanned += span;
            }
            // Parallel sub-agents may add up to more than the task lasted; it gives no more.
            final double scale = spanned > total ? total / spanned : 1;
            final double share = Math.max(0, total - spanned * scale) / issues.size();
            for (final String issue : issues) {
                minutes.merge(issue, spans.getOrDefault(issue, 0.0) * scale + share, Double::sum);
            }
        }
        return minutes;
    }

    /** A call's interval cut to its task's, or null when it has no end or lies outside. */
    private static Instant @Nullable [] within(
            final TaskRecord.Call call, final Instant started, final Instant ended) {
        final Instant end = call.end();
        if (end == null) {
            return null;
        }
        final Instant from = call.start().isBefore(started) ? started : call.start();
        final Instant to = end.isAfter(ended) ? ended : end;
        return from.isBefore(to) ? new Instant[] {from, to} : null;
    }

    /** The minutes the intervals cover together, each moment once. */
    private static double union(final List<Instant[]> intervals) {
        intervals.sort(Comparator.comparing(interval -> interval[0]));
        double covered = 0;
        Instant from = intervals.getFirst()[0];
        Instant to = intervals.getFirst()[1];
        for (final Instant[] interval : intervals.subList(1, intervals.size())) {
            if (interval[0].isAfter(to)) {
                covered += between(from, to);
                from = interval[0];
                to = interval[1];
            } else if (interval[1].isAfter(to)) {
                to = interval[1];
            }
        }
        return covered + between(from, to);
    }

    private static double between(final Instant from, final Instant to) {
        final Duration duration = Duration.between(from, to);
        return duration.getSeconds() / 60.0 + duration.getNano() / 60e9;
    }
}
