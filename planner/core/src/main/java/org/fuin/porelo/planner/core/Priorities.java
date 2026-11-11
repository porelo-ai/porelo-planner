package org.fuin.porelo.planner.core;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.Story;
import org.fuin.porelo.planner.store.Writes;

/**
 * Checks a change of a story's priority against the board a window shows and answers what saving it
 * writes: the priority, nothing when it is the story's already, or a refusal.
 */
public final class Priorities {

    private Priorities() {}

    /**
     * Checks a priority change.
     *
     * @param board the board the window shows
     * @param story the story's id
     * @param priority the priority it is to have, one of {@link Story#PRIORITIES}
     * @return what saving the change writes; nothing when the story has that priority already
     * @throws Moves.Refused {@code NoSuchStory} for a story the board lacks
     * @throws IllegalArgumentException for a priority none of the five
     */
    public static Writes check(final Board board, final String story, final String priority) {
        if (!Story.PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("The priority is none of " + Story.PRIORITIES + ".");
        }
        final Story found =
                board.story(story)
                        .orElseThrow(
                                () ->
                                        new Moves.Refused(
                                                "NoSuchStory",
                                                "There is no story " + story + ".",
                                                Map.of("story", story)));
        if (found.priority().equals(priority)) {
            return new Writes(Map.of(), Map.of(), List.of());
        }
        return new Writes(Map.of(), Map.of(), List.of(), Map.of(), Map.of(story, priority));
    }
}
