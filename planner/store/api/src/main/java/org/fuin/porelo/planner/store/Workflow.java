package org.fuin.porelo.planner.store;

import java.util.HashSet;
import java.util.List;

/**
 * The states a story or an issue moves through, in their order, and the phases that group them on
 * the board.
 *
 * @param states every state, in the workflow's order
 * @param phases the phases, each naming states of {@code states}
 * @param closed the states that close a story or an issue, in the workflow's order
 */
public record Workflow(List<String> states, List<Phase> phases, List<String> closed) {

    /**
     * Copies the lists and refuses a state named twice, a phase naming an unknown state or an
     * unknown closed state.
     */
    public Workflow {
        states = List.copyOf(states);
        phases = List.copyOf(phases);
        closed = List.copyOf(closed);
        if (!states.containsAll(closed)) {
            throw new IllegalArgumentException(
                    "A workflow closes states it does not name: " + closed);
        }
        if (states.isEmpty()) {
            throw new IllegalArgumentException("A workflow must name at least one state");
        }
        if (new HashSet<>(states).size() != states.size()) {
            throw new IllegalArgumentException("A workflow names a state twice: " + states);
        }
        for (final Phase phase : phases) {
            for (final String state : phase.states()) {
                if (!states.contains(state)) {
                    throw new IllegalArgumentException(
                            "Phase '" + phase.name() + "' names an unknown state '" + state + "'");
                }
            }
        }
    }

    /**
     * A named group of states, shown as one column of the board.
     *
     * @param name the phase's name
     * @param states the states it groups
     */
    public record Phase(String name, List<String> states) {

        /** Copies the states. */
        public Phase {
            states = List.copyOf(states);
        }
    }
}
