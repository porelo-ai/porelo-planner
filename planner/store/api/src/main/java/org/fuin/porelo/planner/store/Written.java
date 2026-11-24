package org.fuin.porelo.planner.store;

import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** What became of one command written alone: written, or a conflict for the owner account. */
public sealed interface Written permits Written.Done, Written.Conflict {

    /**
     * Written, or standing already as the command would have written it.
     *
     * @param board the board after the write, or null when the store read none after it
     * @param created what a create made, or null for a command that creates nothing
     */
    record Done(@Nullable Board board, CommandOutcome.@Nullable Created created)
            implements Written {}

    /**
     * The item moved since the command was judged, at places the command writes or read: nothing is
     * written, and the owner account decides.
     *
     * @param places the places, named in the store's fixed words, never free text
     */
    record Conflict(List<String> places) implements Written {

        /** The most places a conflict names. */
        public static final int MOST = 32;

        /** A place's name: a kind, and for a field, a label or a body part its own name. */
        public static final Pattern PLACE =
                Pattern.compile("[A-Z_]{1,32}(:[A-Za-z0-9 ._-]{1,64})?");

        /** Copies the places; refuses none, too many, or one that is no place's name. */
        public Conflict {
            places = List.copyOf(places);
            if (places.isEmpty() || places.size() > MOST) {
                throw new IllegalArgumentException("A conflict names 1 to " + MOST + " places.");
            }
            for (final String place : places) {
                if (!PLACE.matcher(place).matches()) {
                    throw new IllegalArgumentException("A conflict names a place by its kind.");
                }
            }
        }
    }
}
