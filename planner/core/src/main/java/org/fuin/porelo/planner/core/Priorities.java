package org.fuin.porelo.planner.core;

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
     * @throws Moves.Refused {@code NoSuchStory} for a story the board lacks, {@code MoveNotAllowed}
     *     for an id naming two stories or a story the board holds back
     * @throws IllegalArgumentException for a priority none of the five
     */
    public static Writes check(final Board board, final String story, final String priority) {
        if (!Story.PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("The priority is none of " + Story.PRIORITIES + ".");
        }
        final java.util.Optional<String> twice = board.twice(story);
        if (twice.isPresent()) {
            throw new Moves.Refused(
                    "MoveNotAllowed", twice.get(), Moves.item(story, "", "", twice.get()));
        }
        final Story found =
                board.story(story)
                        .orElseThrow(
                                () ->
                                        new Moves.Refused(
                                                "NoSuchStory",
                                                "There is no story " + story + ".",
                                                Map.of("story", story)));
        final java.util.Optional<String> held = board.holdsWork(story);
        if (held.isPresent()) {
            // A priority set on a story the store could not fully read builds on a hand edit.
            throw new Moves.Refused(
                    "MoveNotAllowed", held.get(), Moves.item(story, found.state(), "", held.get()));
        }
        if (found.priority().equals(priority)) {
            return new Writes(Map.of(), Map.of());
        }
        return new Writes(Map.of(), Map.of(), Map.of(), Map.of(story, priority));
    }
}
