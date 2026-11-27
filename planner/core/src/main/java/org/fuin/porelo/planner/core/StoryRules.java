package org.fuin.porelo.planner.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * The stories' rules, one set for every store: what on a board breaks them, shown to the person and
 * never read by a move, a preview or a save.
 */
public final class StoryRules {

    private StoryRules() {}

    /** A rule of the stories. */
    public enum Rule {
        /** An open issue past the workflow's first state stands in no story. */
        ISSUE_IN_NO_STORY("IssueInNoStory"),
        /**
         * A story's priority is below the highest priority of its issues still to be built: open
         * and, where the workflow gives a story its state, before its review state.
         */
        STORY_PRIORITY_BELOW_ISSUE("StoryPriorityBelowIssue");

        private final String spelled;

        Rule(final String spelled) {
            this.spelled = spelled;
        }

        /**
         * Answers the rule's name as the board answer spells it.
         *
         * @return the name
         */
        public String spelled() {
            return spelled;
        }
    }

    /**
     * One breach of a rule.
     *
     * @param item the story's or the issue's id
     * @param rule the rule broken
     * @param words what is broken, in fixed words and ids only
     * @param issues the issues the breach names: the open issue a story's priority is below, or the
     *     issue that stands in no story
     */
    public record Breach(String item, Rule rule, String words, List<String> issues) {

        /** Copies the issues. */
        public Breach {
            issues = List.copyOf(issues);
        }
    }

    /**
     * Answers every breach on a board: each story's in the board's order, then each issue's.
     *
     * @param board the board
     * @return the breaches
     */
    public static List<Breach> of(final Board board) {
        final Workflow workflow = board.workflow();
        final Map<String, Issue> issues = new HashMap<>();
        board.issues().forEach(issue -> issues.put(issue.id(), issue));
        final Map<String, List<String>> children = children(board);
        final Optional<StoryState> rule = StoryState.of(workflow, List.of());
        final List<Breach> breaches = new ArrayList<>();
        for (final Story story : board.stories()) {
            belowIssue(story, issues, children, workflow, rule).ifPresent(breaches::add);
        }
        if (board.stories().stream().anyMatch(story -> !story.issuesWhole())) {
            // A story whose issues are not all known may name any issue, so none is in no story.
            return List.copyOf(breaches);
        }
        final Set<String> inStories = inStories(board);
        for (final Issue issue : board.issues()) {
            if (!inStories.contains(issue.id())
                    && workflow.states().indexOf(issue.state()) > 0
                    && !workflow.closed().contains(issue.state())) {
                breaches.add(
                        new Breach(
                                issue.id(),
                                Rule.ISSUE_IN_NO_STORY,
                                issue.id()
                                        + " stands past the workflow's first state in no"
                                        + " story.",
                                List.of(issue.id())));
            }
        }
        return List.copyOf(breaches);
    }

    /**
     * The issues of the board in a story: each a story names, and their sub-issues at any depth.
     *
     * @param board the board
     * @return the issues' ids
     */
    public static Set<String> inStories(final Board board) {
        final Map<String, List<String>> children = children(board);
        final Set<String> assigned = new HashSet<>();
        final ArrayDeque<String> pending = new ArrayDeque<>();
        for (final Story story : board.stories()) {
            for (final String id : story.issues()) {
                if (assigned.add(id)) {
                    pending.add(id);
                }
            }
        }
        while (!pending.isEmpty()) {
            for (final String id : children.getOrDefault(pending.remove(), List.of())) {
                if (assigned.add(id)) {
                    pending.add(id);
                }
            }
        }
        return assigned;
    }

    /**
     * Answers a priority's rank, 0 the highest; none for no priority or an unknown one, which is
     * never ranked, so no breach is made up from it.
     *
     * @param priority the priority, or null
     * @return its index in {@link Story#PRIORITIES}, or none
     */
    public static OptionalInt rank(final @Nullable String priority) {
        final int index = priority == null ? -1 : Story.PRIORITIES.indexOf(priority);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    private static Optional<Breach> belowIssue(
            final Story story,
            final Map<String, Issue> issues,
            final Map<String, List<String>> children,
            final Workflow workflow,
            final Optional<StoryState> rule) {
        final OptionalInt own = rank(story.priority());
        if (own.isEmpty()) {
            return Optional.empty();
        }
        @Nullable String highest = null;
        int best = own.getAsInt();
        for (final String id : withSubIssues(story.issues(), children)) {
            final Issue issue = issues.get(id);
            // An issue in review or later is built: its priority no longer asks for its story's.
            if (issue == null
                    || workflow.closed().contains(issue.state())
                    || rule.map(r -> r.reviewOrLater(issue.state())).orElse(false)) {
                continue;
            }
            final OptionalInt rank = rank(issue.priority());
            if (rank.isPresent() && rank.getAsInt() < best) {
                best = rank.getAsInt();
                highest = id;
            }
        }
        if (highest == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Breach(
                        story.id(),
                        Rule.STORY_PRIORITY_BELOW_ISSUE,
                        story.id() + " has a lower priority than its open issue " + highest + ".",
                        List.of(highest)));
    }

    private static Set<String> withSubIssues(
            final List<String> issues, final Map<String, List<String>> children) {
        final Set<String> all = new LinkedHashSet<>(issues);
        final ArrayDeque<String> pending = new ArrayDeque<>(issues);
        while (!pending.isEmpty()) {
            for (final String id : children.getOrDefault(pending.remove(), List.of())) {
                if (all.add(id)) {
                    pending.add(id);
                }
            }
        }
        return all;
    }

    private static Map<String, List<String>> children(final Board board) {
        final Map<String, List<String>> children = new HashMap<>();
        for (final Issue issue : board.issues()) {
            final String parent = issue.parent();
            if (parent != null) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(issue.id());
            }
        }
        return children;
    }
}
