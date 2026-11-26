package org.fuin.porelo.planner.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Fields;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Wait;
import org.fuin.porelo.planner.store.WorkRule;
import org.jspecify.annotations.Nullable;

/**
 * What is still open of a story or a whole board: its issues per state, those blocked by an open
 * issue, the sizes and minutes of what is left to build, and what waits for the person's review.
 *
 * <p>An issue is left to build while it stands neither in a closed state nor in the review state:
 * of the states a work rule's work ends in that are neither working nor closed, the one latest in
 * the workflow, where the last work ends. An earlier work's end, such as an analysis's, waits for
 * the person's next step and is left to build. An issue left to build is blocked while an issue it
 * waits on stands on the board and holds it back, neither closed nor in review ({@link
 * WorkProposal#blockerDone}); it then counts as blocked, not under its state. An issue in a state
 * the workflow lacks counts in no state, but in every other figure.
 *
 * @param states each state of the workflow, in order, with the number of its issues that are not
 *     blocked
 * @param blocked the issues left to build that an open issue blocks
 * @param sizes each size of the issues left to build, smallest first, only sizes that occur
 * @param unsized the issues left to build without a size
 * @param left the issues left to build, the blocked ones included
 * @param minutes the minutes left over the issues left to build that have minutes, or null when
 *     none has
 * @param unestimated the issues left to build without minutes
 * @param review the issues waiting for the person's review
 */
