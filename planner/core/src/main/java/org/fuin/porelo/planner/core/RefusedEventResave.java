package org.fuin.porelo.planner.core;

import org.fuin.porelo.planner.store.Board;

/**
 * Saves again, for the owner account, a task's event the store refused before it wrote anything:
 * the event stands kept with its refusal under Needs you, the run not halted, and its save is named
 * by the id of the command group the refusal kept.
 */
public interface RefusedEventResave {

    /**
     * Tells whether the story's run keeps an event the store refused before writing, under the
     * group named, with the run not halted and no save of it in flight.
     *
     * @param story the story's id
     * @param group the id of the refused save's command group
     * @return true when the event can be saved again
     */
    boolean resavable(String story, String group);

    /**
     * Saves the kept event again, on the board as it stands now, as the task's identical repeated
     * report does: once per offer and fingerprint, a save that stands clearing the refusal. An
     * event already saved, by an earlier resave or a repeated report, is saved no second time and
     * refused.
     *
     * @param story the story's id
     * @param group the id of the refused save's command group
     * @return the board after the save
     * @throws WorkRefusal {@code NoSuchStory} for a story without a run, {@code RunChanged} when
     *     the run keeps no event refused under the group, or the work's refusal of the event judged
     *     again
     * @throws org.fuin.porelo.planner.store.StoreException the store's refusal as it gave it; one
     *     given before writing keeps the event under Needs you, under the new save's group
     */
    Board resave(String story, String group);
}
