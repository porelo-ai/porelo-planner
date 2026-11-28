package org.fuin.porelo.planner.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Issue;
import org.jspecify.annotations.Nullable;

/**
 * Judges many issues moved to their states in one call: each move the rules allow is kept and saved
 * with the others in one save, each the rules refuse is dropped with its refusal. The rules are
 * those of {@link Moves}.
 */
public final class MoveIssues {

    /** What became of one move. */
    public enum Outcome {
        /** The move stands in the save. */
        SAVED,
        /** The issue stood in the state already, with no reason to write. */
        UNCHANGED,
        /** The rules refuse the move. */
        REFUSED
    }

    /**
     * One issue asked to move, through the states it passes on its way, in order.
     *
     * @param issue the issue's id
     * @param to the state it moves to
     * @param reason the person's one sentence why the issue is closed, or null for none; it goes
     *     with the last step alone
     * @param through the states the issue passes before {@code to}, in order, each saved as a move
     *     of its own; empty for a move of one step
     */
    public record Ask(String issue, String to, @Nullable String reason, List<String> through) {

        /** Counts a blank reason as none, as a move does, and copies the states passed. */
        public Ask {
            reason = reason == null || reason.isBlank() ? null : reason.strip();
            through = List.copyOf(through);
        }

        /**
         * One issue asked to move in one step.
         *
         * @param issue the issue's id
         * @param to the state it moves to
         * @param reason the person's one sentence why the issue is closed, or null for none
         */
        public Ask(final String issue, final String to, final @Nullable String reason) {
            this(issue, to, reason, List.of());
        }
    }

    /**
     * The answer for one move.
     *
     * @param issue the issue's id
     * @param outcome what became of the move
     * @param state the issue's state after the save, or null when the move is refused
     * @param refusal why the move is refused, a {@link Moves.Refused} or a {@link WorkRefusal}, or
     *     null when it is not
     */
    public record Item(
            String issue,
            Outcome outcome,
            @Nullable String state,
            @Nullable RuntimeException refusal) {}

    /**
     * A batch judged on one board.
     *
     * @param items the answer for each move, in the order asked
     * @param moves the moves the save carries
     * @param planned what saving those moves writes
     * @param preview what the save checks the board's targets by, or null when no move is carried
     */
    public record Judged(
            List<Item> items,
            List<Moves.Move> moves,
            Moves.Planned planned,
            @Nullable MovePreview preview) {

        /** Copies the lists. */
        public Judged {
            items = List.copyOf(items);
            moves = List.copyOf(moves);
        }

        /**
         * Answers whether anything is to be written.
         *
         * @return whether a save is needed
         */
        public boolean saves() {
            return !planned.none();
        }
    }

    private static final Moves.Planned NOTHING =
            new Moves.Planned(Map.of(), Map.of(), List.of(), Map.of(), Map.of());

    private MoveIssues() {}

    /**
     * Judges the moves: the whole batch is checked, and a move named by a refusal is dropped and
     * the rest checked again, until the rest stand; a refusal naming no move of the batch refuses
     * all that are left. A move over running work is refused, since no person confirms it here.
     *
     * @param board the board the moves are judged on
     * @param asks the moves, in the order sent
     * @param keepsReasons whether the store keeps a reason with a closed issue
     * @param running answers the runs with tasks at work over what a move touches
     * @param runs answers the runs of this planner over an issue an agent works
     * @return what each move came to and what saving the rest writes
     */
    public static Judged judge(
            final Board board,
            final List<Ask> asks,
            final boolean keepsReasons,
            final MovePreview.Running running,
            final Moves.Runs runs) {
        final @Nullable RuntimeException[] refusals = new RuntimeException[asks.size()];
        final Set<String> once = new HashSet<>();
        final Set<String> twice = new HashSet<>();
        for (final Ask ask : asks) {
            if (!once.add(ask.issue())) {
                twice.add(ask.issue());
            }
        }
        for (int i = 0; i < asks.size(); i++) {
            final Ask ask = asks.get(i);
            if (twice.contains(ask.issue())) {
                refusals[i] = notAllowed(board, ask, "Each move must name exactly one item, once.");
            } else if (ask.reason() != null && !keepsReasons) {
                refusals[i] = notAllowed(board, ask, "this store keeps no reason");
            } else if (!ask.through().isEmpty()) {
                refusals[i] = steps(board, ask, runs);
            }
        }
        // A move through stages is judged in the batch by its last step, from the last state it
        // passes, so a target the rules allow only from that stage is not refused as a jump.
        Board staged = board;
        for (int i = 0; i < asks.size(); i++) {
            final Ask ask = asks.get(i);
            if (refusals[i] == null && !ask.through().isEmpty()) {
                staged = moved(staged, ask.issue(), ask.through().getLast());
            }
        }
        final Board judgedOn = staged;
        Moves.Planned planned = NOTHING;
        List<Moves.Move> moves = List.of();
        while (true) {
            final List<Integer> live = live(refusals);
            moves = live.stream().map(i -> move(asks.get(i))).toList();
            if (moves.isEmpty()) {
                planned = NOTHING;
                break;
            }
            try {
                planned = Moves.check(judgedOn, moves, runs);
            } catch (final Moves.Refused refused) {
                final int at = named(live, asks, refused);
                if (at < 0) {
                    live.forEach(i -> refusals[i] = refused);
                    moves = List.of();
                    planned = NOTHING;
                    break;
                }
                refusals[at] = refused;
                continue;
            }
            if (!runningOver(judgedOn, live, moves, refusals, running)) {
                break;
            }
        }
        return answer(board, asks, refusals, moves, planned, running, runs);
    }

