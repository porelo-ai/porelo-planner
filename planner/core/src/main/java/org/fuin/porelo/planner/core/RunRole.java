package org.fuin.porelo.planner.core;

/**
 * A run's role at its place, as Porelo's {@code Start} takes it: only what the run does there,
 * never a person's duty, which is the planner's own setting.
 */
public enum RunRole {
    /** The run writes the work and answers what is open at its place. */
    AUTHOR,
    /** The run reviews another's work at its place. */
    REVIEWER
}
