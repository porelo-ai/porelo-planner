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
 * @param roles the roles the project defines for its tasks by name, in the file's order, those no
 *     rule offers among them
 */
public record Work(
        Map<String, WorkRule> rules,
        List<String> faults,
        Map<String, Double> seed,
        Map<String, Role> roles) {

    /**
     * Copies the rules and the roles, keeping their order, the faults and the seed, and refuses a
     * role kept under another name than its own or a rule offering a role the roles do not hold.
     */
    public Work {
        rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        faults = List.copyOf(faults);
        seed = Map.copyOf(seed);
        roles = Collections.unmodifiableMap(new LinkedHashMap<>(roles));
        for (final Map.Entry<String, Role> role : roles.entrySet()) {
            if (!role.getKey().equals(role.getValue().name())) {
                throw new IllegalArgumentException(
                        "The role '"
                                + role.getValue().name()
                                + "' is kept as '"
                                + role.getKey()
                                + "'");
            }
        }
        for (final Map.Entry<String, WorkRule> rule : rules.entrySet()) {
            final Role offered = rule.getValue().role();
            if (!offered.equals(roles.get(offered.name()))) {
                throw new IllegalArgumentException(
                        "The rule '"
                                + rule.getKey()
                                + "' offers a role '"
                                + offered.name()
                                + "' the roles do not define so");
            }
        }
    }

    /**
     * Rules, faults and a seed, with exactly the roles the rules offer.
     *
     * @param rules the rule per working state, in the file's order
     * @param faults what could not be read, in words
     * @param seed the seed's minutes by size
     */
    public Work(
            final Map<String, WorkRule> rules,
            final List<String> faults,
            final Map<String, Double> seed) {
        this(rules, faults, seed, offered(rules));
    }

    private static Map<String, Role> offered(final Map<String, WorkRule> rules) {
        final Map<String, Role> roles = new LinkedHashMap<>();
        rules.values().forEach(rule -> roles.putIfAbsent(rule.role().name(), rule.role()));
        return roles;
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
        return new Work(rules, faults, seed, roles);
    }
}
