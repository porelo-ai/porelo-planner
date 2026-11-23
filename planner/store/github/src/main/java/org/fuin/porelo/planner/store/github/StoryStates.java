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

    /**
     * The story's warning naming each of its issues that stands before the state derived for it.
     *
     * @param storyState the state {@link #storyState} gave the story
     * @param ids the story's issues
     * @param issueStates their states, one for each of {@code ids}
     * @return the warning, or empty when none stands behind it or the rule names none
     */
    default java.util.Optional<org.fuin.porelo.planner.store.Warning> behind(
            final String storyState, final List<String> ids, final List<String> issueStates) {
        return java.util.Optional.empty();
    }

    /**
     * The board's fault when the workflow gives no story its state from its issues; the mapping
     * then names it among the board's faults and places no open story by a guess.
     *
     * @return the fault, or empty when the workflow gives the rule
     */
    default java.util.Optional<String> fault() {
        return java.util.Optional.empty();
    }

    /**
     * The project's rule as the mapping reads it, each call asking the project the supplier gives
     * at that moment, so a workflow read again counts at once.
     *
     * @param project the project's rule as it stands
     * @return the rule, its warnings and its fault all taken from the project's
     */
    static StoryStates following(
            final java.util.function.Supplier<
                            org.fuin.porelo.planner.store.TrackedProject.StoryStates>
                    project) {
        return new StoryStates() {
            @Override
            public @Nullable String storyState(final List<String> issueStates) {
                return project.get().storyState(issueStates);
            }

            @Override
            public java.util.Optional<org.fuin.porelo.planner.store.Warning> behind(
                    final String storyState,
                    final List<String> ids,
                    final List<String> issueStates) {
                return project.get().behind(storyState, ids, issueStates);
            }

            @Override
            public java.util.Optional<String> fault() {
                return project.get().fault();
            }
        };
    }
}
