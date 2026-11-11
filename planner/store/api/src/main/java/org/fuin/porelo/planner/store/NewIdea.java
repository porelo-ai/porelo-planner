package org.fuin.porelo.planner.store;

import java.util.List;

/**
 * An idea the planner has checked: a new story in state Idea in the project's own repository and a
 * new issue in state Idea in each repository it names, their numbers given by the store.
 *
 * @param title the story's title, one line
 * @param gain what a user gains, one line
 * @param test the test the person runs at its end, one line
 * @param priority the story's priority, {@code Highest} to {@code Lowest}
 * @param issues the new issues, each of another repository of the project
 */
public record NewIdea(
        String title, String gain, String test, String priority, List<NewIssue> issues) {

    /** Copies the issues to keep the idea immutable. */
    public NewIdea {
        issues = List.copyOf(issues);
    }

    /**
     * What a store answers for an added idea.
     *
     * @param story the id the store gave the story
     * @param issues the ids it gave the issues, in the idea's order
     * @param board the board after the save, holding them
     */
    public record Added(String story, List<String> issues, Board board) {

        /** Checks the ids and copies them. */
        public Added {
            Ids.story(story);
            issues = List.copyOf(issues);
            issues.forEach(Ids::issue);
        }
    }
}