    private static Judged answer(
            final Board board,
            final List<Ask> asks,
            final @Nullable RuntimeException[] refusals,
            final List<Moves.Move> moves,
            final Moves.Planned planned,
            final MovePreview.Running running,
            final Moves.Runs runs) {
        final List<Item> items = new ArrayList<>();
        for (int i = 0; i < asks.size(); i++) {
            final Ask ask = asks.get(i);
            if (refusals[i] != null) {
                items.add(new Item(ask.issue(), Outcome.REFUSED, null, refusals[i]));
            } else if (planned.issues().containsKey(ask.issue())
                    || planned.issueReasons().containsKey(ask.issue())) {
                items.add(new Item(ask.issue(), Outcome.SAVED, ask.to(), null));
            } else {
                final String state = board.issue(ask.issue()).map(Issue::state).orElse(ask.to());
                items.add(new Item(ask.issue(), Outcome.UNCHANGED, state, null));
            }
        }
        final MovePreview preview =
                planned.none() ? null : Moves.preview(board, moves, running, runs);
        return new Judged(items, moves, planned, preview);
    }

    /** Refuses each live move a run with tasks at work owns, and answers whether any was. */
    private static boolean runningOver(
            final Board board,
            final List<Integer> live,
            final List<Moves.Move> moves,
            final @Nullable RuntimeException[] refusals,
            final MovePreview.Running running) {
        boolean refused = false;
        for (int at = 0; at < live.size(); at++) {
            final List<Moves.Move> one = List.of(moves.get(at));
            final List<MovePreview.Owned> owned =
                    running.over(Moves.touched(board, one), Moves.stories(board, one));
            if (!owned.isEmpty()) {
                refusals[live.get(at)] =
                        WorkRefusal.workRunning(owned.getFirst().story(), owned.getFirst().tasks());
                refused = true;
            }
        }
        return refused;
    }

    /**
     * Judges each step of a move through stages as a move of its own, on the board the steps before
     * it leave, so a stage the rules refuse refuses the whole move with that step named; no closed
     * state is passed on the way and none is named twice.
     *
     * @return the first step's refusal, or null when every step stands
     */
    private static @Nullable RuntimeException steps(
            final Board board, final Ask ask, final Moves.Runs runs) {
        final @Nullable Issue issue = board.issue(ask.issue()).orElse(null);
        if (issue == null) {
            // The batch's own check refuses an issue the board lacks.
            return null;
        }
        final Set<String> passed = new HashSet<>(List.of(issue.state(), ask.to()));
        for (final String state : ask.through()) {
            if (board.workflow().closed().contains(state)) {
                return notAllowed(board, ask, "An issue passes no closed state on its way.");
            }
            if (!passed.add(state)) {
                return notAllowed(
                        board,
                        ask,
                        "Each state passed must be named once, and neither the issue's own state"
                                + " nor its target.");
            }
        }
        final List<String> steps = new ArrayList<>(ask.through());
        steps.add(ask.to());
        Board at = board;
        for (int step = 0; step < steps.size(); step++) {
            final String to = steps.get(step);
            final @Nullable String reason = step == steps.size() - 1 ? ask.reason() : null;
            try {
                Moves.check(at, List.of(new Moves.Move(false, ask.issue(), to, reason)), runs);
            } catch (final Moves.Refused refused) {
                return refused;
            }
            at = moved(at, ask.issue(), to);
        }
        return null;
    }

    /** The board with one issue in another state, as a step of a move leaves it. */
    private static Board moved(final Board board, final String id, final String state) {
        return new Board(
                board.revision(),
                board.workflow(),
                board.stories(),
                board.issues().stream()
                        .map(issue -> issue.id().equals(id) ? issue.withState(state) : issue)
                        .toList(),
                board.work(),
                board.repositories(),
                board.faults(),
                board.owner(),
                board.unlisted(),
                board.repositoryWarnings());
    }

    private static List<Integer> live(final @Nullable RuntimeException[] refusals) {
        final List<Integer> live = new ArrayList<>();
        for (int i = 0; i < refusals.length; i++) {
            if (refusals[i] == null) {
                live.add(i);
            }
        }
        return live;
    }

    /** The index of the ask a refusal names among the live ones, or -1 for none. */
    private static int named(
            final List<Integer> live, final List<Ask> asks, final Moves.Refused refused) {
        @Nullable String item = refused.parameters().get("item");
        if (item == null) {
            item = refused.parameters().get("issue");
        }
        for (final int i : live) {
            if (asks.get(i).issue().equals(item)) {
                return i;
            }
        }
        return -1;
    }

    private static Moves.Move move(final Ask ask) {
        return new Moves.Move(false, ask.issue(), ask.to(), ask.reason());
    }

    private static Moves.Refused notAllowed(final Board board, final Ask ask, final String reason) {
        final String from = board.issue(ask.issue()).map(Issue::state).orElse("");
        return new Moves.Refused(
                "MoveNotAllowed", reason, Moves.item(ask.issue(), from, ask.to(), reason));
    }
}
