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
import org.fuin.porelo.planner.store.Writes;
import org.jspecify.annotations.Nullable;

/**
 * Checks a batch of moves against the board a window shows and answers what saving them writes: all
 * of them, or a refusal and nothing.
 */
public final class Moves {

    /** The closed state that is not completed work, as the stories index reads it. */
    private static final String REJECTED = "Rejected";

    /** The most code points a move's reason holds. */
    public static final int MOST_REASON = Writes.Reason.MOST;

    private Moves() {}

    /**
     * One move as the window sends it.
     *
     * @param story whether it moves a story rather than an issue
     * @param id the story's or the issue's id
     * @param to the state it moves to
     * @param follow for a story, whether it gives up its given state for the one its issues give,
     *     which {@code to} names as the window saw it
     * @param reason the person's one sentence why the item is closed, trimmed, or null for none; a
     *     blank one counts as none
     */
    public record Move(
            boolean story, String id, String to, boolean follow, @Nullable String reason) {

        /** Trims the reason; a blank one is none. */
        public Move {
            reason = reason == null || reason.isBlank() ? null : reason.strip();
        }

        /**
         * One move without a reason.
         *
         * @param story whether it moves a story rather than an issue
         * @param id the story's or the issue's id
         * @param to the state it moves to
         * @param follow for a story, whether it gives up its given state for the one its issues
         *     give
         */
        public Move(final boolean story, final String id, final String to, final boolean follow) {
            this(story, id, to, follow, null);
        }

        /**
         * One plain move.
         *
         * @param story whether it moves a story rather than an issue
         * @param id the story's or the issue's id
         * @param to the state it moves to
         */
        public Move(final boolean story, final String id, final String to) {
            this(story, id, to, false, null);
        }
    }

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

    /** Answers whether this planner's work record holds a run over an issue or its stories. */
    @FunctionalInterface
    public interface Runs {

        /**
         * Answers whether a run works the issue, by itself or through a story that holds it.
         *
         * @param issue the issue's id
         * @param stories the stories holding the issue, directly or as a sub-issue
         * @return whether the work record holds such a run
         */
        boolean over(String issue, Set<String> stories);
    }

