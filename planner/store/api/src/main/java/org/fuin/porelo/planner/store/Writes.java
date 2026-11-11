package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one save writes to the store: each issue's new state and worker and each story's new stated
 * state.
 *
 * @param issues the issues' new states, by id
 * @param stories the stories' new stated states, by id
 * @param unstated the stories whose stated state is removed
 * @param workers the issues' new workers, by id
 */
public record Writes(
        Map<String, String> issues,
        Map<String, String> stories,
        List<String> unstated,
        Map<String, Worker> workers) {

    /** Checks the ids and copies the maps and the list, keeping their order. */
    public Writes {
        issues.keySet().forEach(Ids::issue);
        stories.keySet().forEach(Ids::story);
        unstated.forEach(Ids::story);
        issues = Collections.unmodifiableMap(new LinkedHashMap<>(issues));
        stories = Collections.unmodifiableMap(new LinkedHashMap<>(stories));
        unstated = List.copyOf(unstated);
        workers.keySet().forEach(Ids::issue);
        workers = Collections.unmodifiableMap(new LinkedHashMap<>(workers));
    }

    /**
     * Writes that set no worker.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories' new stated states, by id
     * @param unstated the stories whose stated state is removed
     */
    public Writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final List<String> unstated) {
        this(issues, stories, unstated, Map.of());
    }

    /**
     * Answers whether the writes change nothing.
     *
     * @return whether there is nothing to write
     */
    public boolean none() {
        return issues.isEmpty() && stories.isEmpty() && unstated.isEmpty() && workers.isEmpty();
    }
}
