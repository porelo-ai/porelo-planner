package org.fuin.porelo.planner.store;

import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * How much harm an issue's change can do, as its head names it; the work rules choose a task's
 * model and effort by it.
 */
public enum Risk {

    /** A change that can do harm, worked with the most care. */
    HARM("harm", 2),

    /** An ordinary change, and every issue whose head names no risk. */
    ORDINARY("ordinary", 1),

    /** A change that can do little harm. */
    SMALL("small", 0);

    private final String spelled;

    // The order stands here, never in the declaration, so a reordering cannot lower a task's risk.
    private final int weight;

    Risk(final String spelled, final int weight) {
        this.spelled = spelled;
        this.weight = weight;
    }

    /**
     * The risk as a head and a work rule spell it.
     *
     * @return {@code harm}, {@code ordinary} or {@code small}
     */
    public String spelled() {
        return spelled;
    }

    /**
     * Reads a risk as a head or a work rule spells it.
     *
     * @param spelled the spelling, lower case
     * @return the risk
     * @throws IllegalArgumentException when the spelling is none of the three
     */
    public static Risk of(final String spelled) {
        for (final Risk risk : values()) {
            if (risk.spelled.equals(spelled)) {
                return risk;
            }
        }
        throw new IllegalArgumentException(
                "risk " + Ids.quoted(spelled) + " is none of harm, ordinary and small");
    }

    /**
     * The highest of several risks, so a careful choice is never lost to a lighter issue beside it.
     *
     * @param risks the risks of a task's issues
     * @return the highest, or {@link #ORDINARY} for a task with no issue
     */
    public static Risk highest(final Collection<Risk> risks) {
        @Nullable Risk highest = null;
        for (final Risk risk : risks) {
            if (highest == null || risk.weight > highest.weight) {
                highest = risk;
            }
        }
        return highest == null ? ORDINARY : highest;
    }
}
