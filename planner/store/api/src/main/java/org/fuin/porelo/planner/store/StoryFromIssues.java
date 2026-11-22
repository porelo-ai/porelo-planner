package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.Set;

/**
 * A story the planner has checked, to be made in the project's own repository over issues that
 * stand on the board; no new issue is made, and each keeps its own fields.
 *
 * @param title the story's title, one line
 * @param gain what a user gains, one line
 * @param test the test the person runs at its end, one line
 * @param priority the story's priority, {@code Highest} to {@code Lowest}
 * @param existing the issues' ids, in the story's order, each named once
 */
public record StoryFromIssues(
        String title, String gain, String test, String priority, List<String> existing) {

    /** Checks the ids and copies them to keep the story immutable. */
    public StoryFromIssues {
        existing = List.copyOf(existing);
        existing.forEach(Ids::issue);
        if (existing.isEmpty()) {
            throw new IllegalArgumentException("A story from issues names one issue at least");
        }
        if (Set.copyOf(existing).size() != existing.size()) {
            throw new IllegalArgumentException("A story from issues names each issue once");
        }
    }
}
