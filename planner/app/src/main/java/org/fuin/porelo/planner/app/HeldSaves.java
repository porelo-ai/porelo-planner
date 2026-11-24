package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.core.WorkRefusal;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.CommandOutcome;

/**
 * The saves one project's outbox keeps for the person, and the calls that resend or drop them: the
 * seam between the owner account's calls and the project's outbox, its runner and its runs.
 */
interface HeldSaves {

    /** Runs that keep no refused event: nothing of a run is saved again. */
    RefusedEventResave NO_RUNS =
            new RefusedEventResave() {
                @Override
                public boolean resavable(final String story, final String group) {
                    return false;
                }

                @Override
                public Board resave(final String story, final String group) {
                    throw WorkRefusal.runChanged(story, "The run keeps no refused event.");
                }
            };

    /** A project whose saves go through no outbox: it keeps nothing, so nothing is resent. */
    HeldSaves NONE =
            new HeldSaves() {
                @Override
                public List<CommandOutbox.Group> groups() {
                    return List.of();
                }

                @Override
                public CommandOutcome resend(final String group) {
                    throw new IllegalStateException("The project keeps no group " + group + ".");
                }

                @Override
                public void drop(final String group) {
                    throw new IllegalStateException("The project keeps no group " + group + ".");
                }

                @Override
                public RefusedEventResave runs() {
                    return NO_RUNS;
                }
            };

    /**
     * Every group the outbox holds not written whole, this source's first in order, then those kept
     * from other sources; those still running included.
     *
     * @return the groups
     */
    List<CommandOutbox.Group> groups();

    /**
     * Sends a window's held group again through the project's runner, each command with the
     * versions it was judged on.
     *
     * @param group the group's id
     * @return what became of it
     * @throws IllegalStateException when the outbox holds the group no longer, or not held
     * @throws org.fuin.porelo.planner.store.StoreException.Busy while the project's saves stop; the
     *     group stays as it was
     */
    CommandOutcome resend(String group);

    /**
     * Removes a held group from the outbox.
     *
     * @param group the group's id
     * @throws IllegalStateException when the outbox holds the group no longer, or not held
     * @throws org.fuin.porelo.planner.store.StoreException.Busy while the project's saves stop; the
     *     group stays as it was
     */
    void drop(String group);

    /**
     * The project's runs, which save their own refused groups again; the adapter given takes a
     * group saved again out of the outbox.
     *
     * @return the runs
     */
    RefusedEventResave runs();
}
