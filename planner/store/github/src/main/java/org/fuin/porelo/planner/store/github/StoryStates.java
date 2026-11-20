package org.fuin.porelo.planner.store.github;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** Derives a story's state from its issues' states, by the project's workflow. */
@FunctionalInterface
public interface StoryStates {

    /**
     * Derives the state.
     *
     * @param issueStates the states of the story's issues
     * @return the story's state, or null when none follows
     */
    @Nullable String storyState(List<String> issueStates);
}
