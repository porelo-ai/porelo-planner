package org.fuin.porelo.planner.app;

import org.fuin.porelo.planner.store.CommandQueue;
import org.fuin.porelo.planner.store.DirectQueue;
import org.fuin.porelo.planner.store.PlanningStore;

/** Gives each project's saves the queue that writes their commands. */
@FunctionalInterface
public interface CommandQueues {

    /** The queue that writes a group by calling the project's store directly. */
    CommandQueues DIRECT = (project, store) -> new DirectQueue(store);

    /**
     * Answers the queue of a project.
     *
     * @param project the project's name
     * @param store the project's store
     * @return the queue its saves are handed to
     */
    CommandQueue queue(String project, PlanningStore store);
}
