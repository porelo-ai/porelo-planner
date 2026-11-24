package org.fuin.porelo.planner.store;

/** Takes the command groups of one project's saves and answers what became of each. */
public interface CommandQueue {

    /**
     * Writes a group's commands in order, stopping at the first refused.
     *
     * @param group the group
     * @return its outcome
     */
    CommandOutcome submit(CommandGroup group);
}