public record OpenWork(
        List<Count> states,
        int blocked,
        List<Count> sizes,
        int unsized,
        int left,
        @Nullable Double minutes,
        int unestimated,
        int review) {

    private static final String REJECTED = "Rejected";

    /**
     * A name with its number of issues.
     *
     * @param name a state's or size's name
     * @param issues how many issues
     */
    public record Count(String name, int issues) {}

    /** Copies the lists. */
    public OpenWork {
        states = List.copyOf(states);
        sizes = List.copyOf(sizes);
    }

    /**
     * Counts the open work of one board, its stories and the board as a whole, reading the board
     * once, so a large board costs one pass per story and not one per story and issue.
     */
    public static final class Counter {

        private final Board board;

        private final Map<String, Issue> byId = new HashMap<>();

        private final Map<String, List<String>> children = new HashMap<>();

        private final Set<String> holding = new HashSet<>();

        private final Set<String> review;

        private final Map<String, Double> minutes;

        /**
         * Reads a board's issues, the review states and each issue's minutes left.
         *
         * @param board the board
         */
        public Counter(final Board board) {
            this.board = board;
            for (final Issue issue : board.issues()) {
                byId.put(issue.id(), issue);
                if (issue.parent() != null) {
                    children.computeIfAbsent(issue.parent(), key -> new ArrayList<>())
                            .add(issue.id());
                }
                if (!WorkProposal.blockerDone(board, issue)) {
                    holding.add(issue.id());
                }
            }
            this.review = reviewStates(board);
            this.minutes = OpenWork.minutesLeft(board, review);
        }

        /**
         * Each issue's minutes left that has any, as {@link OpenWork#minutesLeft(Board)} answers
         * them.
         *
         * @return the minutes left by issue id
         */
        public Map<String, Double> minutes() {
            return minutes;
        }

        /**
         * What is open of one story: the issues it names and their sub-issues, each once; null when
         * the board does not hold every issue its head names, or holds one of its ids twice, so
         * nothing is told as if it did.
         *
         * @param story the story
         * @return the story's open work, or null
         */
        public @Nullable OpenWork story(final Story story) {
            // An id on two issues tells no one issue to count.
            if (!story.issuesWhole()
                    || story.issues().stream().anyMatch(id -> board.twice(id).isPresent())) {
                return null;
            }
            final Set<String> ids = new LinkedHashSet<>();
            final Deque<String> next = new ArrayDeque<>(story.issues());
            while (!next.isEmpty()) {
                final String id = next.pop();
                if (byId.containsKey(id) && ids.add(id)) {
                    next.addAll(children.getOrDefault(id, List.of()));
                }
            }
            final List<Issue> issues = new ArrayList<>();
            ids.forEach(id -> issues.add(byId.get(id)));
            return count(issues);
        }

        /**
         * What is open of the whole board, every issue it holds counted once, in a story or not.
         *
         * @return the board's open work
         */
        public OpenWork board() {
            return count(board.issues());
        }

        private OpenWork count(final Collection<Issue> issues) {
            final Map<String, Integer> states = new LinkedHashMap<>();
            board.workflow().states().forEach(state -> states.put(state, 0));
            final Map<String, Integer> sizes = new LinkedHashMap<>();
            Fields.SIZES.forEach(size -> sizes.put(size, 0));
            int blocked = 0;
            int unsized = 0;
            int left = 0;
            int unestimated = 0;
            int waiting = 0;
            double sum = 0;
            boolean anyMinutes = false;
            for (final Issue issue : issues) {
                if (review.contains(issue.state())) {
                    waiting++;
                }
                final boolean building = leftToBuild(board, review, issue);
                // An issue whose wait opened again counts as blocked even while a run works it:
                // the run goes on, but nothing more of it may start until the wait is done.
                if (building
                        && (issue.blockedBy().stream().anyMatch(holding::contains)
                                || issue.waitsOn().stream().anyMatch(Wait::holds))) {
                    blocked++;
                } else {
                    // A state the workflow lacks is shown outside it, not counted here.
                    states.computeIfPresent(issue.state(), (state, count) -> count + 1);
                }
                if (!building) {
                    continue;
                }
                left++;
                if (issue.size() == null || !sizes.containsKey(issue.size())) {
                    unsized++;
                } else {
                    sizes.merge(issue.size(), 1, Integer::sum);
                }
                final Double own = minutes.get(issue.id());
                if (own == null) {
                    unestimated++;
                } else {
                    sum += own;
                    anyMinutes = true;
                }
            }
            return new OpenWork(
                    counts(states, false),
                    blocked,
                    counts(sizes, true),
                    unsized,
                    left,
                    anyMinutes ? sum : null,
                    unestimated,
                    waiting);
        }
    }

    /**
     * Each issue's minutes left that has any: what is expected of it less what it took so far,
     * never below zero, for an issue left to build only.
     *
     * <p>What is expected is its estimate; without one, the median minutes of the finished issues
     * of its size in its repository; without those, the median of the finished issues of its size
     * in every repository; without those, the seed's minutes for its size. An issue without a size
     * and without an estimate has none, as has one whose size neither is measured nor seeded.
     *
     * @param board the board
     * @return the minutes left by issue id
     */
    public static Map<String, Double> minutesLeft(final Board board) {
        return minutesLeft(board, reviewStates(board));
    }

    private static Map<String, Double> minutesLeft(final Board board, final Set<String> review) {
        final Map<String, List<Double>> byRepository = new HashMap<>();
        final Map<String, List<Double>> bySize = new HashMap<>();
        for (final Issue issue : board.issues()) {
            final Double taken = issue.minutes();
            if (taken != null
                    && taken > 0
                    && issue.size() != null
                    && board.workflow().closed().contains(issue.state())
                    && !REJECTED.equals(issue.state())) {
                byRepository
                        .computeIfAbsent(
                                issue.repository() + " " + issue.size(), key -> new ArrayList<>())
                        .add(taken);
                bySize.computeIfAbsent(issue.size(), key -> new ArrayList<>()).add(taken);
            }
        }
        final Map<String, Double> medians = new HashMap<>();
        final Map<String, Double> left = new LinkedHashMap<>();
        for (final Issue issue : board.issues()) {
            if (!leftToBuild(board, review, issue)) {
                continue;
            }
            Double expected = issue.estimate();
            if (expected == null && issue.size() != null) {
                final String own = issue.repository() + " " + issue.size();
                expected = medians.computeIfAbsent(own, key -> median(byRepository.get(key)));
                if (expected == null) {
                    expected =
                            medians.computeIfAbsent(
                                    " " + issue.size(), key -> median(bySize.get(issue.size())));
                }
                if (expected == null) {
                    expected = board.work().seed().get(issue.size());
                }
            }
            if (expected != null) {
                final double taken = issue.minutes() == null ? 0 : issue.minutes();
                left.put(issue.id(), Math.max(0, expected - taken));
            }
        }
        return left;
    }

    /**
     * The review state, alone or none: of the states a work rule's work ends in that are neither
     * working nor closed, the one latest in the workflow, as the window tells review too.
     */
    private static Set<String> reviewStates(final Board board) {
        final Set<String> ends = new HashSet<>();
        for (final WorkRule rule : board.work().rules().values()) {
            ends.add(rule.done());
        }
        ends.removeAll(board.work().rules().keySet());
        ends.removeAll(board.workflow().closed());
        String last = null;
        for (final String state : board.workflow().states()) {
            if (ends.contains(state)) {
                last = state;
            }
        }
        return last == null ? Set.of() : Set.of(last);
    }

    private static boolean leftToBuild(
            final Board board, final Set<String> review, final Issue issue) {
        return !board.workflow().closed().contains(issue.state())
                && !review.contains(issue.state());
    }

    private static List<Count> counts(
            final Map<String, Integer> counts, final boolean onlyPresent) {
        final List<Count> list = new ArrayList<>();
        counts.forEach(
                (name, issues) -> {
                    if (!onlyPresent || issues > 0) {
                        list.add(new Count(name, issues));
                    }
                });
        return list;
    }

    private static @Nullable Double median(final @Nullable List<Double> samples) {
        if (samples == null || samples.isEmpty()) {
            return null;
        }
        final List<Double> sorted = new ArrayList<>(samples);
        sorted.sort(Double::compare);
        final int middle = sorted.size() / 2;
        // Halved apart, so two of the largest figures never add up past a double.
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : sorted.get(middle - 1) / 2 + sorted.get(middle) / 2;
    }
}
