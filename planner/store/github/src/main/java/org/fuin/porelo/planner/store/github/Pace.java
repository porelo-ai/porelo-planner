package org.fuin.porelo.planner.store.github;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * How often GitHub may be asked about a project: whether its rate limit is spent, how long to wait
 * before the next look, and what the board says of it.
 */
public interface Pace {

    /** A pace that never holds and never lengthens the interval. */
    Pace STEADY =
            new Pace() {
                @Override
                public @Nullable String hold() {
                    return null;
                }

                @Override
                public List<String> faults() {
                    return List.of();
                }

                @Override
                public Duration delay(final Duration probed) {
                    return probed;
                }
            };

    /**
     * Answers why GitHub is not asked now, while its rate limit is spent.
     *
     * @return the words of the refusal, free of any secret, or null when GitHub may be asked
     */
    @Nullable String hold();

    /**
     * Answers what the board says of the pace: a spent or low rate limit, a change check that
     * failed.
     *
     * @return the faults, empty when GitHub is asked as usual
     */
    List<String> faults();

    /**
     * Answers how long to wait before the next look.
     *
     * @param probed the usual interval of a project asked whether it changed
     * @return the wait, until the hold ends while one holds, lengthened while the limit is low
     */
    Duration delay(Duration probed);
}
