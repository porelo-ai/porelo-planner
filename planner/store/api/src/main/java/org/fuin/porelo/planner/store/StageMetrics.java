package org.fuin.porelo.planner.store;

import java.util.Optional;

/** Gives a stage's numbers from Porelo's record of the task that handed the result back. */
@FunctionalInterface
public interface StageMetrics {

    /** Knows no numbers; the stage comment then holds the result alone. */
    StageMetrics NONE = (issue, result) -> Optional.empty();

    /**
     * Answers the stage's numbers for one issue of the result.
     *
     * @param issue the issue's id
     * @param result the stage's result
     * @return its numbers, if Porelo's record holds them
     */
    Optional<StageEntry> stage(String issue, StageResult result);
}
