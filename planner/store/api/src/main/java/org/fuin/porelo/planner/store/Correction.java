package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one change a store offers to set a warning right, which the person picks and confirms on the
 * board; a warning the store cannot set right carries none.
 *
 * @param kind an open name of the change, such as {@link #MOVE}; a later store may name one of its
 *     own
 * @param choices what the person picks from: the states, in the workflow's order, for a {@link
 *     #MOVE}; the priorities, the highest first, for a {@link #PRIORITY}; none for {@link #LABELS},
 *     the one kind without a choice; at most {@link #MOST_CHOICES}, each a name the window can send
 *     back as its choice
 */
public record Correction(String kind, List<String> choices) {

    private static final Pattern KIND = Pattern.compile("[A-Z][A-Za-z]{0,63}");

    /** The most choices a correction offers, as the window's description bounds them. */
    public static final int MOST_CHOICES = 64;

    /** A choice as the window sends it back: a state's or a priority's name. */
    private static final Pattern CHOICE = Pattern.compile("[A-Za-z][A-Za-z0-9 _-]{0,63}");

    /** The kind of an issue's move into one of the states, saved as any move of it. */
    public static final String MOVE = "Move";

    /** The kind of a story's priority set to the one picked, saved as its priority alone. */
    public static final String PRIORITY = "Priority";

    /** The kind of a repository's labels brought to the topic labels; it offers no choice. */
    public static final String LABELS = "Labels";

    /**
     * Checks the kind's spelling, the choices' count and spelling, and copies the choices: none for
     * a Labels, some for any other.
     *
     * @throws IllegalArgumentException for a kind or a choice the window cannot send back, a choice
     *     for a Labels, none for another kind, or more than {@link #MOST_CHOICES}
     */
    public Correction {
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException(
                    "Not a correction's kind, " + kind.length() + " chars");
        }
        choices = List.copyOf(choices);
        if (choices.isEmpty() != LABELS.equals(kind)) {
            throw new IllegalArgumentException(
                    LABELS.equals(kind)
                            ? "A correction Labels offers no choice"
                            : "A correction " + kind + " offers no choice");
        }
        if (choices.size() > MOST_CHOICES) {
            throw new IllegalArgumentException(
                    "A correction " + kind + " offers " + choices.size() + " choices");
        }
        if (!choices.stream().allMatch(Correction::sendable)) {
            throw new IllegalArgumentException(
                    "A correction " + kind + " offers a choice the window cannot send back");
        }
    }

    /**
     * Whether a choice is a name the window can send back as one.
     *
     * @param choice the choice
     * @return whether it is a state's or a priority's name as a correction takes it
     */
    public static boolean sendable(final String choice) {
        return CHOICE.matcher(choice).matches();
    }

    /**
     * The correction of a repository's labels: the calls that bring them to the topic labels.
     *
     * @return the correction, without a choice
     */
    public static Correction labels() {
        return new Correction(LABELS, List.of());
    }

    /**
     * The states a correction of an item's state offers: the workflow's open states that no work
     * alone enters, so a correction neither closes the item, which would drop its file, nor starts
     * work on it; a state whose name the window cannot send back as a choice is left out, and so is
     * every one past {@link #MOST_CHOICES}.
     *
     * @param workflow the board's workflow
     * @param working the states entered only by work
     * @return the states, in the workflow's order
     */
    public static List<String> openStates(final Workflow workflow, final Set<String> working) {
        return workflow.states().stream()
                .filter(state -> !workflow.closed().contains(state) && !working.contains(state))
                .filter(Correction::sendable)
                .limit(MOST_CHOICES)
                .toList();
    }

    /**
     * The correction of an issue's state none of the workflow's: a move into one of the open
     * states; none when the workflow has no such state the window can send back, or while the work
     * rules have faults, since a working state whose rule could not be read would then be offered
     * as open. A story has no state of its own to correct.
     *
     * @param workflow the board's workflow
     * @param work the board's work rules
     * @return the correction, or null when nothing can be offered
     */
    public static @org.jspecify.annotations.Nullable Correction ofState(
            final Workflow workflow, final Work work) {
        if (!work.faults().isEmpty()) {
            return null;
        }
        final List<String> states = openStates(workflow, work.rules().keySet());
        return states.isEmpty() ? null : new Correction(MOVE, states);
    }

    /**
     * The correction of a story's priority that is missing or none the planner knows: the priority
     * set to one the store can hold; none while the work rules have faults, as no correction is
     * offered then, or when the store holds no priority.
     *
     * @param work the board's work rules
     * @param priorities the priorities the store can hold, the highest first, each one of {@link
     *     Story#PRIORITIES}
     * @return the correction, or null when nothing can be offered
     * @throws IllegalArgumentException for a priority none of {@link Story#PRIORITIES}
     */
    public static @org.jspecify.annotations.Nullable Correction ofPriority(
            final Work work, final List<String> priorities) {
        if (!Story.PRIORITIES.containsAll(priorities)) {
            throw new IllegalArgumentException("A priority is none of " + Story.PRIORITIES);
        }
        if (!work.faults().isEmpty() || priorities.isEmpty()) {
            return null;
        }
        return new Correction(PRIORITY, priorities);
    }
}
