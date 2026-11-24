package org.fuin.porelo.planner.app;

import java.util.List;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.store.CommandOutcome;

/**
 * A project's held saves over its outbox: the groups it tells with the story whose run saves each,
 * a window's group resent through the project's runner, a group dropped from the file, and a run's
 * group saved again by its run.
 */
final class OutboxHeldSaves implements HeldSaves {

    private final CommandOutbox outbox;

    private final CommandRunner runner;

    private final RefusedEventResave runs;

    /**
     * Keeps the saves of one project's outbox.
     *
     * @param outbox the project's outbox
     * @param runner the project's runner over that outbox, which sends a window's group again
     * @param runs the project's runs, through the adapter that takes a group saved again out of the
     *     outbox
     */
    OutboxHeldSaves(
            final CommandOutbox outbox, final CommandRunner runner, final RefusedEventResave runs) {
        this.outbox = outbox;
        this.runner = runner;
        this.runs = runs;
    }

    @Override
    public List<CommandOutbox.Group> groups() {
        return outbox.pending();
    }

    @Override
    public CommandOutcome resend(final String group) {
        return runner.resend(group);
    }

    @Override
    public void drop(final String group) {
        // Through the runner, so a drop never meets the group's run or a closing outbox.
        runner.drop(group);
    }

    @Override
    public RefusedEventResave runs() {
        return runs;
    }
}
