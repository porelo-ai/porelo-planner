package org.fuin.porelo.planner.store;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The fields of an issue a person sets by hand: each one named is written, null clearing it where
 * the issue may be without it; one not named stays as it is.
 *
 * @param named the fields written
 * @param size the size, one of {@link #SIZES}, or null to clear it
 * @param estimateMinutes the estimate in minutes, 1 to {@link #MOST_ESTIMATE}, or null to clear it
 * @param priority the priority, one of {@link Story#PRIORITIES}; never cleared
 * @param rank the order within its priority, 1 to {@link #MOST_RANK}, or null to clear it
 */
public record Fields(
        Set<Fields.Field> named,
        @Nullable String size,
        @Nullable Integer estimateMinutes,
        @Nullable String priority,
        @Nullable Integer rank) {

    /** The sizes an issue may have, the smallest first. */
    public static final List<String> SIZES = List.of("XS", "S", "M", "L", "XL");

    /** The highest estimate, in minutes. */
    public static final int MOST_ESTIMATE = 100_000;

    /** The highest rank. */
    public static final int MOST_RANK = 99_999;

    /** The fields a person sets by hand. */
    public enum Field {
        /** The size. */
        SIZE,
        /** The estimate in minutes. */
        ESTIMATE,
        /** The priority. */
        PRIORITY,
        /** The order within its priority. */
        RANK
    }

    /**
     * Checks every value against its field's bounds and copies the named fields.
     *
     * @throws IllegalArgumentException for a value out of its bounds, a value of a field not named,
     *     or a priority named without a value
     */
    public Fields {
        named = named.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(named));
        given(named, Field.SIZE, size);
        given(named, Field.ESTIMATE, estimateMinutes);
        given(named, Field.PRIORITY, priority);
        given(named, Field.RANK, rank);
        if (size != null && !SIZES.contains(size)) {
            throw new IllegalArgumentException("The size must be XS, S, M, L or XL.");
        }
        if (estimateMinutes != null && (estimateMinutes < 1 || estimateMinutes > MOST_ESTIMATE)) {
            throw new IllegalArgumentException(
                    "The estimate must be 1 to " + MOST_ESTIMATE + " minutes.");
        }
        if (named.contains(Field.PRIORITY)
                && (priority == null || !Story.PRIORITIES.contains(priority))) {
            throw new IllegalArgumentException(
                    "The priority must be Highest, High, Medium, Low or Lowest.");
        }
        if (rank != null && (rank < 1 || rank > MOST_RANK)) {
            throw new IllegalArgumentException("The rank must be 1 to " + MOST_RANK + ".");
        }
    }

    /**
     * Answers whether nothing is written.
     *
     * @return whether no field is named
     */
    public boolean none() {
        return named.isEmpty();
    }

    // A value of a field not named would be written by a store that reads values, not names.
    private static void given(
            final Set<Field> named, final Field field, final @Nullable Object value) {
        if (value != null && !named.contains(field)) {
            throw new IllegalArgumentException("The field " + field + " is not named.");
        }
    }
}
