package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one save writes to the store: each issue's new state and each story's new stated state.
 *
 * @param issues the issues' new states, by id
 * @param stories the stories' new stated states, by id
 * @param unstated the stories whose stated state is removed
 */
public record Writes(
        Map<String, String> issues, Map<String, String> stories, List<String> unstated) {

    /** Checks the ids and copies the maps and the list, keeping their order. */
    public Writes {
        issues.keySet().forEach(Ids::issue);
        stories.keySet().forEach(Ids::story);
        unstated.forEach(Ids::story);
        issues = Collections.unmodifiableMap(new LinkedHashMap<>(issues));
        stories = Collections.unmodifiableMap(new LinkedHashMap<>(stories));
        unstated = List.copyOf(unstated);
    }

    /**
     * Answers whether the writes change nothing.
     *
     * @return whether there is nothing to write
     */
    public boolean none() {
        return issues.isEmpty() && stories.isEmpty() && unstated.isEmpty();
    }
}
