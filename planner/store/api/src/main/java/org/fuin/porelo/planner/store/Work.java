package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project's work rules as the store read them, and what it could not read; a fault never hides
 * the board. With them the velocity seed, the minutes an issue of a size is expected to take before
 * any finished issue of that size is measured.
 *
 * @param rules the rule per working state, in the file's order
 * @param faults what could not be read, in words
 * @param seed the seed's minutes by size; empty when the project keeps no seed
 */
public record Work(Map<String, WorkRule> rules, List<String> faults, Map<String, Double> seed) {

    /** Copies the rules, keeping their order, the faults and the seed. */
    public Work {
        rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        faults = List.copyOf(faults);
        seed = Map.copyOf(seed);
    }

    /**
     * Rules and faults without a seed.
     *
     * @param rules the rule per working state, in the file's order
     * @param faults what could not be read, in words
     */
    public Work(final Map<String, WorkRule> rules, final List<String> faults) {
        this(rules, faults, Map.of());
    }

    /**
     * The same rules and faults with the seed given.
     *
     * @param seed the seed's minutes by size
     * @return the work with the seed
     */
    public Work withSeed(final Map<String, Double> seed) {
        return new Work(rules, faults, seed);
    }
}
