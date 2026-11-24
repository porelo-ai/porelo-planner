package org.fuin.porelo.planner.app;

/** Gives each project the saves its outbox keeps for the person. */
@FunctionalInterface
interface HeldSavesOf {

    /** Projects whose saves go through no outbox. */
    HeldSavesOf NONE = project -> HeldSaves.NONE;

    /**
     * Answers a project's held saves.
     *
     * @param project the project's name
     * @return its held saves; {@link HeldSaves#NONE} for a project that keeps none
     */
    HeldSaves of(String project);
}
