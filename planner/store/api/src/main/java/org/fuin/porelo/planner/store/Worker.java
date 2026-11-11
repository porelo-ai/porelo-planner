package org.fuin.porelo.planner.store;

/** Who works an issue: an agent through the tasks the planner starts, or a person by hand. */
public enum Worker {

    /** An agent works the issue in the tasks the planner starts for it. */
    AGENT,

    /** A person works the issue and moves it; the planner starts no task for it. */
    MANUAL
}
