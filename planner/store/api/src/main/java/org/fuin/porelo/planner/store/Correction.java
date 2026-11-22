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
 * @param states the states the person picks from, in the workflow's order
 */
public record Correction(String kind, List<String> states) {

    private static final Pattern KIND = Pattern.compile("[A-Z][A-Za-z]{0,63}");

    /** The kind of an issue's move into one of the states, saved as any move of it. */
    public static final String MOVE = "Move";

    /** The kind of a story's stated state set to one of the states, writing none of its issues. */
    public static final String STATE_STATED = "StateStated";

    /** Checks the kind's spelling and copies the states. */
    public Correction {
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException(
                    "Not a correction's kind, " + kind.length() + " chars");
        }
        states = List.copyOf(states);
        if (states.isEmpty()) {
            throw new IllegalArgumentException("A correction " + kind + " offers no state");
        }
    }

    /**
     * The states a correction of an item's state offers: the workflow's open states that no work
     * alone enters, so a correction neither closes the item, which would drop its file, nor starts
     * work on it.
     *
     * @param workflow the board's workflow
     * @param working the states entered only by work
     * @return the states, in the workflow's order
     */
    public static List<String> openStates(final Workflow workflow, final Set<String> working) {
        return workflow.states().stream()
                .filter(state -> !workflow.closed().contains(state) && !working.contains(state))
                .toList();
    }

    /**
     * The correction of a state none of the workflow's, or of none told: for an issue a move into
     * one of the open states, for a story its stated state set to one of them; none when the
     * workflow has no such state, or while the work rules have faults, since a working state whose
     * rule could not be read would then be offered as open.
     *
     * @param story whether it corrects a story
     * @param workflow the board's workflow
     * @param work the board's work rules
     * @return the correction, or null when nothing can be offered
     */
    public static @org.jspecify.annotations.Nullable Correction ofState(
            final boolean story, final Workflow workflow, final Work work) {
        if (!work.faults().isEmpty()) {
            return null;
        }
        final List<String> states = openStates(workflow, work.rules().keySet());
        return states.isEmpty() ? null : new Correction(story ? STATE_STATED : MOVE, states);
    }
}