    /**
     * What a checked batch writes.
     *
     * @param issues each issue's new state, by id
     * @param stories each story's new stated state, by id
     * @param unstated the stories whose stated state goes, since their issues fit their target
     * @param moved the issues the batch moved, each once, as the window expects them in its answer
     * @param issueReasons the reasons kept with the issues the batch closes, by id
     * @param storyReasons the reasons kept with the stories the batch moves into a closed state, by
     *     id
     */
    public record Planned(
            Map<String, String> issues,
            Map<String, String> stories,
            List<String> unstated,
            List<String> moved,
            Map<String, Writes.Reason> issueReasons,
            Map<String, Writes.Reason> storyReasons) {

        /** Copies the maps and lists and keeps their order. */
        public Planned {
            issues = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(issues));
            stories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(stories));
            unstated = List.copyOf(unstated);
            moved = List.copyOf(moved);
            issueReasons = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(issueReasons));
            storyReasons = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(storyReasons));
        }

        /**
         * Answers whether saving changes nothing: no state is written and no reason kept.
         *
         * @return whether there is nothing to save
         */
        public boolean none() {
            return issues.isEmpty()
                    && stories.isEmpty()
                    && unstated.isEmpty()
                    && issueReasons.isEmpty()
                    && storyReasons.isEmpty();
        }

        /**
         * Answers the writes that save the batch.
         *
         * @return the writes
         */
        public Writes writes() {
            return new Writes(
                    issues, stories, unstated, Map.of(), Map.of(), issueReasons, storyReasons);
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
     * @param runs answers the runs of this planner over an issue an agent works
     * @return what saving the batch writes
     * @throws Refused when any move is refused; nothing is written then
     */
    public static Planned check(
            final Board board,
            final List<Move> moves,
            final boolean confirm,
            final StoryState derive,
            final Runs runs) {
        return plan(board, moves, confirm, derive, runs).planned();
    }

    /**
     * Answers what a batch of moves would do and what it can do harm by, writing nothing.
     *
     * @param board the board the window shows
     * @param moves the moves, in the order sent
     * @param derive derives a story's state from its issues
     * @param running answers the work running over the items the batch touches
     * @param runs answers the runs of this planner over an issue an agent works
     * @return the preview
     * @throws Refused when the save would refuse the batch for anything but a story that does not
     *     fit its issues, which is a warning here
     */
    public static MovePreview preview(
            final Board board,
            final List<Move> moves,
            final StoryState derive,
            final MovePreview.Running running,
            final Runs runs) {
        return MovePreview.of(board, moves, plan(board, moves, true, derive, runs), running);
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
            if (move.follow()) {
                // A story that follows its issues moves none of them, so it stops no work over
                // them.
                continue;
            }
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
            if (move.follow()) {
                continue;
            }
            if (move.story()) {
                stories.add(move.id());
                continue;
            }
            final Set<String> lineage = new HashSet<>();
            @Nullable String id = move.id();
            while (id != null && lineage.add(id)) {
                id = parentOf(board, id);
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
            final StoryState derive,
            final Runs runs) {
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
            checkOne(board, working, move, seen, runs);
        }
        if (moves.size() > 1 && moves.stream().anyMatch(Move::follow)) {
            // Judged with other moves, the state its issues give would be the batch's, not the
            // board's.
            final String reason = "A story follows its issues in a batch of its own.";
            throw new Refused("MoveNotAllowed", reason, item("", "", "", reason));
        }
        final Map<String, String> states = new LinkedHashMap<>();
        // An issue held back, or in a state the workflow does not know, is moved only by its own
        // move, never carried along or chosen as a story's earliest.
        board.issues().stream()
                .filter(issue -> placed(board, issue))
                .forEach(issue -> states.put(issue.id(), issue.state()));
        final Map<String, String> onBoard = Map.copyOf(states);
        final Map<String, String> issues = new LinkedHashMap<>();
        final Set<String> moved = new LinkedHashSet<>();
        final Map<String, List<String>> along = new LinkedHashMap<>();
        final Map<String, Writes.Reason> reasons = new LinkedHashMap<>();
        final Map<String, Writes.Reason> storyReasons = new LinkedHashMap<>();
        // Story moves first, so an issue's own move in the same batch wins, as the window shows it.
        for (final Move move : moves) {
            if (move.story()) {
                // A story that follows its issues again carries none: they already give its state.
                final List<String> carried;
                if (move.follow()) {
                    carried = List.of();
                } else if (completed(workflow, move.to())) {
                    carried = finished(board, story(board, move.id()), move.to(), onBoard, states);
                } else {
                    carried = leastAdvanced(workflow, story(board, move.id()), states);
                }
                along.put(move.id(), carried);
                for (final String id : carried) {
                    states.put(id, move.to());
                    issues.put(id, move.to());
                    moved.add(id);
                    final String reason = move.reason();
                    if (reason != null) {
                        reasons.put(id, new Writes.Reason(move.to(), reason));
                    }
                }
                final String reason = move.reason();
                if (reason != null
                        && !move.follow()
                        && move.to().equals(story(board, move.id()).state())) {
                    // The same move sent again, the story standing in its target already: an
                    // issue of it standing there too keeps the reason, so a save whose comment
                    // failed after the close writes it now; the store writes a comment it finds
                    // standing only once. On a first move, only the issues it closes keep one.
                    for (final String id :
                            Misfits.known(
                                    withSubIssues(board, story(board, move.id()).issues()),
                                    onBoard)) {
                        if (move.to().equals(onBoard.get(id))) {
                            reasons.putIfAbsent(id, new Writes.Reason(move.to(), reason));
                        }
                    }
                }
                if (reason != null) {
                    // Also when it stands there already: a save whose comment failed is sent again.
                    storyReasons.put(move.id(), new Writes.Reason(move.to(), reason));
                }
            }
        }
        for (final Move move : moves) {
            if (!move.story()) {
                states.put(move.id(), move.to());
                issues.put(move.id(), move.to());
                moved.add(move.id());
                // An issue's own move wins entirely, a story's reason too.
                reasons.remove(move.id());
                final String reason = move.reason();
                if (reason != null) {
                    reasons.put(move.id(), new Writes.Reason(move.to(), reason));
                }
            }
        }
        for (final Move move : moves) {
            if (move.story() && !move.follow() && completed(workflow, move.to())) {
                checkCarriedParents(board, move, along.getOrDefault(move.id(), List.of()), states);
            }
        }
        checkParents(board, workflow, states, moved);
        for (final Move move : moves) {
            if (move.story() && !move.follow() && completed(workflow, move.to())) {
                checkCompleted(board, story(board, move.id()), move.to(), states, true);
            }
        }
        final Map<String, String> stories = new LinkedHashMap<>();
        final List<String> unstated = new ArrayList<>();
        final Map<String, List<String>> misfits = new LinkedHashMap<>();
        for (final Move move : moves) {
            if (move.story()) {
                final Story story = story(board, move.id());
                if (move.follow()) {
                    checkFollow(story, move.to(), states, derive);
                    unstated.add(story.id());
                    continue;
                }
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
                new Planned(issues, stories, unstated, List.copyOf(moved), reasons, storyReasons),
                states,
                along,
                misfits);
    }

    private static void checkOne(
            final Board board,
            final Set<String> working,
            final Move move,
            final Set<String> seen,
            final Runs runs) {
        final String kind = move.story() ? "story" : "issue";
        if (move.id().isEmpty() || !seen.add(kind + " " + move.id())) {
            throw new Refused(
                    "MoveNotAllowed",
                    "Each move must name exactly one item, once.",
                    item(move.id(), "", move.to(), "Each move must name exactly one item, once."));
        }
        if (move.follow() && !move.story()) {
            final String reason = "Only a story follows its issues.";
            throw new Refused("MoveNotAllowed", reason, item(move.id(), "", move.to(), reason));
        }
        final Optional<String> held = board.holdsMove(move.id());
        if (held.isPresent()) {
            throw new Refused(
                    "MoveNotAllowed", held.get(), item(move.id(), "", move.to(), held.get()));
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
        // A story that follows its issues takes their state, a working one too, without starting
        // work.
        if (working.contains(move.to()) && !manual && !move.follow()) {
            checkWorkElsewhere(board, move, runs);
        }
        if (!board.workflow().states().contains(move.to())) {
            final String reason = "The destination must be a configured waiting or closed state.";
            throw new Refused("StateNotAllowed", reason, item(move.id(), from, move.to(), reason));
        }
        checkReason(board.workflow(), move, from);
    }

    /**
     * A reason is one plain sentence, kept only with a move that closes its item: never on a move
     * into an open state, on a story that follows its issues, or on an issue standing in its target
     * already, so no reason is ever dropped unsaid.
     */
    private static void checkReason(final Workflow workflow, final Move move, final String from) {
        final String given = move.reason();
        if (given == null) {
            return;
        }
        final String reason;
        final String refused = Writes.Reason.refusal(given);
        if (refused != null) {
            reason = refused;
        } else if (move.follow()) {
            reason = "A story that follows its issues closes nothing, so it takes no reason.";
        } else if (!workflow.closed().contains(move.to())) {
            reason = "A reason is taken only with a move into a closed state.";
        } else if (!move.story() && from.equals(move.to())) {
            reason =
                    "It is in that state already; a reason goes only with the move that closes it.";
        } else {
            return;
        }
        throw new Refused("MoveNotAllowed", reason, item(move.id(), from, move.to(), reason));
    }

    /**
     * An agent's issue enters a working state by hand only as work started elsewhere, while no run
     * of this planner works it or its story, so a hand move never stands beside a task of a run; a
     * story enters one only by starting its work.
     */
    private static void checkWorkElsewhere(final Board board, final Move move, final Runs runs) {
        final String reason;
        if (move.story()) {
            reason =
                    move.to()
                            + " is entered only by starting its work; a story is never moved there"
                            + " by hand.";
        } else if (runs.over(move.id(), stories(board, List.of(move)))) {
            reason =
                    move.to()
                            + " is entered only by its work while a run of this planner works the"
                            + " issue or its story.";
        } else {
            return;
        }
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", move.id());
        parameters.put("to", move.to());
        throw new Refused("EnteredOnlyByWork", reason, parameters);
    }

    /**
     * A story follows its issues again only from a given state, with every issue its head names
     * held, and only to the state those issues give now, so a stale board moves nothing.
     */
    private static void checkFollow(
            final Story story,
            final String to,
            final Map<String, String> states,
            final StoryState derive) {
        final String reason;
        final String now = Misfits.derivedOf(story, states, derive);
        if (!story.stateGiven()) {
            reason = "Its state is not given; it follows its issues already.";
        } else if (now == null) {
            reason =
                    "The board does not hold all of its issues, so the state they give is not known.";
        } else if (!now.equals(to)) {
            reason = "Its issues give " + now + " now.";
        } else {
            return;
        }
        throw new Refused("MoveNotAllowed", reason, item(story.id(), story.state(), to, reason));
    }

    /**
     * After the batch, no parent the batch moves stands in a closed state while a sub-issue of it
     * is open, nor any parent of a sub-issue it moves; a closed parent over open work elsewhere on
     * the board refuses no move that leaves both alone.
     */
    private static void checkParents(
            final Board board,
            final Workflow workflow,
            final Map<String, String> states,
            final Set<String> moved) {
        final Map<String, @Nullable String> parents = new LinkedHashMap<>();
        board.issues().forEach(issue -> parents.put(issue.id(), issue.parent()));
        for (final Map.Entry<String, @Nullable String> sub : parents.entrySet()) {
            final String parent = sub.getValue();
            final String state = states.get(sub.getKey());
            final String parentState = parent == null ? null : states.get(parent);
            if (parent != null
                    && (moved.contains(parent) || moved.contains(sub.getKey()))
                    && state != null
                    && parentState != null
                    && workflow.closed().contains(parentState)
                    && !workflow.closed().contains(state)) {
                final String reason =
                        "Its sub-issue "
                                + sub.getKey()
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
    static boolean completed(final Workflow workflow, final String state) {
        return workflow.closed().contains(state) && !REJECTED.equals(state);
    }

    /**
     * A story is completed only once each of its issues stands closed or in the state the last work
     * of the rules leads to, where finished work waits for the person's test; one whose store could
     * not name every issue of it never is, since an issue left out may still be open.
     */
    private static void checkCompleted(
            final Board board,
            final Story story,
            final String to,
            final Map<String, String> states,
            final boolean afterMoves) {
        if (!story.issuesWhole()) {
            final String reason =
                    "Not every issue of it is on the board, so whether all are done cannot be told.";
            throw new Refused(
                    "MoveNotAllowed", reason, item(story.id(), story.state(), to, reason));
        }
        // An issue in doubt counts as open: finishing over it would close work no one can see. One
        // held by its state alone is judged once the batch's own moves are known, in the state
        // a move of the batch gives it; without one it stays in doubt.
        for (final String id : withSubIssues(board, story.issues())) {
            final Optional<String> held = board.holdsWork(id);
            final boolean stateAlone = board.holdsMove(id).isEmpty();
            if (held.isPresent() && !(stateAlone && (!afterMoves || states.containsKey(id)))) {
                throw new Refused(
                        "MoveNotAllowed",
                        held.get(),
                        item(story.id(), story.state(), to, held.get()));
            }
        }
        final Optional<String> done = lastDone(board);
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
     * The issues a story's move to a completed state finishes with it: each of its issues and their
     * sub-issues standing in the last done state on the board before any other move of the batch,
     * and not taken elsewhere by an earlier story move of it, so only work that reached the
     * person's test is finished, whatever order the moves come in.
     */
    private static List<String> finished(
            final Board board,
            final Story story,
            final String to,
            final Map<String, String> onBoard,
            final Map<String, String> states) {
        checkCompleted(board, story, to, onBoard, false);
        final Optional<String> done = lastDone(board);
        if (done.isEmpty()) {
            return List.of();
        }
        return Misfits.known(withSubIssues(board, story.issues()), onBoard).stream()
                .filter(id -> done.get().equals(onBoard.get(id)))
                .filter(id -> done.get().equals(states.get(id)))
                .toList();
    }

    /**
     * Refuses a story's move that finishes an issue whose parent the batch leaves rejected, so work
     * under a rejected issue is never finished as accepted, the parent's own move included.
     */
    private static void checkCarriedParents(
            final Board board,
            final Move move,
            final List<String> carried,
            final Map<String, String> states) {
        for (final String id : carried) {
            final @Nullable String parent = board.issue(id).map(Issue::parent).orElse(null);
            if (move.to().equals(states.get(id))
                    && parent != null
                    && REJECTED.equals(states.get(parent))) {
                final Story story = story(board, move.id());
                final String reason =
                        "Its issue "
                                + id
                                + " would be finished under "
                                + parent
                                + ", which is "
                                + REJECTED
                                + ".";
                throw new Refused(
                        "MoveNotAllowed",
                        reason,
                        item(story.id(), story.state(), move.to(), reason));
            }
        }
    }

    /** The state the last work of the rules leads to, where finished work waits for its test. */
    private static Optional<String> lastDone(final Board board) {
        return board.work().rules().values().stream()
                .map(WorkRule::done)
                .max(Comparator.comparingInt(board.workflow().states()::indexOf));
    }

    /**
     * A story's issues followed by their sub-issues at any depth, so open work under one counts.
     */
    private static List<String> withSubIssues(final Board board, final List<String> issues) {
        final Set<String> all = new LinkedHashSet<>(issues);
        final Map<String, @Nullable String> parents = new LinkedHashMap<>();
        board.issues().forEach(each -> parents.put(each.id(), each.parent()));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (final Map.Entry<String, @Nullable String> each : parents.entrySet()) {
                final String parent = each.getValue();
                if (parent != null && all.contains(parent) && all.add(each.getKey())) {
                    grew = true;
                }
            }
        }
        return List.copyOf(all);
    }

    /** An issue's parent on the board. */
    private static @Nullable String parentOf(final Board board, final String id) {
        return board.issue(id).map(Issue::parent).orElse(null);
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

    /** A story the batch names, which its first check found on the board. */
    private static Story story(final Board board, final String id) {
        return board.story(id).orElseThrow();
    }

    /**
     * Whether a story's move may carry the issue or judge its fit by it: it is held back by
     * nothing, and its state is one of the workflow's.
     */
    private static boolean placed(final Board board, final Issue issue) {
        return board.workflow().states().contains(issue.state())
                && board.holdsWork(issue.id()).isEmpty();
    }

    private static String of(final Optional<String> value) {
        return value.orElse("");
    }

    static Map<String, String> item(
            final String id, final String from, final String to, final String reason) {
        final Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("item", id);
        parameters.put("from", from);
        parameters.put("to", to);
        parameters.put("reason", reason);
        return parameters;
    }
}
