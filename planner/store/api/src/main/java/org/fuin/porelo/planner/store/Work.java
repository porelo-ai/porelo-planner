package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project's work rules as the store read them, and what it could not read; a fault never hides
 * the board.
 *
 * @param rules the rule per working state, in the file's order
 * @param faults what could not be read, in words
 */
public record Work(Map<String, WorkRule> rules, List<String> faults) {

    /** Copies the rules, keeping their order, and the faults. */
    public Work {
        rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        faults = List.copyOf(faults);
    }
}
