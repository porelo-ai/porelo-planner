package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.WorkRule;
import org.fuin.porelo.planner.store.Worker;
import org.fuin.porelo.planner.store.Workflow;
import org.jspecify.annotations.Nullable;

/**
 * Checks a batch of moves against the board a window shows and answers what saving them writes: all
 * of them, or a refusal and nothing.
 */
public final class Moves {

    /** The closed state that is not completed work, as the stories index reads it. */
    private static final String REJECTED = "Rejected";

    private Moves() {}

    /**
     * One move as the window sends it.
     *
     * @param story whether it moves a story rather than an issue
     * @param id the story's or the issue's id
     * @param to the state it moves to
     */
    public record Move(boolean story, String id, String to) {}

    /** Derives a story's state from its issues' states, as the stories index does. */
    @FunctionalInterface
    public interface StoryState {

        /**
         * Answers the state that follows from the issues' states.
         *
         * @param issueStates the states of the story's issues
         * @return the story's state, or null when none follows
         */
        @Nullable String of(List<String> issueStates);
    }

    /**
     * What a checked batch writes.
     *
     * @param issues each issue's new state, by id
     * @param stories each story's new stated state, by id
     * @param unstated the stories whose stated state goes, since their issues fit their target
     * @param moved the issues the batch moved, each once, as the window expects them in its answer
     */
    public record Planned(
            Map<String, String> issues,
            Map<String, String> stories,
            List<String> unstated,
            List<String> moved) {

        /** Copies the maps and lists and keeps their order. */
        public Planned {
            issues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(issues));
            stories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stories));
            unstated = List.copyOf(unstated);
            moved = List.copyOf(moved);
        }
    }

    /** A batch refused, with the name and the parameters the window reads. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String name;

        private final Map<String, String> parameters;

        /**
         * Makes a refusal.
         *
         * @param name the refusal's name
         * @param detail the words the window shows
         * @param parameters its parameters, in order
         */
        public Refused(
                final String name, final String detail, final Map<String, String> parameters) {
            super(detail);
            this.name = name;
            this.parameters =
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }

        /**
         * Answers the refusal's name.
         *
         * @return the name
         */
        public String name() {
            return name;
        }

        /**
         * Answers the refusal's parameters.
         *
         * @return the parameters, in order
         */
        public Map<String, String> parameters() {
            return parameters;
        }
    }

    /**
     * Checks a batch of moves.
     *
     * @param board the board the window shows; its work rules name the states entered only by work
     * @param moves the moves, in the order sent
     * @param confirm whether a story may stand where its issues do not fit
     * @param derive derives a story's state from its issues
     * @return what saving the batch writes
     * @throws Refused when any move is refused; nothing is written then
     */
    public static Planned check(
            final Board board,
            final List<Move> moves,
            final boolean confirm,
            final StoryState derive) {
        return plan(board, moves, confirm, derive).planned();
    }

    /**
     * Answers what a batch of moves would do and what it can do harm by, writing nothing.
     *
     * @param board the board the window shows
     * @param moves the moves, in the order sent
     * @param derive derives a story's state from its issues
     * @param running answers the work running over the items the batch touches
     * @return the preview
     * @throws Refused when the save would refuse the batch for anything but a story that does not
     *     fit its issues, which is a warning here
     */
    public static MovePreview preview(
            final Board board,
            final List<Move> moves,
            final StoryState derive,
            final MovePreview.Running running) {
        return MovePreview.of(board, moves, plan(board, moves, true, derive), running);
    }

    /**
     * The issues a batch touches: each issue moved by itself, and each issue of a story moved with
     * every sub-issue of it, as a run's ownership reads them.
     *
     * @param board the board
     * @param moves the moves
     * @return the issues' ids
     */
    public static Set<String> touched(final Board board, final List<Move> moves) {
        final Set<String> affected = new LinkedHashSet<>();
        for (final Move move : moves) {
            if (!move.story()) {
                affected.add(move.id());
                continue;
            }
            final Story story = board.story(move.id()).orElse(null);
            if (story == null) {
                continue;
            }
            affected.addAll(story.issues());
            for (final Issue issue : board.issues()) {
                final String parent = issue.parent();
                if (parent != null && story.issues().contains(parent)) {
                    affected.add(issue.id());
                }
            }
        }
        return affected;
    }

    /**
     * The stories whose running work a batch moves under: each story it moves, and each story
     * holding an issue it moves by itself, directly or as a sub-issue, as a run's ownership reads
     * them.
     *
     * @param board the board
     * @param moves the moves
     * @return the stories' ids
     */
    public static Set<String> stories(final Board board, final List<Move> moves) {
        final Set<String> stories = new LinkedHashSet<>();
        for (final Move move : moves) {
            if (move.story()) {
                stories.add(move.id());
                continue;
            }
            final Set<String> lineage = new HashSet<>();
            @Nullable String id = move.id();
            while (id != null && lineage.add(id)) {
                id = board.issue(id).map(Issue::parent).orElse(null);
            }
            board.stories().stream()
                    .filter(story -> story.issues().stream().anyMatch(lineage::contains))
                    .forEach(story -> stories.add(story.id()));
        }
        return stories;
    }

    /**
     * A checked batch with what its preview reads: the planned writes, each issue's state after the
     * batch, the issues each story's move carried and the stories that do not fit.
     */
    record Plan(
            Planned planned,
            Map<String, String> states,
            Map<String, List<String>> along,
            Map<String, List<String>> misfits) {}

    private static Plan plan(
            final Board board,
            final List<Move> moves,
            final boolean confirm,
            final StoryState derive) {
        final Workflow workflow = board.workflow();
        if (!board.work().faults().isEmpty()) {
            // A working state whose rule could not be read would be entered without its work.
            final String reason =
                    "The work rules have faults: " + String.join("; ", board.work().faults());
            throw new Refused("MoveNotAllowed", reason, item("", "", "", reason));
        }
        final Set<String> working = board.work().rules().keySet();
        final Set<String> seen = new HashSet<>();
        for (final Move move : moves) {
            checkOne(board, working, move, seen);
        }
        final Map<String, String> states = new LinkedHashMap<>();
        board.issues().forEach(issue -> states.put(issue.id(), issue.state()));
        final Map<String, String> issues = new LinkedHashMap<>();
        final Set<String> moved = new LinkedHashSet<>();
        final Map<String, List<String>> along = new LinkedHashMap<>();
        // Story moves first, so an issue's own move in the same batch wins, as the window shows it.
        for (final Move move : moves) {
            if (move.story()) {
                // A story is completed over its issues' own moves, never by carrying them along.
                final List<String> carried =
                        completed(workflow, move.to())
                                ? List.of()
                                : leastAdvanced(workflow, story(board, move.id()), states);
                along.put(move.id(), carried);
                for (final String id : carried) {
                    states.put(id, move.to());
                    issues.put(id, move.to());
                    moved.add(id);
                }
            }
        }
        for (final Move move : moves) {
            if (!move.story()) {
                states.put(move.id(), move.to());
                issues.put(move.id(), move.to());
                moved.add(move.id());
            }
        }
        checkParents(board, workflow, states);
        for (final Move move : moves) {
            if (move.story() && completed(workflow, move.to())) {
                checkCompleted(board, story(board, move.id()), move.to(), states);
            }
        }
        final Map<String, String> stories = new LinkedHashMap<>();
        final List<String> unstated = new ArrayList<>();
        final Map<String, List<String>> misfits = new LinkedHashMap<>();
        for (final Move move : moves) {
            if (move.story()) {
                final Story story = story(board, move.id());
                // A story none of whose issues the board holds has nothing to fit, as one without.
                if (Misfits.known(story.issues(), states).isEmpty()) {
                    stories.put(story.id(), move.to());
                } else if (!move.to().equals(Misfits.derived(story.issues(), states, derive))) {
                    if (!confirm) {
                        throw mismatch(story, move.to(), states, derive);
                    }
                    misfits.put(story.id(), Misfits.of(story.issues(), move.to(), states, derive));
                    stories.put(story.id(), move.to());
                } else if (story.stateGiven()) {
                    unstated.add(story.id());
                }
            }
        }
        // Only a changed head is written; the window still hears of every issue it moved.
        board.issues()
                .forEach(
                        issue -> {
                            if (issue.state().equals(issues.get(issue.id()))) {
                                issues.remove(issue.id());
                            }
                        });
        board.stories()
                .forEach(
                        story -> {
                            if (story.stateGiven()
                                    && story.state().equals(stories.get(story.id()))) {
                                stories.remove(story.id());
                            }
                        });
        return new Plan(
                new Planned(issues, stories, unstated, List.copyOf(moved)), states, along, misfits);
    }

    private static void checkOne(
            final Board board, final Set<String> working, final Move move, final Set<String> seen) {
        final String kind = move.story() ? "story" : "issue";
        if (move.id().isEmpty() || !seen.add(kind + " " + move.id())) {
            throw new Refused(
                    "MoveNotAllowed",
                    "Each move must name exactly one item, once.",
                    item(move.id(), "", move.to(), "Each move must name exactly one item, once."));
        }
        final String from;
        boolean manual = false;
        if (move.story()) {
            from =
                    board.story(move.id())
                            .orElseThrow(
                                    () ->
                                            new Refused(
                                                    "NoSuchStory",
                                                    "There is no story " + move.id() + ".",
                                                    Map.of("story", move.id())))
                            .state();
        } else {
            final Issue issue =
                    board.issue(move.id())
                            .orElseThrow(
                                    () ->
                                            new Refused(
                                                    "NoSuchIssue",
                                                    "There is no issue " + move.id() + ".",
                                                    Map.of("issue", move.id())));
            from = issue.state();
            manual = issue.worker() == Worker.MANUAL;
        }
        if (working.contains(move.to()) && !manual) {
            final Map<String, String> parameters = new LinkedHashMap<>();
            parameters.put("item", move.id());
            parameters.put("to", move.to());
            throw new Refused(
                    "EnteredOnlyByWork",
                    move.to()
                            + " is entered only by starting its work; only an issue a person"
                            + " works is moved there by hand.",
                    parameters);
        }
        if (!board.workflow().states().contains(move.to())) {
            final String reason = "The destination must be a configured waiting or closed state.";
            throw new Refused("StateNotAllowed", reason, item(move.id(), from, move.to(), reason));
        }
    }

    /** After the batch, no parent stands in a closed state while a sub-issue of it is open. */
    private static void checkParents(
            final Board board, final Workflow workflow, final Map<String, String> states) {
        for (final Issue sub : board.issues()) {
            final String parent = sub.parent();
            final String state = states.get(sub.id());
            final String parentState = parent == null ? null : states.get(parent);
            if (parent != null
                    && state != null
                    && parentState != null
                    && workflow.closed().contains(parentState)
                    && !workflow.closed().contains(state)) {
                final String reason =
                        "Its sub-issue "
                                + sub.id()
                                + " stands in "
                                + state
                                + ", which is not closed.";
                throw new Refused(
                        "MoveNotAllowed",
                        reason,
                        item(
                                parent,
                                of(board.issue(parent).map(Issue::state)),
                                parentState,
                                reason));
            }
        }
    }

    /** Whether a state completes the work: closed, but not the rejection. */
    private static boolean completed(final Workflow workflow, final String state) {
        return workflow.closed().contains(state) && !REJECTED.equals(state);
    }

    /**
     * A story is completed only once each of its issues stands closed or in the state the last work
     * of the rules leads to, where finished work waits for the person's test.
     */
    private static void checkCompleted(
            final Board board,
            final Story story,
            final String to,
            final Map<String, String> states) {
        final Optional<String> done =
                board.work().rules().values().stream()
                        .map(WorkRule::done)
                        .max(Comparator.comparingInt(board.workflow().states()::indexOf));
        final Set<String> finished = new HashSet<>(board.workflow().closed());
        done.ifPresent(finished::add);
        final List<String> open =
                Misfits.known(withSubIssues(board, story.issues()), states).stream()
                        .filter(id -> !finished.contains(states.get(id)))
                        .toList();
        if (!open.isEmpty()) {
            final String reason =
                    "Its issues "
                            + open.stream()
                                    .map(id -> id + ":" + states.get(id))
                                    .collect(Collectors.joining(", "))
                            + done.map(state -> " stand neither in " + state + " nor closed.")
                                    .orElse(" are not closed.");
            throw new Refused(
                    "MoveNotAllowed", reason, item(story.id(), story.state(), to, reason));
        }
    }

    /**
     * A story's issues followed by their sub-issues at any depth, so open work under one counts.
     */
    private static List<String> withSubIssues(final Board board, final List<String> issues) {
        final Set<String> all = new LinkedHashSet<>(issues);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final Issue each : board.issues()) {
                if (each.parent() != null && all.contains(each.parent()) && all.add(each.id())) {
                    grew = true;
                }
            }
        }
        return List.copyOf(all);
    }

    /** A story's issues in the earliest state, among the open ones while any is open. */
    private static List<String> leastAdvanced(
            final Workflow workflow, final Story story, final Map<String, String> states) {
        final List<String> known = story.issues().stream().filter(states::containsKey).toList();
        final List<String> open =
                known.stream().filter(id -> !workflow.closed().contains(states.get(id))).toList();
        final List<String> candidates = open.isEmpty() ? known : open;
        final int earliest =
                candidates.stream()
                        .mapToInt(id -> workflow.states().indexOf(states.get(id)))
                        .min()
                        .orElse(-1);
        return candidates.stream()
                .filter(id -> workflow.states().indexOf(states.get(id)) == earliest)
                .toList();
    }

    private static Refused mismatch(
            final Story story,
            final String to,
            final Map<String, String> states,
            final StoryState derive) {
        final String misfits =
                Misfits.of(story.issues(), to, states, derive).stream()
                        .map(id -> id + ":" + states.get(id))
                        .collect(Collectors.joining(", "));
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("story", story.id());
        parameters.put("to", to);
        parameters.put("issues", misfits);
        return new Refused(
                "StoryStateMismatch",
                story.id() + " does not fit " + to + " while its issues stand in " + misfits + ".",
                parameters);
    }

    private static Story story(final Board board, final String id) {
        return board.story(id).orElseThrow();
    }

    private static String of(final Optional<String> value) {
        return value.orElse("");
    }

    private static Map<String, String> item(
            final String id, final String from, final String to, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", id);
        parameters.put("from", from);
        parameters.put("to", to);
        parameters.put("reason", reason);
        return parameters;
    }
}
