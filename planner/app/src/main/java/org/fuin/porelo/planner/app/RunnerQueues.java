package org.fuin.porelo.planner.app;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.planner.core.RefusedEventResave;
import org.fuin.porelo.planner.store.Board;
import org.fuin.porelo.planner.store.CommandGroup;
import org.fuin.porelo.planner.store.CommandOutcome;
import org.fuin.porelo.planner.store.PlanningStore;
import org.fuin.porelo.planner.store.git.LogText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The queues of the projects whose saves are kept in an outbox: each the command runner over the
 * project's outbox, the file beside its work record.
 */
final class RunnerQueues implements ProjectQueues {

    private static final Logger LOG = LoggerFactory.getLogger(RunnerQueues.class);

    @Override
    public ProjectQueue queue(final Place place, final PlanningStore store, final Runnable saved) {
        final CommandOutbox outbox =
                new CommandOutbox(
                        place.environment(),
                        place.directory(),
                        place.project(),
                        place.repository(),
                        place.branch(),
                        place.files(),
                        place.limits());
        return new Runner(outbox, new CommandRunner(outbox, store), saved);
    }

    /** One project's runner and its outbox, as the registry opens, resumes and drains them. */
    private static final class Runner implements ProjectQueue {

        private final CommandOutbox outbox;

        private final CommandRunner runner;

        private final Runnable saved;

        /** The groups waiting when the outbox opened: the only ones the resume runs. */
        private volatile List<String> atOpen = List.of();

        Runner(final CommandOutbox outbox, final CommandRunner runner, final Runnable saved) {
            this.outbox = outbox;
            this.runner = runner;
            this.saved = saved;
        }

        @Override
        public CommandOutcome submit(final CommandGroup group) {
            return runner.submit(group);
        }

        @Override
        public Map<String, CommandOutbox.State> open(final Supplier<Board> board) {
            final Map<String, CommandOutbox.State> states = outbox.open(board);
            // Taken before the project is given: a group queued later is its submitter's alone.
            atOpen = outbox.waiting();
            return states;
        }

        @Override
        public List<CommandOutbox.Group> pending() {
            return outbox.pending();
        }

        @Override
        public boolean fresh(final String group) {
            return outbox.fresh(group);
        }

        @Override
        public void unqueue(final String group) {
            outbox.unqueue(group);
        }

        @Override
        public void hold(final String group, final Throwable why) {
            outbox.hold(group, why);
        }

        /** Runs what waits on a thread of its own, then tells the windows what it wrote. */
        @Override
        public Thread resumeAside() {
            return Thread.ofPlatform()
                    .name("planner-command-resume")
                    .daemon(true)
                    .start(
                            () -> {
                                final boolean ended;
                                try {
                                    ended = runner.resume(atOpen);
                                } catch (final RuntimeException closed) {
                                    // Drained before it began, or the outbox closed: the next
                                    // open runs what waits.
                                    LOG.warn(
                                            "The groups waiting at open were not run: {}",
                                            LogText.of(String.valueOf(closed.getMessage())));
                                    return;
                                }
                                if (ended) {
                                    saved.run();
                                }
                            });
        }

        @Override
        public boolean drain(final Duration within) {
            return runner.drain(within);
        }

        @Override
        public HeldSaves held(final RefusedEventResave runs) {
            return new OutboxHeldSaves(outbox, runner, new OutboxResave(runs, outbox));
        }
    }
}
