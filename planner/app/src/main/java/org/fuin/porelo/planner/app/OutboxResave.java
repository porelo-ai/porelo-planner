package org.fuin.porelo.planner.app;

import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Saves a task's refused event again through the run, and takes the refused save's group out of the
 * project's outbox once the run no longer keeps the event under it: saved again, refused again
 * under a new group, or saved meanwhile by the task's repeated report. Only a group the store
 * refused before writing anything leaves the outbox, so nothing that may stand in part is lost.
 */
final class OutboxResave implements RefusedEventResave {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxResave.class);

    private final RefusedEventResave runs;
    private final CommandOutbox outbox;

    /**
     * Saves through the runs given, with the project's outbox.
     *
     * @param runs the project's runs
     * @param outbox the project's outbox, open
     */
    OutboxResave(final RefusedEventResave runs, final CommandOutbox outbox) {
        this.runs = runs;
        this.outbox = outbox;
    }

    @Override
    public boolean resavable(final String story, final String group) {
        return runs.resavable(story, group);
    }

    @Override
    public Board resave(final String story, final String group) {
        try {
            return runs.resave(story, group);
        } finally {
            if (!runs.resavable(story, group) && outbox.refusedUnwritten(group, story)) {
                try {
                    outbox.drop(group);
                    LOG.info(
                            "The refused group {} of story {} left the outbox",
                            LogText.of(group),
                            LogText.of(story));
                } catch (final RuntimeException kept) {
                    // The run's answer stands; the group stays refused in the file, where it
                    // writes nothing, and the owner account sees it.
                    LOG.warn(
                            "The refused group {} of story {} could not leave the outbox: {}",
                            LogText.of(group),
                            LogText.of(story),
                            LogText.of(String.valueOf(kept.getMessage())));
                }
            }
        }
    }
}
