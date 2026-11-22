package org.fuin.porelo.planner.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one save writes to the store: each issue's new state and worker and each story's new stated
 * state and priority.
 *
 * @param issues the issues' new states, by id
 * @param stories the stories' new stated states, by id
 * @param unstated the stories whose stated state is removed
 * @param workers the issues' new workers, by id
 * @param priorities the stories' new priorities, by id, each one of {@link Story#PRIORITIES}
 * @param issueReasons the person's reasons kept with the issues the save closes, by id, each one an
 *     issue whose new state this save writes
 * @param storyReasons the person's reasons kept with the stories the save shows closed, by id
 */
public record Writes(
        Map<String, String> issues,
        Map<String, String> stories,
        List<String> unstated,
        Map<String, Worker> workers,
        Map<String, String> priorities,
        Map<String, Reason> issueReasons,
        Map<String, Reason> storyReasons) {

    /**
     * The person's one sentence why an item is closed, kept with it.
     *
     * @param state the closed state the item is moved to
     * @param text the sentence, trimmed
     */
    public record Reason(String state, String text) {}

    /**
     * Checks the ids and the priorities and copies the maps and the list, keeping their order.
     *
     * @throws IllegalArgumentException for a priority none of {@link Story#PRIORITIES}, or a reason
     *     for an issue whose state this save does not write
     */
    public Writes {
        issues.keySet().forEach(Ids::issue);
        stories.keySet().forEach(Ids::story);
        unstated.forEach(Ids::story);
        issues = Collections.unmodifiableMap(new LinkedHashMap<>(issues));
        stories = Collections.unmodifiableMap(new LinkedHashMap<>(stories));
        unstated = List.copyOf(unstated);
        workers.keySet().forEach(Ids::issue);
        workers = Collections.unmodifiableMap(new LinkedHashMap<>(workers));
        priorities.forEach(
                (id, priority) -> {
                    Ids.story(id);
                    if (!Story.PRIORITIES.contains(priority)) {
                        throw new IllegalArgumentException(
                                "The priority of " + id + " is none of " + Story.PRIORITIES + ".");
                    }
                });
        priorities = Collections.unmodifiableMap(new LinkedHashMap<>(priorities));
        for (final String id : issueReasons.keySet()) {
            if (!issues.containsKey(id)) {
                throw new IllegalArgumentException(
                        "The reason of " + id + " goes with no state this save writes.");
            }
        }
        storyReasons.keySet().forEach(Ids::story);
        issueReasons = Collections.unmodifiableMap(new LinkedHashMap<>(issueReasons));
        storyReasons = Collections.unmodifiableMap(new LinkedHashMap<>(storyReasons));
    }

    /**
     * Writes that keep no reason.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories' new stated states, by id
     * @param unstated the stories whose stated state is removed
     * @param workers the issues' new workers, by id
     * @param priorities the stories' new priorities, by id, each one of {@link Story#PRIORITIES}
     */
    public Writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final List<String> unstated,
            final Map<String, Worker> workers,
            final Map<String, String> priorities) {
        this(issues, stories, unstated, workers, priorities, Map.of(), Map.of());
    }

    /**
     * Answers whether the writes keep a reason.
     *
     * @return whether any item gets one
     */
    public boolean reasons() {
        return !issueReasons.isEmpty() || !storyReasons.isEmpty();
    }

    /**
     * Writes that set no priority.
     *
     * @param issues the issues' new states, by id
     * @param stories the stories' new stated states, by id
     * @param unstated the stories whose stated state is removed
     * @param workers the issues' new workers, by id
     */
    public Writes(
            final Map<String, String> issues,
            final Map<String, String> stories,
            final List<String> unstated,
            final Map<String, Worker> workers) {
        this(issues, stories, unstated, workers, Map.of());
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
        this(issues, stories, unstated, Map.of(), Map.of());
    }

    /**
     * Answers whether the writes change nothing.
     *
     * @return whether there is nothing to write
     */
    public boolean none() {
        return issues.isEmpty()
                && stories.isEmpty()
                && unstated.isEmpty()
                && workers.isEmpty()
                && priorities.isEmpty()
                && !reasons();
    }
}
